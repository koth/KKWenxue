package com.kkwenxue.shanye.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.kkwenxue.shanye.data.*
import com.kkwenxue.shanye.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private data class SynthesisVoice(val id: String, val name: String, val custom: Boolean = false)

private val NarratorVoices = listOf(
    SynthesisVoice("clone-linchuan-zonos3-v1", "林川 · 克隆音色", custom = true),
    SynthesisVoice("zonos3-haoran-v1", "Haoran · 中文男声")
)

// Keep the relay request's stable preset ID internal to the selection label.
private val SynthesisVoices = NarratorVoices
private val ExportSpeeds = listOf(0.8f, 0.9f, 1.0f, 1.1f, 1.2f)
private fun exportSpeedLabel(speed: Float) = "${String.format(java.util.Locale.ROOT, "%.1f", speed)} 倍速"

@Composable
fun SynthesisScreen(book: Book?, initialChapterIndex: Int?, audioLibrary: AudioLibrary, onImported: () -> Unit, onClose: () -> Unit,
                    availableBooks: List<Book> = emptyList()) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val repository = remember { SynthesisRepository(context) }
    val scope = rememberCoroutineScope()
    var connection by remember { mutableStateOf<RelayConnection?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val shelfBooks = availableBooks.ifEmpty { Library.books.toList() }
    var selectedBookId by rememberSaveable { mutableStateOf(book?.id ?: shelfBooks.firstOrNull()?.id.orEmpty()) }
    val activeBookId = selectedBookId.takeIf { id -> shelfBooks.any { it.id == id } }
        ?: book?.id?.takeIf { id -> shelfBooks.any { it.id == id } }
        ?: shelfBooks.firstOrNull()?.id.orEmpty()
    val selectedBook = shelfBooks.firstOrNull { it.id == activeBookId }
    if (selectedBook == null) {
        OverlaySystemBars(SystemBarsMode.SYNTHESIS)
        BackHandler(onBack = onClose)
        Box(Modifier.fillMaxSize().background(Paper).systemBarsPadding(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text("还没有可合成的书", fontFamily = Serif, fontSize = 22.sp, color = Ink)
                Text("请先从在线书库导入一本书。", Modifier.padding(top = 10.dp), color = Muted, fontSize = 13.sp)
                TextButton(onClick = onClose, modifier = Modifier.padding(top = 12.dp)) { Text("返回书架", color = Accent) }
            }
        }
        return
    }
    val startingIndex = (if (selectedBook.id == book?.id) initialChapterIndex else 0)?.coerceIn(selectedBook.chapters.indices) ?: 0
    var expression by rememberSaveable(selectedBookId) { mutableStateOf(
        SynthesisPayload.chapterNumber(selectedBook.chapters[startingIndex], startingIndex).toString()
    ) }
    var selectedIndices by remember(selectedBookId) { mutableStateOf<List<Int>>(emptyList()) }
    var parsedExpression by remember(selectedBookId) { mutableStateOf("") }
    var voiceId by rememberSaveable { mutableStateOf(NarratorVoices.first().id) }
    var exportSpeed by rememberSaveable { mutableFloatStateOf(1.0f) }
    var bookMenu by remember { mutableStateOf(false) }
    var voiceMenu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var taskBookMenu by remember { mutableStateOf(false) }
    var taskBookFilterId by rememberSaveable { mutableStateOf("") }
    var tasks by remember { mutableStateOf<List<SavedSynthesis>>(emptyList()) }
    var pending by remember { mutableStateOf<List<PendingSynthesis>>(emptyList()) }
    var cursor by remember { mutableStateOf<Long?>(null) }
    var loadedOlder by remember { mutableStateOf(false) }
    var lookupId by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var networkError by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    fun notify(message: String) { scope.launch { snackbar.showSnackbar(message) } }


    suspend fun loadSaved(config: RelayConnection) {
        val saved = withContext(Dispatchers.IO) { repository.tasks(config.endpoint) to repository.pending(config.endpoint) }
        tasks = saved.first; pending = saved.second
    }
    suspend fun refresh(config: RelayConnection, before: Long? = null) {
        refreshing = true
        try {
            val next = withContext(Dispatchers.IO) { repository.refresh(config, before) }
            if (!loadedOlder || before != null) cursor = next
            if (before != null) loadedOlder = true
            loadSaved(config)
            networkError = null
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { networkError = error.message ?: "连接失败，稍后可重试" }
        finally { refreshing = false }
    }
    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notify(error.message ?: "操作失败，请重试") }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        if (SynthesisVoices.none { it.id == voiceId }) voiceId = NarratorVoices.first().id
        try { connection = withContext(Dispatchers.IO) { repository.settings.load() } }
        catch (_: Exception) { networkError = "服务器设置无法读取，请重新填写接入凭据" }
        initialized = true
        settingsOpen = connection == null
    }
    LaunchedEffect(connection) {
        val config = connection ?: return@LaunchedEffect
        loadedOlder = false; cursor = null
        withContext(Dispatchers.IO) { SynthesisDownloads.resume(context) }
        loadSaved(config)
        while (true) {
            if (!busy && !refreshing && ((activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false)) refresh(config)
            delay(5000)
        }
    }
    LaunchedEffect(connection, repository) {
        val config = connection ?: return@LaunchedEffect
        SynthesisRepository.changes.collect { loadSaved(config) }
    }
    OverlaySystemBars(SystemBarsMode.SYNTHESIS)
    BackHandler { if (settingsOpen) settingsOpen = false else onClose() }

    Box(Modifier.fillMaxSize().background(Paper).systemBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal=12.dp), verticalAlignment=Alignment.CenterVertically) {
                IconAction("back", "返回听书", onClick=onClose)
                Text("合成与导入", Modifier.weight(1f), fontFamily=Serif, fontSize=22.sp, color=Ink)
                TextButton(enabled=!busy, onClick={ settingsOpen = true }) { Text("服务器", color=Accent) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=24.dp), horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                listOf("派发合成", "服务器任务").forEachIndexed { index, title ->
                    Column(Modifier.clickable { tab = index }.padding(top=12.dp), horizontalAlignment=Alignment.CenterHorizontally) {
                        Text(title, color=if (tab == index) Ink else Muted, fontWeight=if (tab == index) FontWeight.SemiBold else FontWeight.Normal, fontSize=16.sp)
                        Box(Modifier.padding(top=12.dp).width(28.dp).height(2.dp).background(if (tab == index) Accent else Paper))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (refreshing) CircularProgressIndicator(Modifier.padding(top=12.dp).size(16.dp), strokeWidth=2.dp, color=Accent)
            }
            HorizontalDivider(color=Rule)
            if (connection == null && initialized) {
                TextButton(onClick={settingsOpen=true}, modifier=Modifier.padding(horizontal=16.dp)) { Text("先连接任务服务器", color=Accent) }
            }
            if (tab == 0) {
                LazyColumn(Modifier.weight(1f), contentPadding=PaddingValues(24.dp), verticalArrangement=Arrangement.spacedBy(18.dp)) {
                    item {
                        Text("把下一段故事，变成声音。", fontFamily=Serif, fontSize=23.sp, color=Ink)
                        Text("只上传选中的章节。合成完成后自动下载并导入，退出此页仍会继续。", Modifier.padding(top=8.dp), fontSize=12.sp, lineHeight=20.sp, color=Muted)
                    }
                    item {
                        Text("选择书籍", fontSize=12.sp, color=Muted)
                        Box(Modifier.padding(top=8.dp)) {
                            OutlinedButton(onClick={bookMenu=true}, enabled=!busy, modifier=Modifier.fillMaxWidth(), shape=RoundedCornerShape(12.dp)) {
                                Text(selectedBook.title, Modifier.weight(1f).padding(vertical=8.dp), color=Ink)
                                Glyph("next", Muted, size=16.dp)
                            }
                            DropdownMenu(expanded=bookMenu, onDismissRequest={bookMenu=false}) {
                                shelfBooks.forEach { item -> DropdownMenuItem(text={Text(item.title)}, onClick={selectedBookId=item.id; bookMenu=false}) }
                            }
                        }
                    }
                    item {
                        OutlinedTextField(expression, { expression=it }, Modifier.fillMaxWidth(), enabled=!busy,
                            label={Text("章号 / 范围")}, placeholder={Text("984,986-988")}, singleLine=true,
                            shape=RoundedCornerShape(12.dp), keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii))
                        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                            Text("每批最多 100 章", Modifier.weight(1f), color=Muted, fontSize=11.sp)
                            TextButton(enabled=!busy, onClick={
                                runCatching { SynthesisPayload.resolve(selectedBook, expression) }.onSuccess {
                                    selectedIndices=it; parsedExpression=expression
                                }.onFailure { error -> scope.launch { snackbar.showSnackbar(error.message ?: "章号无效") } }
                            }) { Text("解析章节", color=Accent) }
                        }
                    }
                    if (selectedIndices.isNotEmpty() && expression == parsedExpression) {
                        item { Text("已匹配 ${selectedIndices.size} 章", fontWeight=FontWeight.Medium, fontSize=13.sp, color=Ink) }
                        items(selectedIndices, key={selectedBook.chapters[it].id}) { index ->
                            Row(Modifier.fillMaxWidth().background(Ink.copy(.035f), RoundedCornerShape(10.dp)).padding(12.dp), verticalAlignment=Alignment.CenterVertically) {
                                Glyph("check", Accent, size=17.dp)
                                Text(selectedBook.chapters[index].title, Modifier.padding(start=10.dp).weight(1f), fontSize=13.sp, color=Ink)
                                Text("${selectedBook.chapters[index].text.length} 字", fontSize=10.sp, color=Muted)
                            }
                        }
                    }
                    item {
                        Text("朗读音色", color=Muted, fontSize=12.sp)
                        Box(Modifier.padding(top=8.dp)) {
                            OutlinedButton(onClick={voiceMenu=true}, enabled=!busy, modifier=Modifier.fillMaxWidth(), shape=RoundedCornerShape(12.dp)) {
                                Text(SynthesisVoices.firstOrNull { it.id == voiceId }?.name ?: "请选择音色", Modifier.weight(1f).padding(vertical=8.dp), color=Ink, maxLines=1, overflow=TextOverflow.Ellipsis)
                                Glyph("next", Muted, size=16.dp)
                            }
                            DropdownMenu(expanded=voiceMenu, onDismissRequest={voiceMenu=false}) {
                                DropdownMenuItem(onClick={}, enabled=false, text={Text("ZONOS3 音色", color=Muted, fontSize=11.sp)})
                                SynthesisVoices.forEach { voice -> DropdownMenuItem(text={Text(voice.name)}, onClick={voiceId=voice.id; voiceMenu=false}) }
                            }
                        }
                        Text("成品语速", Modifier.padding(top=16.dp), color=Muted, fontSize=12.sp)
                        Box(Modifier.padding(top=8.dp)) {
                            OutlinedButton(onClick={speedMenu=true}, enabled=!busy, modifier=Modifier.fillMaxWidth(), shape=RoundedCornerShape(12.dp)) {
                                Text(exportSpeedLabel(exportSpeed), Modifier.weight(1f).padding(vertical=8.dp), color=Ink)
                                Glyph("next", Muted, size=16.dp)
                            }
                            DropdownMenu(expanded=speedMenu, onDismissRequest={speedMenu=false}) {
                                ExportSpeeds.forEach { value ->
                                    DropdownMenuItem(text={Text(exportSpeedLabel(value))}, onClick={exportSpeed=value; speedMenu=false})
                                }
                            }
                        }
                        Text("自然停顿 · 统一音量", Modifier.padding(top=10.dp), color=Muted, fontSize=11.sp)
                    }
                    item {
                        Button(enabled=!busy && connection != null && expression.isNotBlank() && SynthesisVoices.any { it.id == voiceId },
                            modifier=Modifier.fillMaxWidth().height(52.dp), shape=RoundedCornerShape(14.dp), onClick={
                                val config=connection ?: return@Button
                                runAction {
                                    val indices=SynthesisPayload.resolve(selectedBook, expression)
                                    selectedIndices=indices; parsedExpression=expression
                                    val selected=SynthesisVoices.first { it.id == voiceId }
                                    val key=withContext(Dispatchers.IO) {
                                        repository.prepare(config, selectedBook, indices, selected.id, selected.name, exportSpeed).also {
                                            SynthesisDownloads.enqueue(context,it,5)
                                        }
                                    }
                                    tab=1
                                    try {
                                        withContext(Dispatchers.IO) { repository.publish(config, key) }
                                    } finally { loadSaved(config) }
                                    notify("已派发 ${indices.size} 章，完成后将自动下载并导入")
                                }
                            }) {
                            if (busy) CircularProgressIndicator(Modifier.size(18.dp).padding(end=5.dp), strokeWidth=2.dp, color=Paper)
                            Text(if (busy) "正在派发…" else "派发合成任务")
                        }
                    }
                }
            } else {
                // The selector is based on the local shelf, so a book with no
                // synthesis yet is still available as a filter and shows an
                // honest empty state instead of disappearing from the menu.
                val localTaskBooks = shelfBooks
                val activeTaskBookFilter = taskBookFilterId.takeIf { id -> localTaskBooks.any { it.id == id } }.orEmpty()
                val visiblePending = pending.filter { item ->
                    item.bookId.isNotBlank() && localTaskBooks.any { it.id == item.bookId } &&
                        (activeTaskBookFilter.isBlank() || item.bookId == activeTaskBookFilter)
                }
                val visibleTasks = tasks.filter { saved ->
                    localTaskBooks.any { it.id == saved.task.bookId } &&
                        (activeTaskBookFilter.isBlank() || saved.task.bookId == activeTaskBookFilter)
                }
                LazyColumn(Modifier.weight(1f), contentPadding=PaddingValues(24.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("服务器任务", fontFamily=Serif, fontSize=22.sp, color=Ink)
                                Text("优先局域网下载，无法直连时才申请中转", Modifier.padding(top=5.dp), fontSize=11.sp, color=Muted)
                            }
                            TextButton(enabled=!busy && !refreshing && connection != null, onClick={connection?.let {scope.launch {refresh(it)}}}) { Text("刷新", color=Accent) }
                        }
                        networkError?.let { Text(it, Modifier.padding(top=12.dp), color=Accent, fontSize=12.sp, lineHeight=19.sp) }
                        if (localTaskBooks.isNotEmpty()) {
                            Text("按书籍筛选", Modifier.padding(top=18.dp), fontSize=12.sp, color=Muted)
                            Box(Modifier.padding(top=8.dp)) {
                                OutlinedButton(onClick={ taskBookMenu = true }, enabled=!busy, modifier=Modifier.fillMaxWidth(), shape=RoundedCornerShape(12.dp)) {
                                    Text(localTaskBooks.firstOrNull { it.id == activeTaskBookFilter }?.title ?: "全部本地书籍", Modifier.weight(1f).padding(vertical=8.dp), color=Ink, maxLines=1, overflow=TextOverflow.Ellipsis)
                                    Glyph("next", Muted, size=16.dp)
                                }
                                DropdownMenu(expanded=taskBookMenu, onDismissRequest={taskBookMenu=false}) {
                                    DropdownMenuItem(text={Text("全部本地书籍")}, onClick={taskBookFilterId=""; taskBookMenu=false})
                                    localTaskBooks.forEach { localBook ->
                                        DropdownMenuItem(text={Text(localBook.title, maxLines=1, overflow=TextOverflow.Ellipsis)}, onClick={taskBookFilterId=localBook.id; taskBookMenu=false})
                                    }
                                }
                            }
                        }
                    }
                    localTaskBooks.filter { localBook ->
                        visiblePending.any { it.bookId == localBook.id } || visibleTasks.any { it.task.bookId == localBook.id }
                    }.forEach { localBook ->
                        item(key="task-book:${localBook.id}") {
                            Text(localBook.title, Modifier.padding(top=4.dp), fontFamily=Serif, fontSize=19.sp, color=Ink)
                        }
                        val bookPending = visiblePending.filter { it.bookId == localBook.id }
                        items(bookPending, key={"pending:${it.key}"}) { item ->
                            Column(Modifier.fillMaxWidth().border(1.dp, Rule, RoundedCornerShape(14.dp)).padding(16.dp)) {
                                Text(item.title, fontSize=15.sp, color=Ink)
                                Text("派发状态待确认", Modifier.padding(top=8.dp), fontSize=12.sp, color=Accent)
                                Text("使用原任务核对，不会因重试重复创建。", Modifier.padding(top=6.dp), fontSize=11.sp, color=Muted)
                                item.error?.let { Text(it, Modifier.padding(top=6.dp), fontSize=11.sp, color=Muted, maxLines=2) }
                                TextButton(enabled=!busy, onClick={connection?.let {config -> runAction {
                                    try { withContext(Dispatchers.IO) { repository.publish(config, item.key) } }
                                    finally { loadSaved(config) }
                                }}}) {Text("核对派发", color=Accent)}
                            }
                        }
                        val bookTasks = visibleTasks.filter { it.task.bookId == localBook.id }
                        items(bookTasks, key={it.task.id}) { saved ->
                        val task=saved.task
                        val delivery=saved.delivery
                        val downloading=delivery?.state == "running" && task.state == "completed"
                        val expired=task.result?.expiresAt?.let { it <= System.currentTimeMillis()/1000 } ?: true
                        val hasLocalBook = task.bookId.isBlank() || Library.books.any { it.id == task.bookId }
                        val label=when {
                            saved.importedAt>0 -> "已导入"
                            delivery?.state == "blocked" -> "需要处理"
                            delivery?.state == "retry" -> "自动重试中"
                            downloading -> "自动导入中"
                            task.state == "completed" && expired -> "结果已过期"
                            else -> taskState(task.state)
                        }
                        Column(Modifier.fillMaxWidth().background(Ink.copy(.025f), RoundedCornerShape(14.dp)).border(1.dp, Rule, RoundedCornerShape(14.dp)).padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                                Text(task.title, Modifier.weight(1f), fontSize=15.sp, color=Ink, fontWeight=FontWeight.Medium, maxLines=2, overflow=TextOverflow.Ellipsis)
                                Text(label, Modifier.padding(start=12.dp), fontSize=11.sp, color=if(saved.importedAt>0) ColorGreen else Accent)
                            }
                            if (task.voice.isNotBlank()) Text(task.voice, Modifier.padding(top=8.dp), fontSize=11.sp, color=Muted)
                            if (delivery != null && saved.importedAt == 0L) {
                                Text(delivery.phase,Modifier.padding(top=9.dp),fontSize=12.sp,color=Accent)
                                if (delivery.total > 0 && task.state == "completed") {
                                    Text("${delivery.received * 100 / delivery.total}%",Modifier.padding(top=6.dp),fontSize=11.sp,color=Muted)
                                    LinearProgressIndicator(progress={(delivery.received.toFloat()/delivery.total).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().padding(top=6.dp),color=Accent,trackColor=Rule)
                                }
                            }
                            if (task.state == "completed" && !expired && saved.importedAt == 0L) Text(when(task.result?.relayState) {
                                "queued" -> "合成已完成 · 等待按需上传中转包"
                                "uploading" -> "合成已完成 · 正在上传中转包"
                                "ready" -> "合成已完成 · 中转包可用，仍优先直连"
                                "failed" -> "合成已完成 · 中转上传失败，可重试导入"
                                else -> "合成已完成 · 文件保留在合成服务器，尚未上传中转"
                            }, Modifier.padding(top=8.dp), fontSize=11.sp, color=Muted)
                            Text("${task.id.take(8)} · ${if (task.createdAt>0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(task.createdAt*1000)) else "服务器任务"}", Modifier.padding(top=7.dp), fontSize=10.sp, color=Muted)
                            if (task.state in setOf("running", "pausing", "paused", "cancelling")) {
                                val counts=task.progress.optJSONObject("counts")
                                val total=counts?.optInt("total") ?: 0
                                val completed=(counts?.optInt("completed") ?: 0) + (counts?.optInt("skipped") ?: 0)
                                Text((when {
                                    task.state == "paused" -> "已暂停"
                                    task.state == "pausing" -> "正在暂停"
                                    task.state == "cancelling" -> "正在取消"
                                    task.progress.optString("phase") == "bundle" -> "正在打包听书包"
                                    task.progress.optString("phase") == "upload" -> "正在上传结果"
                                    task.progress.optString("phase") == "synthesis" ->
                                        if (task.progress.optBoolean("retrying")) "重试失败片段" else "合成进度"
                                    else -> "等待 worker 处理"
                                }) + if (total>0) " · $completed / $total 段" else "", Modifier.padding(top=12.dp), fontSize=12.sp, color=Muted)
                                if (total>0) LinearProgressIndicator(progress={completed.toFloat()/total}, modifier=Modifier.fillMaxWidth().padding(top=10.dp).height(3.dp), color=Accent, trackColor=Rule)
                            }
                            (delivery?.error ?: saved.error ?: task.error)?.let { Text(it, Modifier.padding(top=9.dp), color=Accent, fontSize=11.sp, maxLines=3) }
                            if (task.state in setOf("queued", "running", "paused", "pausing", "cancelling")) {
                                Row(
                                    Modifier.fillMaxWidth().padding(top=8.dp),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    when (task.state) {
                                        "queued", "running" -> {
                                            TextButton(enabled=!busy, onClick={connection?.let {config -> runAction {
                                                withContext(Dispatchers.IO) { repository.pauseTask(config, task.id) }
                                                loadSaved(config)
                                                notify("已暂停，已合成的片段会保留；点击“继续合成”可从断点接着处理")
                                            }}}) { Text("暂停", color=Accent) }
                                        }
                                        "paused" -> {
                                            TextButton(enabled=!busy, onClick={connection?.let {config -> runAction {
                                                withContext(Dispatchers.IO) { repository.resumeTask(config, task.id) }
                                                loadSaved(config)
                                                notify("已继续合成，已完成的片段不会重复生成")
                                            }}}) { Text("继续合成", color=Accent) }
                                        }
                                        "pausing" -> Text("正在暂停…", Modifier.padding(horizontal=12.dp), fontSize=12.sp, color=Muted)
                                        "cancelling" -> Text("正在取消…", Modifier.padding(horizontal=12.dp), fontSize=12.sp, color=Muted)
                                    }
                                    if (task.state != "pausing" && task.state != "cancelling") {
                                        TextButton(enabled=!busy, onClick={connection?.let {config -> runAction {
                                            withContext(Dispatchers.IO) { repository.cancelTask(config, task.id) }
                                            loadSaved(config)
                                            notify("已取消合成任务")
                                        }}}) { Text("取消", color=Muted) }
                                    }
                                }
                            }
                            if (task.state == "failed") {
                                TextButton(enabled=!busy,onClick={connection?.let {config -> runAction {
                                    val key=withContext(Dispatchers.IO) {
                                        repository.prepareRetry(config,task.id).also {SynthesisDownloads.enqueue(context,it,5)}
                                    }
                                    try {withContext(Dispatchers.IO) {repository.publish(config,key)}}
                                    finally {loadSaved(config)}
                                    notify("已重试失败片段，成功音频会复用；完成后自动导入")
                                }}}) {Text("重试失败片段",color=Accent)}
                            }
                            if (task.state == "completed" && saved.importedAt == 0L && !expired && (delivery == null || delivery.state == "blocked")) {
                                TextButton(enabled=!busy && hasLocalBook, onClick={connection?.let {config -> runAction {
                                    withContext(Dispatchers.IO) { SynthesisDownloads.retry(context,config,task.id) }
                                    loadSaved(config)
                                    notify("已加入后台下载，完成后会自动导入")
                                }}}) { Glyph("download", if(hasLocalBook) Accent else Muted, size=16.dp); Text(if(hasLocalBook) (if(delivery==null) "下载并导入" else "重试自动导入") else "请先导入对应小说", Modifier.padding(start=6.dp), color=if(hasLocalBook) Accent else Muted) }
                            }
                        }
                        }
                    }
                    if (visibleTasks.isEmpty() && visiblePending.isEmpty()) item { Text(if (localTaskBooks.isEmpty()) "本地书架中还没有可显示的合成任务。" else "当前筛选下还没有合成任务。", Modifier.padding(vertical=24.dp), color=Muted, fontSize=13.sp, lineHeight=22.sp) }
                    if (cursor != null) item {
                        OutlinedButton(enabled=!busy && !refreshing, onClick={connection?.let {config -> scope.launch {refresh(config,cursor)}}}, modifier=Modifier.fillMaxWidth()) {Text("加载更早的任务", color=Accent)}
                    }
                    item {
                        HorizontalDivider(color=Rule)
                        Text("通过任务 ID 查找", Modifier.padding(top=18.dp, bottom=9.dp), fontSize=13.sp, color=Ink)
                        OutlinedTextField(lookupId, {lookupId=it}, Modifier.fillMaxWidth(), enabled=!busy, singleLine=true,
                            placeholder={Text("粘贴服务器任务 ID", fontSize=12.sp)}, shape=RoundedCornerShape(12.dp))
                        TextButton(enabled=!busy && lookupId.isNotBlank() && connection != null, onClick={connection?.let {config -> runAction {
                            withContext(Dispatchers.IO) { repository.lookup(config, lookupId.trim()) }
                            loadSaved(config); lookupId=""
                        }}}) {Text("查找任务", color=Accent)}
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
    if (settingsOpen) RelaySettingsDialog(connection, repository, onDismiss={settingsOpen=false}, onSaved={
        connection=it; settingsOpen=false; tasks=emptyList(); pending=emptyList(); networkError=null
        val config=it
        scope.launch { withContext(Dispatchers.IO) { repository.resumeCredentials(config.endpoint);SynthesisDownloads.resume(context) } }
    })
}

