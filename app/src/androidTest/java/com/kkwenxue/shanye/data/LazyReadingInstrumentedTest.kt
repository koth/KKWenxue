package com.kkwenxue.shanye.data

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class LazyReadingInstrumentedTest {
    private val target=InstrumentationRegistry.getInstrumentation().targetContext
    private fun context(name: String=UUID.randomUUID().toString())=object:ContextWrapper(target) {
        val root=File(target.filesDir,"lazy-tests/$name").apply{mkdirs()}
        override fun getApplicationContext():Context=this
        override fun getFilesDir()=File(root,"files").apply{mkdirs()}
        override fun getDatabasePath(name:String)=File(root,name)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?)=SQLiteDatabase.openDatabase(getDatabasePath(name).path,factory,SQLiteDatabase.CREATE_IF_NECESSARY)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,handler:DatabaseErrorHandler?)=SQLiteDatabase.openDatabase(getDatabasePath(name).path,factory,SQLiteDatabase.CREATE_IF_NECESSARY,handler)
    }
    private fun book(count:Int=3,revision:String="r1")=Book("online:catalog:77","在线测试书","作者","测试","","",1,(0 until count).map{Chapter("online:catalog:77:chapter:source:${it+1}","第${it+1}章","")},"catalog",77,revision,1,revision)
    private class Server(val handler:(String)->JSONObject):Closeable {
        val socket=ServerSocket(0)
        val hits=AtomicInteger()
        val client=CatalogClient("http://127.0.0.1:${socket.localPort}")
        val thread=Thread {
            while(!socket.isClosed)try {
                socket.accept().use { peer ->
                    val reader=peer.getInputStream().bufferedReader()
                    val path=reader.readLine().split(" ")[1]
                    while(!reader.readLine().isNullOrEmpty()){}
                    hits.incrementAndGet()
                    val bytes=handler(path).toString().toByteArray()
                    peer.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush() }
                }
            }catch(_:Exception){if(socket.isClosed)break}
        }.apply{isDaemon=true;start()}
        override fun close(){socket.close();thread.join(1000)}
    }
    private fun content(source:Long=1,body:String="春风吹过了山岗。\n\n这是足够长的正文，用来检查书签的位置。",revision:String="r1")=JSONObject().put("bookId",77).put("sourceId",source).put("chapterId","online:catalog:77:chapter:source:$source").put("revision",revision).put("body",body).put("bytes",body.toByteArray().size).put("sha256",syncSha256(body))

    @Test fun largeManifestNeverRequestsBodiesAndRejectsDuplicatePages() {
        Server { path ->
            assertTrue(path.contains("manifest"))
            val start=Regex("cursor=(\\d+)").find(path)?.groupValues?.get(1)?.toInt()?.plus(1) ?: 0
            val end=minOf(start+500,1201)
            JSONObject().put("bookId",77).put("revision","r1").put("chapterCount",1201).put("nextCursor",if(end<1201)end-1 else JSONObject.NULL)
                .put("items",JSONArray().apply{for(i in start until end)put(JSONObject().put("source_id",i+1).put("chapter_index",i).put("title","第${i+1}章"))})
        }.use { server ->
            val manifest=server.client.completeManifest(77)
            assertEquals(1201,manifest.chapters.size);assertEquals(3,server.hits.get())
        }
        Server { JSONObject().put("bookId",77).put("revision","r1").put("chapterCount",2).put("nextCursor",0).put("items",JSONArray().put(JSONObject().put("source_id",1).put("chapter_index",0).put("title","第一章"))) }.use { server ->
            assertTrue(runCatching{server.client.completeManifest(77)}.isFailure)
            assertTrue(server.hits.get()<=3)
        }
    }

    @Test fun cacheSurvivesReopenAndOfflineAndCorruptionIsRefetched()=runBlocking {
        val ctx=context();val refs=book()
        BookDatabase(ctx).use{it.save(refs)}
        val server=Server{content()}
        OnlineChapterStore(ctx).load(refs,0,server.client).also{assertTrue(it.chapters[0].body.isNotBlank())}
        assertEquals(1,server.hits.get())
        server.close()
        val reopened=BookDatabase(ctx).use{it.load(refs.id).single()}
        assertTrue(reopened.chapters.all{it.body.isEmpty()})
        assertTrue(OnlineChapterStore(ctx).load(reopened,0,server.client).chapters[0].body.isNotBlank())
        File(ctx.filesDir,"online-chapters").listFiles()!!.single().writeText("corrupt")
        Server{content()}.use { repair ->
            assertTrue(OnlineChapterStore(ctx).load(reopened,0,repair.client).chapters[0].body.isNotBlank())
            assertEquals(1,repair.hits.get())
        }
        Server{content(revision="wrong")}.use { stale ->
            assertTrue(runCatching{OnlineChapterStore(ctx).load(reopened,1,stale.client)}.isFailure)
            assertNull(OnlineChapterStore(ctx).cached(reopened,1))
        }
    }

    @Test fun manifestTransactionPreservesUnchangedCacheAndMapsReorderedAnchors()=runBlocking {
        val ctx=context();val original=book()
        BookDatabase(ctx).use{it.save(original)}
        val loaded=Server{path -> content(path.substringAfter("chapters/").substringBefore('?').toLong())}.use{server ->
            var b=OnlineChapterStore(ctx).load(original,0,server.client)
            b=OnlineChapterStore(ctx).load(b,1,server.client);b
        }
        val anchor=RevisionAnchor.capture(loaded,0,12)
        val refs=original.copy(activeRevision="r2",chapters=listOf(original.chapters[1].copy(title="已修改",bodySha256="f".repeat(64)),original.chapters[0],Chapter("online:catalog:77:chapter:source:4","新增章","")))
        BookDatabase(ctx).use { db ->
            assertTrue(runCatching{db.save(refs.copy(chapters=listOf(refs.chapters[0],refs.chapters[0])),original.activeRevision)}.isFailure)
            assertNotNull(db.latestChapterCache(original.id,original.chapters[1].id))
            db.save(refs,original.activeRevision)
        }
        val active=BookDatabase(ctx).use{it.load(original.id).single()}
        assertEquals(ReadingPosition(1,12),anchor.locate(active))
        assertNotNull(OnlineChapterStore(ctx).cached(active,1))
        assertNull(OnlineChapterStore(ctx).cached(active,0))
        val bodyLoaded=OnlineChapterStore(ctx).load(active,1,CatalogClient("http://127.0.0.1:1"))
        assertEquals(ReadingPosition(1,12),anchor.locate(bodyLoaded))
        val edited=bodyLoaded.copy(chapters=bodyLoaded.chapters.mapIndexed { i,c -> if(i==1)c.copy(body="补入一段前言。\n\n"+c.body) else c })
        val relocated=anchor.locate(edited)!!
        assertTrue(relocated.offset>12)
        assertEquals(bodyLoaded.chapters[1].text.substring(12,20),edited.chapters[1].text.substring(relocated.offset,relocated.offset+8))
        assertNull(anchor.locate(edited.copy(chapters=edited.chapters.map{it.copy(body="原文已被替换。") })))
    }

    @Test fun queueIsDurablePrioritizedAndAdjacentDoesNotReplaceFullBook() {
        val ctx=context();val refs=book(5000)
        ChapterQueue(ctx).use{it.enqueue(refs,refs.chapters.indices.toList(),"full");it.enqueue(refs,listOf(1,2),"range");it.enqueue(refs,listOf(3,4,0),"adjacent")}
        ChapterQueue(ctx).use{q ->
            assertEquals(5000,q.jobs(refs.id).first{it.scope=="full"}.total)
            assertEquals("adjacent",q.next()!!.scope)
            q.pause(refs);assertNull(q.next())
            q.resume(refs)
            val a=q.next()!!;q.finish(a)
            val b=q.next()!!;q.finish(b,"测试失败",false)
            assertEquals(1,q.jobs(refs.id).first{it.scope=="adjacent"}.failed.size)
        }
        ChapterQueue(ctx).use{q ->
            q.resume(refs)
            assertEquals(1,q.jobs(refs.id).first{it.scope=="adjacent"}.completed)
            assertEquals(5000,q.jobs(refs.id).first{it.scope=="full"}.total)
        }
    }

    @Test fun foregroundIsNotBlockedByAnActiveBackgroundDownload()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val bg=launch{ChapterRequests.run(false){entered.complete(Unit);release.await()}}
        entered.await()
        withTimeout(1000){ChapterRequests.run(true){assertTrue(true)}}
        release.complete(Unit);bg.join()
    }

    @Test fun explicitExportResumesInterruptedBytesAndRejectsBadHash() {
        val payload="zip-fixture-".repeat(1000).toByteArray()
        val cutoff=payload.size/2
        val requests=java.util.Collections.synchronizedList(mutableListOf<Map<String,String>>())
        val listener=ServerSocket(0)
        val thread=Thread {
            repeat(3) { attempt ->
                listener.accept().use { peer ->
                    val reader=peer.getInputStream().bufferedReader()
                    reader.readLine()
                    val headers=mutableMapOf<String,String>()
                    while(true){val line=reader.readLine();if(line.isNullOrEmpty())break;headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()}
                    requests.add(headers)
                    val body=when(attempt){0->payload.copyOf(cutoff);1->payload.copyOfRange(cutoff,payload.size);else->ByteArray(payload.size){1}}
                    val status=if(attempt==1)206 else 200
                    val length=if(attempt==0)payload.size else body.size
                    val range=if(attempt==1)"Content-Range: bytes $cutoff-${payload.size-1}/${payload.size}\r\n" else ""
                    peer.getOutputStream().apply{write("HTTP/1.1 $status Result\r\nContent-Length: $length\r\n${range}Connection: close\r\n\r\n".toByteArray());write(body);flush()}
                }
            }
        }.apply{isDaemon=true;start()}
        val file=File(context().filesDir,"export.part")
        val client=CatalogClient("http://127.0.0.1:${listener.localPort}")
        val hash=syncSha256(payload.toString(Charsets.UTF_8))
        val task=CatalogContentTask("export-fixture","completed",downloadUrl="fixture",sha256=hash,bytes=payload.size.toLong())
        try {
            assertTrue(runCatching{client.download(task,file)}.isFailure)
            assertEquals(cutoff.toLong(),file.length())
            assertTrue(client.download(task,file))
            assertArrayEquals(payload,file.readBytes())
            assertEquals("bytes=$cutoff-",requests[1]["range"])
            assertEquals("\"$hash\"",requests[1]["if-range"])
            // A complete verified file needs no new network request after restart.
            assertTrue(client.download(task,file));assertEquals(2,requests.size)
            file.delete()
            assertTrue(runCatching{client.download(task,file)}.isFailure)
            assertFalse(file.exists())
        } finally {listener.close();thread.join(1000);file.delete()}
    }

    @Test fun viewRepaginatesAfterAsyncBodyAndRequestsUncachedAdjacentChapter() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val refs=book()
        val loaded=refs.copy(chapters=refs.chapters.mapIndexed{index,c->if(index==0)c.copy(body="春风轻拂，故事继续。".repeat(300)) else c})
        var page=com.kkwenxue.shanye.reader.PageInfo()
        var next=-1
        lateinit var view:com.kkwenxue.shanye.reader.ReadingView
        val activity=androidx.test.core.app.ActivityScenario.launch(com.kkwenxue.shanye.MainActivity::class.java)
        instrumentation.waitForIdleSync()
        activity.onActivity { host ->
            view=com.kkwenxue.shanye.reader.ReadingView(host)
            host.setContentView(view)
            view.onPosition={page=it}
            view.onChapterRequested={index,_->next=index}
            view.configure(refs,0,700,20,true,true,0xff222222.toInt(),0xff888888.toInt())
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(1800,android.view.View.MeasureSpec.EXACTLY))
            view.layout(0,0,1080,1800)
            view.configure(loaded,0,700,20,true,true,0xff222222.toInt(),0xff888888.toInt())
        }
        instrumentation.waitForIdleSync()
        assertTrue(page.end>700 && page.start<=700)
        instrumentation.runOnMainSync {
            val bitmap=android.graphics.Bitmap.createBitmap(1080,1800,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(target.filesDir,"lazy-reader.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            view.goToOffset(-1);view.turn(1)
        }
        assertEquals(1,next)
        activity.close()
    }

    @Test fun cacheEvictionKeepsUserProtectedChapters()=runBlocking {
        val ctx=context();val refs=book()
        BookDatabase(ctx).use{it.save(refs)}
        val store=OnlineChapterStore(ctx,100)
        Server{path->content(path.substringAfter("chapters/").substringBefore('?').toLong(),"正文".repeat(100))}.use { server ->
            store.load(refs,0,server.client);store.protect(refs.id,refs.chapters[0].id)
            store.protect(refs.id,refs.chapters[0].id,2);store.unprotect(refs.id,refs.chapters[0].id,2)
            store.load(refs,1,server.client)
            store.load(refs,2,server.client)
            assertNotNull(store.cached(refs,0));assertNull(store.cached(refs,1));assertNotNull(store.cached(refs,2))
        }
    }

    @Test fun prepareProcessRestartFixture()=runBlocking {
        val ctx=context("process-restart");val refs=book()
        BookDatabase(ctx).use{it.save(refs)}
        Server{content()}.use{OnlineChapterStore(ctx).load(refs,0,it.client)}
        ChapterQueue(ctx).use{it.enqueue(refs,listOf(0,1,2),"full");it.finish(it.next()!!);it.pause(refs)}
    }
    @Test fun resumeProcessRestartFixtureOffline()=runBlocking {
        org.junit.Assume.assumeTrue("Run in a second process after preparing the fixture",InstrumentationRegistry.getArguments().getString("resumeAfterRestart")=="true")
        val ctx=context("process-restart")
        val refs=BookDatabase(ctx).use{it.load(book().id).single()}
        assertTrue(OnlineChapterStore(ctx).load(refs,0,CatalogClient("http://127.0.0.1:1")).chapters[0].body.isNotBlank())
        ChapterQueue(ctx).use{q -> assertEquals(1,q.jobs(refs.id).single().completed);assertEquals("paused",q.jobs(refs.id).single().state);q.resume(refs);assertEquals(refs.chapters[1].id,q.next()!!.chapterId)}
    }

    /** Explicit live-service acceptance; invoked separately from deterministic fixtures. */
    @Test fun liveLargeBookImportsReferencesAndOpensVerifiedChapter()=runBlocking {
        org.junit.Assume.assumeTrue("Live catalog acceptance is opt-in",InstrumentationRegistry.getArguments().getString("liveCatalog")=="true")
        val ctx=context();val client=CatalogClient()
        val started=android.os.SystemClock.elapsedRealtime()
        val metadata=client.book(2)
        val manifest=client.completeManifest(2)
        assertTrue(manifest.chapters.size>1000)
        val refs=Book("online:catalog:2",metadata.title,metadata.author,metadata.category,"","",1,
            manifest.chapters.map{Chapter("online:catalog:2:chapter:source:${it.sourceId}",it.title,"",it.bodySha256)},
            "catalog",2L,manifest.revision,metadata.latestChapterId,manifest.revision)
        BookDatabase(ctx).use{it.save(refs)}
        val imported=android.os.SystemClock.elapsedRealtime()
        assertTrue(BookDatabase(ctx).use{it.load(refs.id).single()}.chapters.all{it.body.isEmpty()})
        val loaded=OnlineChapterStore(ctx).load(refs,0,client)
        assertTrue(loaded.chapters.first().body.length>500)
        assertTrue(loaded.chapters.drop(1).all{it.body.isEmpty()})
        assertEquals(loaded.chapters.first().body,OnlineChapterStore(ctx).load(refs,0,CatalogClient("http://127.0.0.1:1")).chapters.first().body)
        File(target.filesDir,"lazy-live-acceptance.json").writeText(JSONObject().put("bookId",2).put("title",refs.title).put("chapterCount",refs.chapters.size)
            .put("manifestAndSaveMs",imported-started).put("chapterAndOfflineReadMs",android.os.SystemClock.elapsedRealtime()-imported)
            .put("bodyBytes",loaded.chapters.first().body.toByteArray().size).put("revision",manifest.revision).toString(2))
    }
}
