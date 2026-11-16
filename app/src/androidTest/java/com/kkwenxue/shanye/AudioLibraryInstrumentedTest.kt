package com.kkwenxue.shanye

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkwenxue.shanye.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File

@RunWith(AndroidJUnit4::class)
class AudioLibraryInstrumentedTest {
    // Isolate the test DB/files under the target UID; instrumentation cannot write the test APK's UID.
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        private val testRoot=File(baseContext.filesDir,"audio-tests").apply {mkdirs()}
        override fun getFilesDir()=File(testRoot,"files").apply {mkdirs()}
        override fun getCacheDir()=File(testRoot,"cache").apply {mkdirs()}
        override fun getDatabasePath(name:String)=File(testRoot,name)
        override fun deleteDatabase(name:String)=SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?)=
            SQLiteDatabase.openDatabase(getDatabasePath(name).path,factory,SQLiteDatabase.CREATE_IF_NECESSARY)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,handler:DatabaseErrorHandler?)=
            SQLiteDatabase.openDatabase(getDatabasePath(name).path,factory,SQLiteDatabase.CREATE_IF_NECESSARY,handler)
        override fun getSharedPreferences(name:String,mode:Int)=baseContext.getSharedPreferences("audio-tests:$name",mode)
    }
    private val book = Book("audio-test", "测试", "", "", "", "", 0,
        listOf(Chapter("audio-test-1", "第一章", "风🌊很轻。\n\n雨停了！")))
    private val id = "a".repeat(32)

    private fun wave(): ByteArray {
        val samples=24000*3
        val buffer=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(24000).putInt(48000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples*2)
        repeat(samples) { buffer.putShort((kotlin.math.sin(it*2*Math.PI*220/24000)*1000).toInt().toShort()) }
        return buffer.array()
    }
    private fun manifest(artifact: String=id): JSONObject {
        val text=book.chapters[0].text
        val hash=AudioLibrary.sha256(text.toByteArray())
        return JSONObject().put("schema_version",2).put("complete",true)
            .put("offsets","UTF-16 into Android Chapter.text").put("artifact_id",artifact)
            .put("book_id",book.id).put("chapter_id",book.chapters[0].id).put("duration_ms",3000)
            .put("source_sha256",hash).put("source_revision",AudioLibrary.sha256("${book.chapters[0].id}:$hash:reader-canonical+OpenCC-t2s-v1:reader-sentence-bounded160-v1".toByteArray()))
            .put("audio_sha256",AudioLibrary.sha256(wave())).put("profile",JSONObject().put("speaker","Uncle_Fu"))
            .put("cues",JSONArray().put(JSONObject().put("start_ms",0).put("end_ms",3000)
                .put("start_utf16",0).put("end_utf16",text.length).put("original_text",text)))
    }
    private fun pack(vararg manifests: JSONObject, corrupt: Boolean=false): ByteArrayInputStream {
        val bytes=ByteArrayOutputStream()
        val entries=JSONArray()
        ZipOutputStream(bytes).use { zip ->
            for (manifest in manifests) {
                val artifact=manifest.getString("artifact_id")
                val json=manifest.toString().toByteArray()
                val audio=wave().also { if(corrupt) it[100]=(it[100]+1).toByte() }
                fun write(name:String, data:ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
                write("$artifact.json",json); write("$artifact.m4a",audio)
                entries.put(JSONObject().put("artifact_id",artifact).put("chapter_id",book.chapters[0].id)
                    .put("audio","$artifact.m4a").put("manifest","$artifact.json")
                    .put("audio_sha256",manifest.getString("audio_sha256")).put("manifest_sha256",AudioLibrary.sha256(json)))
            }
            zip.putNextEntry(ZipEntry("catalogue.json"))
            zip.write(JSONObject().put("schema_version",1).put("entries",entries).toString().toByteArray())
            zip.closeEntry()
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }
    private fun clean() {
        context.deleteDatabase("shanye_audio.db")
        java.io.File(context.filesDir,"audiobooks").deleteRecursively()
        context.getSharedPreferences("shanye_audio",0).edit().clear().commit()
    }
    @Test fun importValidatesUnicodeAnchorsAndPersistsVersionsAndIndependentPositions() {
        clean()
        AudioLibrary(context).use { library ->
            assertEquals(1,library.importBundle(pack(manifest()),listOf(book)))
            assertEquals(0,library.importBundle(pack(manifest()),listOf(book)))
            val first=library.load(listOf(book)).single()
            assertEquals(book.chapters[0].text,first.cues.single().text)
            assertEquals(book.chapters[0].text.length,first.cues.single().end)
            assertNotNull(first.cueAt(2999)); assertNull(first.cueAt(3000))
            first.file.writeBytes(byteArrayOf(1,2,3))
            assertEquals(1,library.importBundle(pack(manifest()),listOf(book)))
            assertTrue(first.file.length()>1000)
            library.savePosition(first,1234)
            assertEquals(1,library.importBundle(pack(manifest("b".repeat(32))),listOf(book)))
            val items=library.load(listOf(book))
            assertEquals(2,items.size)
            assertEquals(1234,library.position(items.first { it.id==id }))
            assertEquals(0,library.position(items.first { it.id!=id }))
            library.select(first)
        }
        AudioLibrary(context).use { library ->
            assertEquals(id,library.preferred(library.load(listOf(book)),book.chapters[0].id)?.id)
            assertEquals(1234,library.position(library.load(listOf(book)).first { it.id==id }))
        }
        clean()
    }

    @Test fun offsetAtTracksLongCueAcrossPageBoundaries() {
        val item = AudioChapter(
            "c".repeat(32), book.id, book.chapters[0].id, 0, book.chapters[0].title,
            "测试音色", 1000,
            listOf(AudioCue(0, 1000, 0, 100, book.chapters[0].text)),
            File(context.filesDir, "unused.m4a")
        )
        assertEquals(0, item.offsetAt(0))
        assertEquals(50, item.offsetAt(500))
        assertEquals(100, item.offsetAt(1000))
    }
    @Test fun corruptAudioAndMismatchedTextNeverBecomePlayable() {
        clean()
        AudioLibrary(context).use { library ->
            assertTrue(runCatching {library.importBundle(pack(manifest(),corrupt=true),listOf(book))}.isFailure)
            val changed=book.copy(chapters=listOf(book.chapters[0].copy(body="另一个版本。")))
            assertTrue(runCatching {library.importBundle(pack(manifest()),listOf(changed))}.isFailure)
            assertTrue(library.load(listOf(book)).isEmpty())
        }
        clean()
    }
    @Test fun invalidSecondChapterRollsBackWholePackageAndPreviewIsRejected() {
        clean()
        AudioLibrary(context).use { library ->
            val bad=manifest("b".repeat(32)).put("complete",false)
            assertTrue(runCatching {library.importBundle(pack(manifest(),bad),listOf(book))}.isFailure)
            assertTrue(library.load(listOf(book)).isEmpty())
            assertTrue(java.io.File(context.filesDir,"audiobooks").listFiles().orEmpty().isEmpty())
            val wrong=manifest().also {it.getJSONArray("cues").getJSONObject(0).put("original_text","错误原文")}
            assertTrue(runCatching {library.importBundle(pack(wrong),listOf(book))}.isFailure)
            assertTrue(library.load(listOf(book)).isEmpty())
        }
        clean()
    }
}
