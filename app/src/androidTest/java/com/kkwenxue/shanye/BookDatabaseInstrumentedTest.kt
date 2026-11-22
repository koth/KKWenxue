package com.kkwenxue.shanye

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkwenxue.shanye.data.BookDatabase
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookDatabaseInstrumentedTest {
    @Test fun freshDatabaseStartsWithAnEmptyShelf() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name="fresh-shelf-${java.util.UUID.randomUUID()}.sqlite"
        try { BookDatabase(context,name).use { db ->
            assertTrue("A fresh database must not contain bundled books", db.load().isEmpty())
        } } finally {context.deleteDatabase(name)}
    }
}
