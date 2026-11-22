package com.kkwenxue.shanye

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkwenxue.shanye.ui.BookCover
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BookCoverInstrumentedTest {
    @Test fun generatedCoverRendersWithStableEditorialArtwork() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        lateinit var view: ComposeView
        scenario.onActivity { activity ->
            view=ComposeView(activity).apply {
                setContent { MaterialTheme { Column(Modifier.padding(24.dp)) { BookCover(TestBooks.reader,Modifier.width(210.dp).height(300.dp)) } } }
                activity.setContentView(this)
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity { activity ->
            val bitmap=Bitmap.createBitmap(420,620,Bitmap.Config.ARGB_8888)
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(420,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(620,android.view.View.MeasureSpec.EXACTLY))
            view.layout(0,0,420,620)
            view.draw(android.graphics.Canvas(bitmap))
            File(context.filesDir,"book-cover.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        scenario.close()
    }
}