private val ColorGreen = androidx.compose.ui.graphics.Color(0xFF647C58)
private fun taskState(state: String) = when(state) {
    "queued" -> "排队中"; "running" -> "处理中"; "pausing" -> "暂停中"; "paused" -> "已暂停"
    "cancelling" -> "取消中"; "cancelled" -> "已取消"; "completed" -> "已完成"; "failed" -> "失败"; else -> state
}

@Composable
private fun RelaySettingsDialog(current: RelayConnection?, repository: SynthesisRepository, onDismiss: () -> Unit, onSaved: (RelayConnection) -> Unit) {
    var endpoint by remember { mutableStateOf(current?.endpoint ?: "http://120.48.49.190:17870") }
    var token by remember { mutableStateOf(current?.token ?: "") }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    val importConnection=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            testing=true; error=null
            scope.launch {
                try {
                    val config=withContext(Dispatchers.IO) {
                        val raw=context.contentResolver.openInputStream(uri)?.use { input ->
                            org.json.JSONObject(String(BookImporter.readLimited(input, 65536), Charsets.UTF_8))
                        } ?: error("无法读取连接配置")
                        val config=RelayConnection(raw.getString("endpoint"),raw.getString("token")).validated()
                        SynthesisRelay(config).health()
                        repository.settings.save(config)
                        config
                    }
                    onSaved(config)
                } catch (cancelled: CancellationException) {throw cancelled
                } catch (failure: Exception) {error=failure.message ?: "连接配置导入失败"}
                finally {testing=false}
            }
        }
    }
    AlertDialog(onDismissRequest={if(!testing) onDismiss()}, title={Text("任务服务器", fontFamily=Serif)},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("连接通用任务中转服务", fontSize=12.sp, color=Muted)
            OutlinedTextField(endpoint, {endpoint=it}, Modifier.fillMaxWidth(), enabled=!testing, label={Text("服务器地址")}, singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
            OutlinedTextField(token, {token=it}, Modifier.fillMaxWidth(), enabled=!testing, label={Text("接入凭据")}, singleLine=true,
                visualTransformation=PasswordVisualTransformation(), keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
            Text("凭据仅保存在本机。IP 联调使用 HTTP，域名部署可改用 HTTPS。", fontSize=11.sp, lineHeight=18.sp, color=Muted)
            Text("山页 ${BuildConfig.VERSION_NAME}",fontSize=10.sp,color=Muted)
            TextButton(enabled=!testing, onClick={importConnection.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))}) {
                Text("导入连接配置文件", color=Accent)
            }
            error?.let {Text(it, color=Accent, fontSize=12.sp)}
        }},
        confirmButton={TextButton(enabled=!testing, onClick={
            testing=true; error=null
            scope.launch {
                try {
                    val config=RelayConnection(endpoint.trim(),token.trim()).validated()
                    withContext(Dispatchers.IO) { SynthesisRelay(config).health(); repository.settings.save(config) }
                    onSaved(config)
                } catch (cancelled: CancellationException) {throw cancelled
                } catch (failure: Exception) {error=failure.message ?: "无法连接服务器"}
                finally {testing=false}
            }
        }) {Text(if(testing) "连接中…" else "连接并保存", color=Accent)}},
        dismissButton={TextButton(enabled=!testing, onClick=onDismiss) {Text("稍后", color=Muted)}}, containerColor=Paper)
}
