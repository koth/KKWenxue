package com.kkwenxue.shanye

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkwenxue.shanye.audio.AudioPlayback
import com.kkwenxue.shanye.data.AudioChapter
import com.kkwenxue.shanye.data.AudioCue
import com.kkwenxue.shanye.data.Library
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class AudioPlaybackInstrumentedTest {
    @Test fun speedPreservesPauseAndTimerPausesBackgroundPlayback() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun await(timeout: Long=10000, predicate: () -> Boolean) {
            val until=SystemClock.elapsedRealtime()+timeout
            while (!predicate() && SystemClock.elapsedRealtime()<until) SystemClock.sleep(100)
            assertTrue("Playback state did not arrive: ${AudioPlayback.error}", predicate())
        }
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        val duration=150000
        val samples=8000*duration/1000
        val bytes=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(samples*2)
            repeat(samples) { putShort((kotlin.math.sin(it*2*Math.PI*220/8000)*200).toInt().toShort()) }
        }.array()
        val file=File(context.cacheDir,"playback-controls.wav").apply { writeBytes(bytes) }
        val chapter=TestBooks.reader.chapters.first()
        val item=AudioChapter("c".repeat(32), TestBooks.reader.id, chapter.id, 0, chapter.title, "测试音频", duration,
            listOf(AudioCue(0,duration,0,chapter.text.length,chapter.text)),file)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(800)
        try {
            main { AudioPlayback.play(context,item,0) }
            await { AudioPlayback.playing && AudioPlayback.position>400 }
            main { AudioPlayback.setSpeed(context,2f) }
            val before=AudioPlayback.position
            await(5000) { AudioPlayback.playing && AudioPlayback.position>before+500 }
            main { AudioPlayback.command(context,"pause") }
            await { !AudioPlayback.playing }
            main { AudioPlayback.setSpeed(context,.75f) }
            val paused=AudioPlayback.position
            SystemClock.sleep(1000)
            assertFalse(AudioPlayback.playing)
            assertTrue("Changing speed must keep the MediaPlayer paused", kotlin.math.abs(AudioPlayback.position-paused)<100)
            assertEquals(.75f,context.getSharedPreferences("shanye_audio",0).getFloat("playback_speed",0f),0f)

            main { AudioPlayback.setSpeed(context,1f); AudioPlayback.command(context,"resume") }
            await { AudioPlayback.playing }
            val otherAudio=instrumentation.context.getSystemService(AudioManager::class.java)
            val competingFocus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener { }
                .build()
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, otherAudio.requestAudioFocus(competingFocus))
            val beforeOtherAudio=AudioPlayback.position
            SystemClock.sleep(1200)
            assertTrue("Another app's audio focus must not pause the novel", AudioPlayback.playing)
            assertTrue("Novel playback should keep advancing while another audio source is active", AudioPlayback.position>beforeOtherAudio+500)
            otherAudio.abandonAudioFocusRequest(competingFocus)

            main { AudioPlayback.setSleepTimer(context,15) }
            await { AudioPlayback.timerRemainingMs in 899000L..900000L }
            main { AudioPlayback.setSleepTimer(context,0) }
            await { AudioPlayback.timerRemainingMs==0L && AudioPlayback.timerMode==0 }
            main {
                AudioPlayback.setSpeed(context,1f)
                AudioPlayback.setSleepTimer(context,1)
                AudioPlayback.command(context,"resume")
            }
            await { AudioPlayback.playing && AudioPlayback.timerRemainingMs>58000L }
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            shell("input keyevent KEYCODE_SLEEP")
            await(65000) { !AudioPlayback.playing && AudioPlayback.timerMode==0 && AudioPlayback.timerRemainingMs==0L }
            val expired=AudioPlayback.position
            assertTrue("Timer must pause before the chapter naturally ends", expired in 58000..70000)
            SystemClock.sleep(900)
            assertTrue(kotlin.math.abs(AudioPlayback.position-expired)<100)

            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            main {
                AudioPlayback.setSleepTimer(context,-1)
                AudioPlayback.command(context,"seek",duration-700)
                AudioPlayback.command(context,"resume")
            }
            await { AudioPlayback.position>=duration && !AudioPlayback.playing && AudioPlayback.timerMode==0 }
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            main {
                AudioPlayback.setSpeed(context,1f)
                AudioPlayback.setSleepTimer(context,0)
                AudioPlayback.command(context,"stop")
            }
            await { AudioPlayback.current==null }
            file.delete()
        }
    }
}
