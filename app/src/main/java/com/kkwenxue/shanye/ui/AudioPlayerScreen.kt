package com.kkwenxue.shanye.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kkwenxue.shanye.audio.AudioPlayback
import com.kkwenxue.shanye.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

fun audioTime(ms: Int): String = "%02d:%02d".format(ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)
private fun speedLabel(speed: Float) = String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.') + "×"
private enum class PlayerChooser { SPEED, TIMER, CHAPTERS, VOICES }
private val PlayerWhite = Color(0xFFF7EEEA)
private val PlayerMuted = PlayerWhite.copy(alpha=.62f)
private val PlayerAccent = Color(0xFFFFC1AA)
private val PlayerPanel = Color(0xFF382529)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioPlayerScreen(
    book: Book?, library: AudioLibrary, items: List<AudioChapter>,
    onImported: () -> Unit, onClose: (ListeningCheckpoint?) -> Unit,
    initialChapterIndex: Int? = null, initialTextOffset: Int = 0,
    resumeArtifactId: String? = null,
    onRead: (AudioChapter, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val visible = remember(items, book?.id) {
        items.filter { book == null || it.bookId == book.id }
            .sortedWith(compareBy<AudioChapter> { it.bookId }.thenBy { it.chapterIndex })
    }
    val active = AudioPlayback.current?.takeIf { book == null || it.bookId == book.id }
    val chapterEntry = book != null && initialChapterIndex != null
    val requestedChapter = book?.chapters?.getOrNull(initialChapterIndex ?: -1)
    var chosenId by remember(book?.id, requestedChapter?.id) { mutableStateOf<String?>(null) }
    var followsPlayback by remember(book?.id, requestedChapter?.id) {
        mutableStateOf(!chapterEntry || active?.chapterId == requestedChapter?.id)
    }
    // After entering/starting this session, follow service-owned automatic chapter changes.
    LaunchedEffect(active?.id) {
        if (followsPlayback && active != null) chosenId = active.id
    }
    val initial = requestedChapter?.let { chapter ->
        active?.takeIf { it.chapterId == chapter.id && visible.any { candidate -> candidate.id == it.id } }
            ?: library.preferred(visible, chapter.id)
    }
    // An explicit reader entry must never fall back to a different chapter's session or audio.
    val displayItem = visible.firstOrNull { it.id == chosenId }
        ?: if (chapterEntry) initial else active ?: library.lastPlayed(visible)
            ?: visible.firstOrNull()?.let { library.preferred(visible, it.chapterId) }
    val displayBook = book ?: displayItem?.let { item -> Library.books.firstOrNull { it.id == item.bookId } }
    val isActive = displayItem != null && displayItem.id == active?.id
    val isPlaying = isActive && AudioPlayback.playing
    val preparing = isActive && AudioPlayback.preparing
    var pendingPosition by remember(displayItem?.id, isActive) {
        mutableIntStateOf(displayItem?.let {
            if (chapterEntry && !followsPlayback && it.id != resumeArtifactId) it.positionFor(initialTextOffset)
            else library.position(it)
        } ?: 0)
    }
    var dragging by remember(displayItem?.id) { mutableStateOf<Float?>(null) }
    val position = dragging?.toInt() ?: if (isActive) AudioPlayback.position else pendingPosition
    val cue = displayItem?.cueAt(position) ?: displayItem?.cues?.lastOrNull { it.startMs <= position }
    val chapters = visible.filter { it.bookId == displayItem?.bookId }.groupBy { it.chapterId }.values.map { versions ->
        versions.firstOrNull { it.id == displayItem?.id } ?: library.preferred(versions, versions.first().chapterId)!!
    }
    val chapterIndex = chapters.indexOfFirst { it.chapterId == displayItem?.chapterId }
    val previous = chapters.getOrNull(chapterIndex - 1)
    val next = chapters.getOrNull(chapterIndex + 1)
    var chooser by remember { mutableStateOf<PlayerChooser?>(null) }
    var customMinutes by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var showSynthesis by remember { mutableStateOf(false) }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try {
                val count = withContext(Dispatchers.IO) {
                    library.importBundle(context.contentResolver.openInputStream(uri) ?: error("无法读取听书包"), Library.books.toList())
                }
                onImported()
                message = if (count == 0) "这些音频已经在本机了" else "已导入 $count 个章节音频版本"
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { message = error.message ?: "听书包导入失败" }
            finally { importing = false }
        }
    }
    fun importAudio() = importFile.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
    fun start(item: AudioChapter, offset: Int = library.position(item)) {
        followsPlayback = true
        chosenId = item.id
        library.select(item)
        AudioPlayback.play(context, item, if (offset >= item.durationMs) 0 else offset)
    }
    fun seek(target: Int) {
        val item = displayItem ?: return
        val offset = target.coerceIn(0, item.durationMs)
        if (isActive) AudioPlayback.command(context, "seek", offset)
        else { pendingPosition = offset; library.savePosition(item, offset) }
    }
    fun closePlayer() {
        // An explicit entry into an unplayed chapter is only browsing. Don't
        // send the reader back to a different chapter's old listening position.
        if (!followsPlayback) { onClose(null); return }
        val live = AudioPlayback.current?.takeIf { book == null || it.bookId == book.id }
        if (live != null) AudioPlayback.checkpointNow()
        onClose(library.latestPlaybackCheckpoint(book?.id))
    }
    fun readDisplayed(item: AudioChapter) {
        val live = AudioPlayback.current?.id == item.id
        if (live) AudioPlayback.checkpointNow()
        val time = dragging?.toInt() ?: if (live) AudioPlayback.position else position
        onRead(item, item.offsetAt(time) ?: 0)
    }
    if (showSynthesis) {
        SynthesisScreen(displayBook ?: book, displayItem?.chapterIndex ?: initialChapterIndex,
            library, onImported, { showSynthesis=false })
        return
    }
    LaunchedEffect(Unit) { AudioPlayback.restore(context) }
    LaunchedEffect(book?.id, requestedChapter?.id) {
        if (chapterEntry && AudioPlayback.current?.let { it.bookId != book?.id || it.chapterId != requestedChapter?.id } == true
            && (AudioPlayback.playing || AudioPlayback.preparing)) {
            AudioPlayback.command(context, "pause")
        }
    }
    LaunchedEffect(message) { if (message.isNotEmpty()) { delay(5000); message = "" } }
    OverlaySystemBars(SystemBarsMode.PLAYER)
    BackHandler { if (chooser != null) chooser = null else closePlayer() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(
        listOf(Color(0xFF2A1B1E), Color(0xFF49302F), Color(0xFF291A1E))
    )).windowInsetsPadding(WindowInsets.systemBars)) {
        val artworkArea = (maxHeight - 440.dp).coerceAtLeast(248.dp)
        val artworkHeight = (artworkArea - 82.dp).coerceIn(144.dp, 260.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp)) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment=Alignment.CenterVertically) {
                PlayerIcon("collapse", "收起播放器", onClick=::closePlayer)
                Spacer(Modifier.weight(1f))
                TextButton(enabled=!importing, onClick={showSynthesis=true}) {
                    Text("合成与导入", fontSize=12.sp, color=PlayerAccent)
                }
                TextButton(enabled=!importing, onClick=::importAudio) {
                    Text(if (importing) "导入中…" else "导入听书包", fontSize=12.sp, color=PlayerMuted)
                }
            }
            Column(Modifier.fillMaxWidth().padding(top=14.dp, bottom=4.dp)) {
                Text(displayItem?.title ?: requestedChapter?.title ?: "听书", fontSize=23.sp, fontWeight=FontWeight.Medium,
                    color=PlayerWhite, maxLines=2, overflow=TextOverflow.Ellipsis)
                Text(displayBook?.title ?: "本地有声书", Modifier.padding(top=7.dp), fontSize=14.sp, color=PlayerMuted)
            }
            if (displayItem != null && displayBook != null) {
                Column(Modifier.fillMaxWidth().height(artworkArea), horizontalAlignment=Alignment.CenterHorizontally) {
                    Spacer(Modifier.weight(1f))
                    BookCover(displayBook, Modifier.width(artworkHeight * .735f).height(artworkHeight)
                        .shadow(22.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)))
                    Spacer(Modifier.weight(1f))
                    Text(cue?.text?.trim() ?: "准备播放…", Modifier.fillMaxWidth().heightIn(min=54.dp)
                        .padding(horizontal=6.dp).clickable { readDisplayed(displayItem) },
                        maxLines=2, overflow=TextOverflow.Ellipsis, fontFamily=Serif, fontSize=18.sp,
                        lineHeight=28.sp, color=PlayerWhite.copy(.88f), textAlign=TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                }
                Row(Modifier.fillMaxWidth().height(68.dp), horizontalArrangement=Arrangement.SpaceBetween,
                    verticalAlignment=Alignment.CenterVertically) {
                    PlayerAction("timer", timerLabel(), AudioPlayback.timerMode != 0) { chooser=PlayerChooser.TIMER }
                    PlayerAction("speed", "语速 ${speedLabel(AudioPlayback.speed)}") { chooser=PlayerChooser.SPEED }
                    PlayerAction("list", "目录 · ${chapters.size}") { chooser=PlayerChooser.CHAPTERS }
                    PlayerAction("download", "导入", enabled=!importing, onClick=::importAudio)
                }
                Slider(
                    value=position.toFloat().coerceIn(0f, displayItem.durationMs.toFloat()),
                    onValueChange={ dragging=it },
                    onValueChangeFinished={ dragging?.let { seek(it.toInt()) }; dragging=null },
                    valueRange=0f..displayItem.durationMs.toFloat(), enabled=!preparing,
                    modifier=Modifier.fillMaxWidth().height(32.dp).semantics { contentDescription="播放进度" },
                    colors=SliderDefaults.colors(thumbColor=PlayerWhite, activeTrackColor=PlayerWhite,
                        inactiveTrackColor=PlayerWhite.copy(.22f)),
                    thumb={ Box(Modifier.size(12.dp).background(PlayerWhite, CircleShape)) },
                    track={ state -> SliderDefaults.Track(state, modifier=Modifier.height(3.dp),
                        colors=SliderDefaults.colors(activeTrackColor=PlayerWhite, inactiveTrackColor=PlayerWhite.copy(.22f)),
                        thumbTrackGapSize=0.dp, drawStopIndicator=null) }
                )
                Row(Modifier.fillMaxWidth().padding(horizontal=2.dp), horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(audioTime(position), fontSize=11.sp, color=PlayerMuted)
                    Text(audioTime(displayItem.durationMs), fontSize=11.sp, color=PlayerMuted)
                }
                Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.SpaceBetween) {
                    PlayerIcon("back15", "后退 15 秒", enabled=!preparing) { seek(position - 15000) }
                    PlayerIcon("previousChapter", "上一章", enabled=previous != null) { previous?.let { start(it, 0) } }
                    Box(Modifier.size(74.dp).border(1.5.dp, PlayerWhite.copy(.42f), CircleShape).clip(CircleShape)
                        .clickable(enabled=!preparing, role=Role.Button) {
                            if (isActive) AudioPlayback.command(context, if (isPlaying) "pause" else "resume")
                            else start(displayItem, position)
                        }.semantics { contentDescription=if (preparing) "正在加载音频" else if (isPlaying) "暂停听书" else "播放听书" },
                        contentAlignment=Alignment.Center) {
                        if (preparing) CircularProgressIndicator(Modifier.size(28.dp), color=PlayerWhite, strokeWidth=2.dp)
                        else Glyph(if (isPlaying) "playerPause" else "playerPlay", PlayerWhite, size=31.dp)
                    }
                    PlayerIcon("nextChapter", "下一章", enabled=next != null) { next?.let { start(it, 0) } }
                    PlayerIcon("next15", "前进 15 秒", enabled=!preparing) { seek(position + 15000) }
                }
                Row(Modifier.fillMaxWidth().padding(top=20.dp, bottom=18.dp), horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(13.dp)).background(PlayerWhite.copy(.08f))
                        .clickable { chooser=PlayerChooser.VOICES }.padding(horizontal=14.dp),
                        verticalAlignment=Alignment.CenterVertically) {
                        Text("朗读音色 · ${displayItem.voice}", Modifier.weight(1f), fontSize=13.sp,
                            color=PlayerWhite, maxLines=1, overflow=TextOverflow.Ellipsis)
                        Glyph("next", PlayerMuted, Modifier.padding(start=8.dp), size=14.dp)
                    }
                    Box(Modifier.height(52.dp).clip(RoundedCornerShape(13.dp)).background(PlayerWhite.copy(.08f))
                        .clickable { readDisplayed(displayItem) }.padding(horizontal=18.dp),
                        contentAlignment=Alignment.Center) {
                        Text("定位原文", fontSize=13.sp, color=PlayerWhite)
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().heightIn(min=440.dp), horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.Center) {
                    Glyph("audio", PlayerMuted, size=52.dp)
                    Text(if (chapterEntry) "本章暂无听书内容" else "还没有听书音频", Modifier.padding(top=22.dp), fontSize=23.sp, color=PlayerWhite)
                    Text(if (chapterEntry) "先合成当前章节，或导入包含本章的听书包。" else "派发章节到服务器合成，或导入已有听书包。", Modifier.padding(top=12.dp), fontSize=12.sp,
                        color=PlayerMuted, textAlign=TextAlign.Center)
                    Button(onClick={showSynthesis=true}, modifier=Modifier.padding(top=22.dp),
                        colors=ButtonDefaults.buttonColors(containerColor=PlayerAccent, contentColor=PlayerPanel)) {
                        Text(if (chapterEntry) "去合成本章" else "去合成 / 从服务器导入")
                    }
                    TextButton(onClick=::importAudio, enabled=!importing, modifier=Modifier.padding(top=22.dp)) {
                        Text(if (importing) "正在导入…" else "导入听书包", color=PlayerAccent)
                    }
                }
            }
        }
        val notice = AudioPlayback.error ?: message.takeIf { it.isNotEmpty() }
        if (notice != null) Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp),
            containerColor=PlayerPanel, contentColor=PlayerWhite) { Text(notice) }
    }

    if (chooser != null) {
        ModalBottomSheet(onDismissRequest={ chooser=null }, containerColor=PlayerPanel, contentColor=PlayerWhite,
            dragHandle={ BottomSheetDefaults.DragHandle(color=PlayerWhite.copy(.25f)) }) {
            Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).navigationBarsPadding().imePadding()) {
                Text(when (chooser) {
                    PlayerChooser.SPEED -> "播放倍速"
                    PlayerChooser.TIMER -> "定时关闭"
                    PlayerChooser.CHAPTERS -> "听书目录"
                    else -> "朗读音色"
                }, Modifier.padding(bottom=20.dp), fontSize=22.sp, color=PlayerWhite)
                when (chooser) {
                    PlayerChooser.SPEED -> {
                        AudioPlayback.supportedSpeeds.chunked(4).forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(bottom=12.dp), horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                row.forEach { value ->
                                    ChoiceChip(speedLabel(value), AudioPlayback.speed==value, Modifier.weight(1f)) {
                                        AudioPlayback.setSpeed(context, value); chooser=null
                                    }
                                }
                                repeat(4-row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        Text("即时调整播放速度，保持原音高；下次播放沿用此设置。", Modifier.padding(top=4.dp, bottom=24.dp),
                            fontSize=12.sp, color=PlayerMuted)
                    }
                    PlayerChooser.TIMER -> {
                        listOf(15, 30, 45, 60).chunked(2).forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(bottom=12.dp), horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                row.forEach { minutes -> ChoiceChip("$minutes 分钟", AudioPlayback.timerMode==minutes, Modifier.weight(1f)) {
                                    AudioPlayback.setSleepTimer(context, minutes); chooser=null
                                } }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            ChoiceChip("本章结束", AudioPlayback.timerMode==-1, Modifier.weight(1f)) {
                                AudioPlayback.setSleepTimer(context, -1); chooser=null
                            }
                            ChoiceChip("关闭定时", AudioPlayback.timerMode==0, Modifier.weight(1f)) {
                                AudioPlayback.setSleepTimer(context, 0); chooser=null
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top=18.dp, bottom=24.dp), verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(customMinutes, { customMinutes=it.filter(Char::isDigit).take(3) },
                                Modifier.weight(1f), label={ Text("自定义分钟 · 1–360") }, singleLine=true,
                                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
                                colors=OutlinedTextFieldDefaults.colors(focusedTextColor=PlayerWhite, unfocusedTextColor=PlayerWhite,
                                    focusedBorderColor=PlayerAccent, unfocusedBorderColor=PlayerMuted, focusedLabelColor=PlayerAccent,
                                    unfocusedLabelColor=PlayerMuted, cursorColor=PlayerAccent))
                            TextButton(enabled=(customMinutes.toIntOrNull() ?: 0) in 1..360, onClick={
                                AudioPlayback.setSleepTimer(context, customMinutes.toInt()); chooser=null
                            }) { Text("开始", color=if ((customMinutes.toIntOrNull() ?: 0) in 1..360) PlayerAccent else PlayerMuted) }
                        }
                    }
                    PlayerChooser.CHAPTERS, PlayerChooser.VOICES -> {
                        val versions = chooser == PlayerChooser.VOICES
                        val entries = if (versions) visible.filter { it.chapterId==displayItem?.chapterId } else chapters
                        val selectedIndex=entries.indexOfFirst { it.id==displayItem?.id }.coerceAtLeast(0)
                        val listState=rememberLazyListState(initialFirstVisibleItemIndex=selectedIndex)
                        if (versions) Text("仅显示本机已导入的当前章节音频版本", Modifier.padding(bottom=12.dp), fontSize=12.sp, color=PlayerMuted)
                        LazyColumn(Modifier.heightIn(max=440.dp).padding(bottom=16.dp), state=listState) {
                            items(entries, key={ it.id }) { item ->
                                val selected=item.id==displayItem?.id
                                Row(Modifier.fillMaxWidth().clickable {
                                    start(item, if (versions) item.positionFor(displayItem?.anchorAt(position) ?: 0) else library.position(item))
                                    chooser=null
                                }.padding(vertical=17.dp), verticalAlignment=Alignment.CenterVertically) {
                                    Glyph(if (selected) "audio" else "play", if (selected) PlayerAccent else PlayerMuted, size=20.dp)
                                    Column(Modifier.weight(1f).padding(start=14.dp)) {
                                        Text(if (versions) item.voice else item.title, fontSize=16.sp, color=if (selected) PlayerAccent else PlayerWhite)
                                        Text(if (versions) "${audioTime(item.durationMs)} · ${item.id.take(8)}" else "${item.voice} · ${audioTime(item.durationMs)}",
                                            Modifier.padding(top=5.dp), fontSize=11.sp, color=PlayerMuted)
                                    }
                                }
                                HorizontalDivider(color=PlayerWhite.copy(.08f))
                            }
                        }
                    }
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(48.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) PlayerAccent.copy(.18f) else PlayerWhite.copy(.06f))
        .border(1.dp, if (selected) PlayerAccent.copy(.55f) else Color.Transparent, RoundedCornerShape(12.dp))
        .clickable(role=Role.Button, onClick=onClick), contentAlignment=Alignment.Center) {
        Text(label, fontSize=14.sp, color=if (selected) PlayerAccent else PlayerWhite)
    }
}

