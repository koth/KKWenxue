package com.kkwenxue.shanye

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkwenxue.shanye.data.BookDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookDatabaseInstrumentedTest {
    @Test fun seededNovelIsStoredAsOrderedChapters() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val book = BookDatabase(context).load().firstOrNull { it.title == "噩夢使徒" }
        assertNotNull("The bundled SQLite seed should contain the imported novel", book)
        requireNotNull(book)
        assertEquals("佚名", book.author)
        assertEquals(1281, book.chapters.size)
        assertEquals("第1章：大師級劇本", book.chapters.first().title)
        assertEquals("第1281章 抉擇", book.chapters.last().title)
        assertTrue(book.chapters.first().body.contains("還是不行嗎"))
        assertTrue(book.chapters.last().body.isNotBlank())
    }
}
