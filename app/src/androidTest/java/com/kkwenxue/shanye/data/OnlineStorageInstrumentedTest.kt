package com.kkwenxue.shanye.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OnlineStorageInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun migratesV1KeepsOldBookAndResumesTaskAcrossReopen() {
        val name = "sync-fixture-${UUID.randomUUID()}.db"
        try {
            context.openOrCreateDatabase(name, 0, null).use {
                it.execSQL("CREATE TABLE books (id TEXT PRIMARY KEY,title TEXT NOT NULL,author TEXT NOT NULL,genre TEXT NOT NULL,subtitle TEXT NOT NULL,description TEXT NOT NULL,cover INTEGER NOT NULL)")
                it.execSQL("CREATE TABLE chapters (id TEXT PRIMARY KEY,book_id TEXT NOT NULL,chapter_index INTEGER NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL)")
                it.execSQL("INSERT INTO books VALUES ('local-old','测试书','作者','文学','','从在线书库导入，支持离线阅读。',1)")
                it.execSQL("INSERT INTO chapters VALUES ('local-old-0','local-old',0,'第1章','旧书正文。')")
                it.version = 1
            }
            val source = CatalogBook(7, "测试书", "作者", "文学", "", 0, 2, "", "")
            val old = BookImporter.parseCatalogText(source, "第1章\n旧书正文。\n".toByteArray())
                .copy(activeRevision = "a".repeat(64), remoteLatestVersion = "remote-1")
            val pending = OnlineSyncJob(old.id, old.activeRevision!!, "remote-2", "running", "update-task")
            BookDatabase(context, name).use { db ->
                assertEquals("旧书正文。", db.load("local-old").single().chapters.single().body)
                assertEquals("local-old", db.linkLegacy("local-old", old).legacyBookId)
                assertTrue(runCatching { db.linkLegacy("local-old", old) }.isFailure)
                db.recordSyncJob(pending)
            }
            BookDatabase(context, name).use { db ->
                val live = db.load(old.id).single()
                assertEquals("update-task", db.syncJob(live, "remote-2")?.taskId)
                val next = live.copy(activeRevision = "b".repeat(64), remoteLatestVersion = "remote-2",
                    chapters = live.chapters + Chapter("new", "第2章", "新正文。"))
                assertTrue(runCatching { db.save(next, "stale", pending) }.isFailure)
                // Force an exception *after* deleting the active chapters inside save.
                val invalid = next.copy(chapters = listOf(next.chapters.first(), next.chapters.first()))
                assertTrue(runCatching { db.save(invalid, old.activeRevision, pending) }.isFailure)
                assertEquals(live, db.load(old.id).single())
                assertEquals("running", db.syncJob(live, "remote-2")?.state)
                db.save(next, old.activeRevision, pending)
                assertEquals("completed", db.syncJob(live, "remote-2")?.state)
            }
            BookDatabase(context, name).use { db ->
                assertEquals(2, db.load(old.id).single().chapters.size)
                val restored = db.rollback(old.id)!!
                assertEquals(old.chapters.map{it.copy(body="")}, restored.chapters)
                assertEquals(old.activeRevision, restored.activeRevision)
                assertEquals(7L, restored.remoteBookId)
                assertEquals("rolled_back", db.onlineStatus(old.id).state)
                db.markOnlineStatus(old.id, OnlineSyncStatus("failed"))
                assertTrue(db.rollbackHeld(old.id))
                assertEquals("旧书正文。", db.load("local-old").single().chapters.single().body)
                assertEquals(null, db.rollback(old.id))
            }
        } finally { context.deleteDatabase(name) }
    }
}
