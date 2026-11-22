package com.kkwenxue.shanye

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkwenxue.shanye.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in: dispatch only this original, tiny smoke chapter to the live GPU. */
@RunWith(AndroidJUnit4::class)
class SynthesisServerAcceptanceTest {
    @Test fun dispatchOriginalChapterThroughPublicRelayAndImportGpuResult() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Requires an explicit private connection file",args.containsKey("relayConnection"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val configFile=File(context.filesDir,args.getString("relayConnection")!!)
        val raw=JSONObject(configFile.readText())
        val config=RelayConnection(raw.getString("endpoint"),raw.getString("token")).validated()
        val book=Book("relay-app-smoke-20261114","云端合成试音","山页","测试","App 派发与导入验收","原创试音",0,
            listOf(Chapter("relay-app-smoke-20261114-1","第1章：雨后",
                "雨停了，窗台还留着几滴水。\n\n林舟翻开书，平静地说：“故事就从这里开始。”")),
            remoteSource="catalog",remoteBookId=1L,activeRevision="fixture-v1",remoteLatestVersion="fixture-v1")
        BookDatabase(context).use {it.save(book)}
        val repository=SynthesisRepository(context)
        repository.settings.save(config)
        val key=repository.prepare(config,book,listOf(0),"narrator-uncle-fu-v1","福叔 · 醇厚男声")
        val task=repository.publish(config,key)
        println("Live relay task ${task.id}")
        val client=SynthesisRelay(config)
        var complete: JSONObject?=null
        val deadline=System.currentTimeMillis()+300000
        while(System.currentTimeMillis()<deadline) {
            val current=client.detail(task.id)
            assertNotEquals(current.optString("error"),"failed",current.getString("state"))
            repository.remember(config.endpoint,RelayTask.parse(current))
            if(current.getString("state")=="completed") {complete=current;break}
            Thread.sleep(2000)
        }
        assertNotNull("GPU task did not complete within 5 minutes",complete)
        val verifyLan=args.getString("verifyLan")=="true"
        if(verifyLan) {
            assertEquals("none",complete!!.getJSONObject("result").getString("relay_state"))
            assertTrue(complete!!.getJSONObject("result").has("direct"))
            println("Completion published a scoped LAN address without uploading ZIP")
        }
        val stages=mutableListOf<String>()
        AudioLibrary(context).use { library ->
            assertTrue(repository.importTask(config,task.id,library,listOf(book)) {phase,_,_ -> stages.add(phase)}>=0)
            val chapter=library.load(listOf(book)).first()
            assertTrue(chapter.file.length()>1000)
            assertEquals(book.chapters[0].id,chapter.chapterId)
            assertTrue(chapter.cues.isNotEmpty())
            chapter.cues.forEach {cue -> assertEquals(cue.text,book.chapters[0].text.substring(cue.start,cue.end))}
        }
        assertTrue(repository.tasks(config.endpoint).first {it.task.id==task.id}.importedAt>0)
        println("GPU result imported with validated UTF-16 cues")
        if(verifyLan) {
            assertTrue(stages.contains("局域网下载中"))
            assertFalse(stages.contains("中转下载中"))
            assertEquals("none",client.detail(task.id).getJSONObject("result").getString("relay_state"))
            println("LAN import succeeded; public relay still has no ZIP")
        }
        if(args.getString("verifyTransfer")=="true") {
            client.requestTransfer(task.id)
            var current=RelayTask.parse(client.detail(task.id))
            val transferDeadline=System.currentTimeMillis()+120000
            while(current.result?.relayState!="ready" && System.currentTimeMillis()<transferDeadline) {
                Thread.sleep(1000);current=RelayTask.parse(client.detail(task.id))
            }
            assertEquals("ready",current.result?.relayState)
            val archive=File.createTempFile("on-demand-acceptance-",".zip",context.cacheDir)
            try {
                client.download(task.id,current.result!!,archive)
                assertEquals(current.result!!.sha256,AudioLibrary.sha256(archive.readBytes()))
            } finally {archive.delete()}
            assertEquals(1,client.detail(task.id).getInt("attempts"))
            println("Explicit transfer request uploaded and downloaded the same verified ZIP without re-synthesis")
        }
    }
}
