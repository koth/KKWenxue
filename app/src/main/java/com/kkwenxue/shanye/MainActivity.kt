package com.kkwenxue.shanye

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.view.WindowCompat
import com.kkwenxue.shanye.data.*
import com.kkwenxue.shanye.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val systemBars by lazy { SystemBarsController(window) }
    private var audioOpenRequest by mutableIntStateOf(0)
    override fun onResume() {
        super.onResume()
        systemBars.restore()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) systemBars.restore()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_audio",false)) audioOpenRequest++
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("open_audio",false)) audioOpenRequest++
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val audioLibrary = remember { AudioLibrary(applicationContext) }
            var audioItems by remember { mutableStateOf<List<AudioChapter>>(emptyList()) }
            val scope = rememberCoroutineScope()
            fun refreshAudio() { scope.launch { audioItems = withContext(Dispatchers.IO) { audioLibrary.load(Library.books.toList()) } } }
            DisposableEffect(Unit) { onDispose { audioLibrary.close() } }
            var libraryReady by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                val imported = withContext(Dispatchers.IO) { LocalBookRepository(applicationContext).load() }
                Library.restoreImported(imported)
                audioItems = withContext(Dispatchers.IO) { audioLibrary.load(Library.books.toList()) }
                libraryReady = true
            }
            if (!libraryReady) {
                Column(Modifier.fillMaxSize().background(Paper), verticalArrangement=Arrangement.Center, horizontalAlignment=Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color=Accent, modifier=Modifier.size(24.dp))
                    Text("正在整理书架…", color=Muted, modifier=Modifier.padding(top=16.dp))
                }
                return@setContent
            }
            val store = remember { ReaderStore(applicationContext) }
            DisposableEffect(store) { onDispose(store.observeListeningProgress()) }
            LaunchedEffect(audioLibrary) {
                AudioLibrary.changes.collect {
                    audioItems = withContext(Dispatchers.IO) { audioLibrary.load(Library.books.toList()) }
                }
            }
            var readingId by rememberSaveable { mutableStateOf<String?>(null) }
            var readerSession by rememberSaveable { mutableIntStateOf(0) }
            var showAudio by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(audioOpenRequest) {
                if (audioOpenRequest>0) { refreshAudio(); showAudio=true }
            }
            val reading = readingId != null
            val systemBarsMode = when {
                showAudio -> SystemBarsMode.PLAYER
                reading && store.theme == 1 -> SystemBarsMode.READER_DARK
                reading -> SystemBarsMode.READER_LIGHT
                else -> SystemBarsMode.HOME
            }
            SideEffect {
                systemBars.setBase(systemBarsMode)
            }
            CompositionLocalProvider(LocalSystemBars provides systemBars) {
            MaterialTheme(colorScheme = lightColorScheme(primary=Accent,background=Paper,surface=Paper,onSurface=Ink,onBackground=Ink)) {
                BackHandler(showAudio) { showAudio=false }
                BackHandler(reading && !showAudio) { readingId = null }
                if (readingId != null) {
                    key(readingId,readerSession) { ReaderScreen(Library.book(readingId!!), store, audioLibrary, audioItems, ::refreshAudio) { readingId = null } }
                } else {
                    HomeScreen(store, onOpen = { book -> store.open(book); readingId = book.id }, onBookmark = { mark ->
                        val book = Library.book(mark.bookId)
                        store.savePosition(book, mark.chapter, mark.offset)
                        store.open(book)
                        readingId = book.id
                    }, onAudio={showAudio=true})
                }
                if (showAudio) {
                    AudioPlayerScreen(null, audioLibrary, audioItems, ::refreshAudio, {showAudio=false}, onRead={ item, offset ->
                        val book=Library.book(item.bookId)
                        store.savePosition(book,item.chapterIndex,offset)
                        store.open(book)
                        readingId=book.id
                        readerSession++
                        showAudio=false
                    })
                }
            }
            }
        }
    }
}