@Composable
private fun PlayerIcon(name: String, label: String, enabled: Boolean=true, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled=enabled, role=Role.Button, onClick=onClick)
        .semantics { contentDescription=label }, contentAlignment=Alignment.Center) {
        Glyph(name, PlayerWhite.copy(if (enabled) 1f else .25f), size=26.dp)
        if (name=="back15" || name=="next15") Text("15", fontSize=9.sp, fontWeight=FontWeight.Medium,
            color=PlayerWhite.copy(if (enabled) 1f else .25f))
    }
}

@Composable
private fun PlayerAction(icon: String, label: String, selected: Boolean=false, enabled: Boolean=true, onClick: () -> Unit) {
    val tint=if (selected) PlayerAccent else PlayerWhite.copy(if (enabled) .85f else .3f)
    Column(Modifier.widthIn(min=60.dp).height(64.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled=enabled, role=Role.Button, onClick=onClick),
        horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
        Glyph(icon, tint, size=25.dp)
        Text(label, Modifier.padding(top=8.dp), fontSize=11.sp, color=tint, maxLines=1)
    }
}

private fun timerLabel(): String = when {
    AudioPlayback.timerMode == -1 -> "本章结束"
    AudioPlayback.timerRemainingMs > 0 -> audioTime(((AudioPlayback.timerRemainingMs + 999) / 1000 * 1000).toInt())
    else -> "定时"
}
