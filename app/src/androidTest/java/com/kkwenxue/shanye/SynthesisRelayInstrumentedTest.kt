package com.kkwenxue.shanye

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkwenxue.shanye.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.*
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class SynthesisRelayInstrumentedTest {
    private val context=object: ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        val root=File(baseContext.filesDir,"relay-tests").apply {mkdirs()}
        override fun getApplicationContext(): Context=this
        override fun getFilesDir()=File(root,"files").apply {mkdirs()}
        override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
        override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        override fun getDatabasePath(name: String)=if(File(name).isAbsolute) File(name) else File(root,name)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?)=
            SQLiteDatabase.openDatabase(getDatabasePath(name).path,factory,SQLiteDatabase.CREATE_IF_NECESSARY)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,handler:DatabaseErrorHandler?)=
            SQLiteDatabase.openDatabase(getDatabasePath(name).path,factory,SQLiteDatabase.CREATE_IF_NECESSARY,handler)
    }
    private val book=Book("relay-test","测试小说","作者","测试","","",0,listOf(
        Chapter("relay-test-10","第10章：风声","风🌊很轻。\n\n雨停了！"),
        Chapter("relay-test-12","第12章：灯下","灯亮了。")))
    private val taskId="11111111-1111-4111-8111-111111111111"
    private fun clean() { context.root.deleteRecursively(); context.root.mkdirs() }

    private data class Request(val path:String,val headers:Map<String,String>,val body:ByteArray)
    private data class Reply(val status:Int=200,val body:ByteArray, val headers:Map<String,String> = emptyMap())
    private class HttpFixture(val route:(Request)->Reply): Closeable {
        private val socket=ServerSocket(0)
        val port=socket.localPort
        private val thread=Thread {
            while(!socket.isClosed) try {
                socket.accept().use { client ->
                    val input=client.getInputStream().buffered()
                    fun line():String {
                        val bytes=ByteArrayOutputStream()
                        while(true) { val next=input.read(); if(next<0 || next==10) break; if(next!=13) bytes.write(next) }
                        return bytes.toString("UTF-8")
                    }
                    val path=line().split(" ")[1]
                    val headers=mutableMapOf<String,String>()
                    while(true) { val entry=line(); if(entry.isBlank()) break; headers[entry.substringBefore(':').lowercase()]=entry.substringAfter(':').trim() }
                    val body=ByteArray(headers["content-length"]?.toInt() ?: 0)
                    var offset=0
                    while(offset<body.size) { val n=input.read(body,offset,body.size-offset); if(n<0) break; offset+=n }
                    val reply=route(Request(path,headers,body))
                    val output=client.getOutputStream()
                    val response="HTTP/1.1 ${reply.status} Result\r\nContent-Length: ${reply.body.size}\r\nConnection: close\r\n"+
                        reply.headers.entries.joinToString("") {"${it.key}: ${it.value}\r\n"}+"\r\n"
                    output.write(response.toByteArray()); output.write(reply.body); output.flush()
                }
            } catch (_:java.net.SocketException) { break }
        }.apply { isDaemon=true;start() }
        override fun close() {socket.close();thread.join(2000)}
        fun config()=RelayConnection("http://127.0.0.1:$port","test-relay-secret")
    }
    private fun task(request:JSONObject, bytes:ByteArray):JSONObject=JSONObject().put("id",taskId).put("kind","tts")
        .put("state","completed").put("created_at",System.currentTimeMillis()/1000).put("progress",JSONObject())
        .put("metadata",request.getJSONObject("metadata")).put("payload",request.getJSONObject("payload"))
        .put("result",JSONObject().put("bytes",bytes.size).put("sha256",AudioLibrary.sha256(bytes))
            .put("expires_at",System.currentTimeMillis()/1000+3600))
    private fun audio():ByteArray {
        val samples=24000*3
        val buffer=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(24000).putInt(48000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples*2)
        repeat(samples) {buffer.putShort((kotlin.math.sin(it*2*Math.PI*220/24000)*1000).toInt().toShort())}
        return buffer.array()
    }
    private fun bundle():ByteArray {
        val sound=audio();val chapter=book.chapters.first();val text=chapter.text
        val hash=AudioLibrary.sha256(text.toByteArray())
        val artifact="c".repeat(32)
        val manifest=JSONObject().put("schema_version",2).put("complete",true).put("artifact_id",artifact)
            .put("book_id",book.id).put("chapter_id",chapter.id).put("duration_ms",3000)
            .put("offsets","UTF-16 into Android Chapter.text").put("source_sha256",hash)
            .put("source_revision",AudioLibrary.sha256("${chapter.id}:$hash:reader-canonical+OpenCC-t2s-v1:reader-sentence-bounded160-v1".toByteArray()))
            .put("audio_sha256",AudioLibrary.sha256(sound)).put("profile",JSONObject().put("speaker","Uncle_Fu"))
            .put("cues",JSONArray().put(JSONObject().put("start_ms",0).put("end_ms",3000).put("start_utf16",0)
                .put("end_utf16",text.length).put("original_text",text))).toString().toByteArray()
        val catalogue=JSONObject().put("schema_version",1).put("entries",JSONArray().put(JSONObject()
            .put("artifact_id",artifact).put("chapter_id",chapter.id).put("audio","chapter.m4a").put("manifest","chapter.json")
            .put("audio_sha256",AudioLibrary.sha256(sound)).put("manifest_sha256",AudioLibrary.sha256(manifest))))
        val bytes=ByteArrayOutputStream()
        ZipOutputStream(bytes).use {zip ->
            listOf("chapter.m4a" to sound,"chapter.json" to manifest,"catalogue.json" to catalogue.toString().toByteArray()).forEach {(name,data)->
                zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
    @Test fun selectedPayloadPreservesCanonicalUnicodeTextAndUsesRealChapterNumbers() {
        assertEquals(listOf(0,1),SynthesisPayload.resolve(book,"10,12"))
        assertTrue(runCatching {SynthesisPayload.resolve(book,"1")}.isFailure)
        assertTrue(runCatching {SynthesisPayload.resolve(book,"10-12")}.isFailure)
        assertTrue(runCatching {SynthesisPayload.resolve(book,"10-99999999999999999")}.isFailure)
        val raw=SynthesisPayload.create(book,listOf(0),"narrator-uncle-fu-v1","福叔","one",.9f)
        val chapter=raw.getJSONObject("payload").getJSONObject("request").getJSONArray("inline_chapters").getJSONObject(0)
        assertEquals(book.chapters[0].text,chapter.getString("canonical_text"))
        assertEquals(10,chapter.getInt("chapter_number"))
        assertEquals(AudioLibrary.sha256(book.chapters[0].text.toByteArray()),chapter.getString("source_sha256"))
        assertEquals("android:one",raw.getString("dedupe_key"))
        assertEquals(.9,raw.getJSONObject("payload").getJSONObject("request").getJSONObject("export").getDouble("speed"),.001)
        assertTrue(runCatching { SynthesisPayload.create(book,listOf(0),"narrator-uncle-fu-v1","福叔","invalid-speed",.85f) }.isFailure)
    }
    @Test fun uncertainSubmissionSurvivesRepositoryRecreationAndRetriesSameRequest() {
        clean();val bodies=mutableListOf<String>();val data=bundle()
        HttpFixture {request ->
            assertEquals("test-relay-secret",request.headers["x-relay-token"])
            bodies.add(String(request.body))
            Reply(if(bodies.size==1) 503 else 200,task(JSONObject(String(request.body)),data).toString().toByteArray())
        }.use {server ->
            val repo=SynthesisRepository(context)
            val key=repo.prepare(server.config(),book,listOf(0),"narrator-uncle-fu-v1","福叔")
            assertTrue(runCatching {repo.publish(server.config(),key)}.isFailure)
            val restarted=SynthesisRepository(context)
            assertEquals(key,restarted.pending(server.config().endpoint).single().key)
            assertEquals(taskId,restarted.publish(server.config(),key).id)
            assertEquals(2,bodies.size);assertEquals(bodies[0],bodies[1])
            assertTrue(restarted.pending(server.config().endpoint).isEmpty())
            assertEquals(1,restarted.tasks(server.config().endpoint).size)
        }
        clean()
    }
    @Test fun completedServerTaskDownloadsValidatesImportsAndRemembersImport() {
        clean();val data=bundle()
        val response=task(SynthesisPayload.create(book,listOf(0),"narrator-uncle-fu-v1","福叔","import"),data)
        HttpFixture {request ->
            when {
                request.path.endsWith("/result") -> Reply(body=data)
                request.path.contains("?kind=") -> Reply(body=JSONObject().put("items",JSONArray().put(response)).put("next_cursor",JSONObject.NULL).toString().toByteArray())
                else -> Reply(body=response.toString().toByteArray())
            }
        }.use {server ->
            val repo=SynthesisRepository(context)
            assertNull(repo.refresh(server.config()))
            AudioLibrary(context).use {library ->
                assertEquals(1,repo.importTask(server.config(),taskId,library,listOf(book)))
                assertEquals(1,library.load(listOf(book)).size)
                assertEquals(book.chapters[0].text,library.load(listOf(book)).single().cues.single().text)
                assertTrue(SynthesisRepository(context).tasks(server.config().endpoint).single().importedAt>0)
                assertEquals(0,repo.importTask(server.config(),taskId,library,listOf(book)))
            }
            assertTrue(context.cacheDir.listFiles().orEmpty().none {it.name.startsWith("relay-audio-")})
        }
        clean()
    }
    @Test fun corruptedDownloadsAndForeignBooksNeverImport() {
        clean();val data=bundle()
        val response=task(SynthesisPayload.create(book,listOf(0),"narrator-uncle-fu-v1","福叔","bad"),data)
        var downloads=0
        HttpFixture {request ->
            if(request.path.endsWith("/result")) {downloads++;Reply(body=data.copyOf().apply {this[100]=(this[100]+1).toByte()})}
            else Reply(body=response.toString().toByteArray())
        }.use {server ->
            val repo=SynthesisRepository(context)
            AudioLibrary(context).use {library ->
                assertTrue(runCatching {repo.importTask(server.config(),taskId,library,emptyList())}.isFailure)
                assertEquals(0,downloads)
                assertTrue(runCatching {repo.importTask(server.config(),taskId,library,listOf(book))}.isFailure)
                assertTrue(library.load(listOf(book)).isEmpty())
                assertTrue(context.cacheDir.listFiles().orEmpty().none {it.name.startsWith("relay-audio-")})
            }
        }
        clean()
    }
    @Test fun lanResultImportsWithoutAnyRelayUploadOrDownload() {
        clean(); val data=bundle(); var relayDownloads=0; var transferRequests=0; var lanDownloads=0
        HttpFixture { request ->
            lanDownloads++
            assertEquals("scoped-zip-token",request.headers["x-result-token"])
            assertNull(request.headers["x-relay-token"])
            Reply(body=data)
        }.use { lan ->
            val response=task(SynthesisPayload.create(book,listOf(0),"narrator-uncle-fu-v1","福叔","lan"),data)
            response.getJSONObject("result").put("relay_state","none").put("local_available",true)
                .put("direct",JSONObject().put("url","http://127.0.0.1:${lan.port}/local.zip").put("token","scoped-zip-token")
                    .put("expires_at",System.currentTimeMillis()/1000+3600))
            HttpFixture { request ->
                if(request.path.endsWith("/result/request")) transferRequests++
                if(request.path.endsWith("/result")) relayDownloads++
                Reply(body=response.toString().toByteArray())
            }.use { server ->
                AudioLibrary(context).use { library ->
                    val repo=SynthesisRepository(context)
                    assertEquals(1,repo.importTask(server.config(),taskId,library,listOf(book)))
                    assertEquals(1,lanDownloads);assertEquals(0,transferRequests);assertEquals(0,relayDownloads)
                    assertFalse(repo.tasks(server.config().endpoint).single().task.json.contains("scoped-zip-token"))
                }
            }
        }
        clean()
    }
    @Test fun unreachableLanRequestsOneTransferWaitsThenReusesRelayPackage() {
        clean();val data=bundle();var requests=0;var downloads=0;val stages=mutableListOf<String>()
        HttpFixture { Reply(status=503,body=ByteArray(0)) }.use { lan ->
            val response=task(SynthesisPayload.create(book,listOf(0),"narrator-uncle-fu-v1","福叔","fallback"),data)
            response.getJSONObject("result").put("relay_state","none").put("local_available",true)
                .put("direct",JSONObject().put("url","http://127.0.0.1:${lan.port}/local.zip").put("token","scoped-zip-token")
                    .put("expires_at",System.currentTimeMillis()/1000+3600))
            HttpFixture { request ->
                when {
                    request.path.endsWith("/result/request") -> {
                        requests++;response.getJSONObject("result").put("relay_state","uploading")
                        Reply(body=response.toString().toByteArray())
                    }
                    request.path.endsWith("/result") -> {downloads++;Reply(body=data)}
                    else -> {
                        if(requests>0) response.getJSONObject("result").put("relay_state","ready")
                        Reply(body=response.toString().toByteArray())
                    }
                }
            }.use { server ->
                val repo=SynthesisRepository(context)
                AudioLibrary(context).use { library ->
                    assertEquals(1,repo.importTask(server.config(),taskId,library,listOf(book)) {phase,_,_ -> stages.add(phase)})
                    assertTrue(stages.contains("局域网不可达，申请中转"))
                    assertTrue(stages.contains("合成服务器正在上传中转包"))
                    assertTrue(stages.contains("中转下载中"))
                    assertEquals(1,requests);assertEquals(1,downloads)
                    assertEquals(0,repo.importTask(server.config(),taskId,library,listOf(book)))
                    assertEquals(1,requests);assertEquals(2,downloads)
                }
            }
        }
        clean()
    }
    @Test fun corruptedLanResultNeverTriggersPaidRelayTransfer() {
        clean();val data=bundle();var requests=0
        HttpFixture { Reply(body=data.copyOf().apply {this[100]=(this[100]+1).toByte()}) }.use { lan ->
            val response=task(SynthesisPayload.create(book,listOf(0),"narrator-uncle-fu-v1","福叔","bad-lan"),data)
            response.getJSONObject("result").put("relay_state","none").put("direct",JSONObject()
                .put("url","http://127.0.0.1:${lan.port}/local.zip").put("token","scoped-zip-token")
                .put("expires_at",System.currentTimeMillis()/1000+3600))
            HttpFixture { request ->
                if(request.path.endsWith("/result/request")) requests++
                Reply(body=response.toString().toByteArray())
            }.use { server ->
                AudioLibrary(context).use { library ->
                    assertTrue(runCatching {SynthesisRepository(context).importTask(server.config(),taskId,library,listOf(book))}.isFailure)
                    assertEquals(0,requests);assertTrue(library.load(listOf(book)).isEmpty())
                }
            }
        }
        clean()
    }
    @Test fun credentialsAreEncryptedAndRedirectsNeverReceiveThem() {
        clean()
        val config=RelayConnection("http://127.0.0.1:17870","private-test-credential")
        RelaySettings(context).save(config)
        assertEquals(config,RelaySettings(context).load())
        assertFalse(File(context.noBackupFilesDir,"relay-connection").readText().contains(config.token))
        var leaked=false
        HttpFixture {leaked=true;Reply(body="{}".toByteArray())}.use {target ->
            HttpFixture {Reply(status=302,body=ByteArray(0),headers=mapOf("Location" to "http://127.0.0.1:${target.port}/stolen"))}.use {source ->
                assertTrue(runCatching {SynthesisRelay(source.config()).health()}.isFailure)
                assertFalse(leaked)
            }
        }
        clean()
    }
}
