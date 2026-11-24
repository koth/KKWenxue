package com.kkwenxue.shanye

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import kotlinx.coroutines.Job
import com.kkwenxue.shanye.tts.LocalTtsModelPack
import com.kkwenxue.shanye.tts.LocalTtsPackState
import com.kkwenxue.shanye.tts.LocalTtsRuntime
import com.kkwenxue.shanye.tts.LocalTtsSynthesisService

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val systemBars by lazy { SystemBarsController(window) }
    private var notificationPermissionRequested = false
    private var audioOpenRequest by mutableIntStateOf(0)
    private var resumeTick by mutableIntStateOf(0)
    private var syncTick by mutableIntStateOf(0)
    override fun onResume() {
        super.onResume()
        resumeTick++
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
        requestNotificationPermission()
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
            val localTtsPack = remember { LocalTtsModelPack(applicationContext) }
            val localTtsPackState by localTtsPack.states.collectAsState()
            var localTtsDownload by remember { mutableStateOf<Job?>(null) }
            fun startLocalTtsDownload() {
                localTtsDownload?.cancel()
                localTtsDownload = scope.launch(Dispatchers.IO) {
                    runCatching { localTtsPack.prepare() }
                }
            }
            val downloading = localTtsPackState is LocalTtsPackState.Downloading || localTtsPackState is LocalTtsPackState.Verifying
            val localTtsReady = localTtsPack.current() != null
            if (!localTtsReady && !downloading) {
                val failure = (localTtsPackState as? LocalTtsPackState.Failed)?.message
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("必须下载本地语音模型") },
                    text = { Text((failure?.let { "上次下载失败：$it\n\n" } ?: "") +
                        "首次下载约 1.1GB 模型，使用 Hugging Face 镜像。下载完成前无法进入书架。") },
                    confirmButton = { Button(onClick = ::startLocalTtsDownload) { Text(if (failure == null) "下载模型" else "重试下载") } }
                )
            }
            if (downloading) {
                val progress = (localTtsPackState as? LocalTtsPackState.Downloading)
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text(if (progress == null) "校验本地模型" else "下载本地模型") },
                    text = {
                        Column {
                            Text(progress?.file ?: "正在校验模型文件")
                            if (progress != null) {
                                val fraction = if (progress.total > 0) progress.received.toFloat() / progress.total else 0f
                                androidx.compose.material3.LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                                Text("${progress.received / (1024 * 1024)} / ${progress.total / (1024 * 1024)} MB", modifier = Modifier.padding(top = 8.dp))
                            } else CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp).size(22.dp))
                        }
                    },
                    confirmButton = {}
                )
            }
            if (!localTtsReady) {
                Column(Modifier.fillMaxSize().background(Paper), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(28.dp))
                    Text("准备本地语音合成…", color = Muted, modifier = Modifier.padding(top = 16.dp))
                }
                return@setContent
            }
            // Load the native model in the background while the user is on the
            // bookshelf. The first local synthesis should not block silently
            // on the 1GB+ model mmap/load step.
            val localTtsRuntime = remember { LocalTtsRuntime(applicationContext, localTtsPack) }
            LaunchedEffect(localTtsReady) {
                withContext(Dispatchers.IO) {
                    runCatching { localTtsRuntime.prepare() }
                }
            }
            LaunchedEffect(localTtsReady) {
                if (localTtsReady) LocalTtsSynthesisService.resumePending(applicationContext)
            }
            val store = remember { ReaderStore(applicationContext) }
            val onlineSync = remember { OnlineLibrarySync(applicationContext) }
            val syncSettings by onlineSync.settings.collectAsState()
            val onlineStatuses by OnlineLibrarySync.statuses.collectAsState()
            fun installUpdated(book: Book) {
                Library.addImported(book)
                refreshAudio()
            }
            var lastAutoCheck by remember { mutableLongStateOf(0L) }
            var lastManualRequest by remember { mutableIntStateOf(0) }
            LaunchedEffect(resumeTick, syncTick, syncSettings) {
                val now = android.os.SystemClock.elapsedRealtime()
                val manual = syncTick != lastManualRequest
                if (!manual && lastAutoCheck != 0L && now - lastAutoCheck < 2000) return@LaunchedEffect
                lastManualRequest = syncTick
                lastAutoCheck = now
                scope.launch { onlineSync.sync(Library.books.toList(), onUpdated = ::installUpdated) }
            }
            DisposableEffect(onlineSync) {
                onDispose(onlineSync.observeNetwork {
                    scope.launch {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (lastAutoCheck == 0L || now - lastAutoCheck >= 2000) {
                            lastAutoCheck = now
                            onlineSync.sync(Library.books.toList(), onUpdated = ::installUpdated)
                        }
                    }
                })
            }
            DisposableEffect(store) { onDispose(store.observeListeningProgress()) }
            LaunchedEffect(audioLibrary) {
                AudioLibrary.changes.collect {
                    audioItems = withContext(Dispatchers.IO) { audioLibrary.load(Library.books.toList()) }
                }
            }
            var readingId by rememberSaveable { mutableStateOf<String?>(null) }
            var readerSession by remember { mutableIntStateOf(0) }
            var readerStart by remember { mutableStateOf<ReadingPosition?>(null) }
            var showAudio by rememberSaveable { mutableStateOf(false) }
            fun showTextAt(book: Book, position: ReadingPosition) {
                readerStart = position
                readingId = book.id
                readerSession++
            }
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
                fun exitReader() { readerStart = null; readingId = null }
                BackHandler(reading && !showAudio) { exitReader() }
                if (readingId != null) {
                    key(readingId,readerSession) {
                        val readerBook = remember { Library.book(readingId!!) }
                        ReaderScreen(readerBook, store, audioLibrary, audioItems, ::refreshAudio,
                            startPosition=readerStart, onExit=::exitReader)
                    }
                } else {
                    HomeScreen(store, onOpen = { book -> store.open(book); readingId = book.id }, onBookmark = { mark ->
                        val book = Library.book(mark.bookId)
                        store.positionFor(book, mark)?.let {
                            store.savePosition(book, it.chapter, it.offset)
                            store.open(book)
                            showTextAt(book, it)
                        }
                    }, onAudio={showAudio=true}, onSync={ syncTick++ }, onlineStatuses=onlineStatuses,
                        syncSettings=syncSettings, onSyncSettings={ value ->
                            onlineSync.setSettings(value)
                            syncTick++
                        }, onCancelSync=onlineSync::cancel,
                        onRetrySync={ book, plan ->
                            scope.launch { onlineSync.sync(listOf(book), retry = true, confirmed = plan, onUpdated = ::installUpdated) }
                        }, onPostponeSync={ book, plan ->
                            scope.launch { onlineSync.postpone(book, plan) }
                        }, onRollback={ book ->
                            scope.launch {
                                try { onlineSync.rollback(book)?.let(::installUpdated) }
                                catch (error: IllegalStateException) {
                                    android.widget.Toast.makeText(applicationContext, error.message, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        })
                }
                if (showAudio) {
                    AudioPlayerScreen(null, audioLibrary, audioItems, ::refreshAudio, { checkpoint ->
                        // Closing the player keeps the screen/book underneath it.
                        if (checkpoint != null && readingId == checkpoint.anchor.bookId) {
                            val book = Library.book(checkpoint.anchor.bookId)
                            store.positionAfterListening(book, checkpoint)?.let { showTextAt(book, it) }
                        }
                        showAudio=false
                    }, onRead={ item, offset ->
                        val book = Library.book(item.bookId)
                        item.readingAnchor(offset).positionIn(book)?.let {
                            store.savePosition(book, it.chapter, it.offset)
                            store.open(book)
                            showTextAt(book, it)
                        }
                        showAudio=false
                    }, availableBooks=Library.books.toList())
                }
            }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || notificationPermissionRequested) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermissionRequested = true
        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_POST_NOTIFICATIONS
        )
    }

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 301
    }
}
