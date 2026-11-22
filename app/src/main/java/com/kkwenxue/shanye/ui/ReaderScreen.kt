package com.kkwenxue.shanye.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.kkwenxue.shanye.data.*
import com.kkwenxue.shanye.reader.*
import com.kkwenxue.shanye.audio.AudioPlayback
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(initialBook:Book,store:ReaderStore,audioLibrary:AudioLibrary,audioItems:List<AudioChapter>,onAudioImported:()->Unit,
                 startPosition:ReadingPosition?=null,onExit:()->Unit) {
    val context=LocalContext.current
    var book by remember { mutableStateOf(initialBook) }
    var loadingChapter by remember { mutableStateOf(false) }
    var chapterError by remember { mutableStateOf<String?>(null) }
    var chapterRetry by remember { mutableIntStateOf(0) }
    var relocated by remember { mutableStateOf(false) }
    var pendingResume by remember { mutableStateOf(startPosition==null) }
    var pendingBookmark by remember { mutableStateOf<RevisionAnchor?>(null) }
    var needsAnchorRelocation by remember { mutableStateOf(false) }
    val initial=remember { startPosition ?: store.resumePosition(book) }
    var chapter by remember { mutableIntStateOf(initial.chapter) }
    var jumpOffset by remember { mutableIntStateOf(initial.offset) }
    if (needsAnchorRelocation || (startPosition == null && !relocated && store.needsRelocation(book))) {
        AlertDialog(onDismissRequest=onExit, title={ Text("原文已变更，请重新定位") },
            text={
                LazyColumn(Modifier.heightIn(max=360.dp)) {
                    itemsIndexed(book.chapters) { index, source ->
                        Text(source.title, Modifier.fillMaxWidth().clickable {
                            store.savePosition(book, index, 0)
                            relocated = true
                            pendingResume=false;pendingBookmark=null;needsAnchorRelocation=false
                            chapter=index;jumpOffset=0
                        }.padding(vertical=10.dp))
                    }
                }
            },
            confirmButton={ TextButton(onClick=onExit) { Text("返回书架") } })
        return
    }
    var info by remember { mutableStateOf(PageInfo()) }
    var controls by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf("") }
    var cacheRangeDialog by remember { mutableStateOf(false) }
    var cacheRangeStart by remember { mutableStateOf("") }
    var cacheRangeEnd by remember { mutableStateOf("") }
    var failedCacheJob by remember { mutableStateOf<String?>(null) }
    var selectedSentence by remember { mutableStateOf<SentenceRange?>(null) }
    val contentsState = rememberLazyListState()
    var script by remember(book.id) { mutableStateOf(store.scriptFor(book)) }
    var followAudio by remember { mutableStateOf(true) }
    val inPlayer=sheet=="audio" || sheet=="activeAudio"
    val activeAudio=AudioPlayback.current?.takeIf { it.bookId==book.id }
    val audioChapterIndex=remember(book,activeAudio?.chapterId) { book.chapters.indexOfFirst { it.id==activeAudio?.chapterId } }
    val audioCue=activeAudio?.cueAt(AudioPlayback.position)
    val audioOffset=activeAudio?.offsetAt(AudioPlayback.position)
    val availableChapters=remember(audioItems,book.id) { audioItems.filter { it.bookId==book.id }.map { it.chapterId }.toSet() }
    val colors=readerColors(store.theme)
    val reader=remember { ReadingView(context) }
    val scope=rememberCoroutineScope()
    val cacheTick by ChapterQueue.changes.collectAsState()
    var cacheJobs by remember { mutableStateOf<List<CacheJob>>(emptyList()) }
    val exportInfos by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(OnlineFullBookExportWorker.name(book)).collectAsState(initial=emptyList())
    val exportInfo=exportInfos.firstOrNull{!it.state.isFinished} ?: exportInfos.firstOrNull()
    val exportFile=exportInfo?.outputData?.getString("path") ?: OnlineFullBookExportWorker.savedPath(context,book)
    val saveExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri!=null && exportFile!=null)scope.launch {
            try {
                withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use { out -> java.io.File(exportFile).inputStream().use { it.copyTo(out) } } ?: error("无法保存文件")}
                toast="整本导出已保存"
            } catch(error: Exception){toast=error.message ?: "导出失败"}
        }
    }
    LaunchedEffect(cacheTick, book.id) {
        cacheJobs=withContext(Dispatchers.IO){ChapterQueue(context).use{it.jobs(book.id)}}
    }

    LaunchedEffect(book.id, chapter, chapterRetry) {
        val selected = book.chapters.getOrNull(chapter)
        if (book.remoteBookId == null || selected == null || selected.body.isNotBlank()) {loadingChapter=false;chapterError=null;return@LaunchedEffect}
        loadingChapter = true
        chapterError = null
        try {
            val loaded = OnlineChapterStore(context).load(book, chapter)
            if(pendingResume) {
                if(store.needsRelocation(loaded))needsAnchorRelocation=true
                else jumpOffset=store.resumePosition(loaded).offset
                pendingResume=false
            }
            pendingBookmark?.let { anchor ->
                val position=anchor.locate(loaded)
                if(position==null)needsAnchorRelocation=true else jumpOffset=position.offset
                pendingBookmark=null
            }
            book = loaded.copy(chapters=loaded.chapters.mapIndexed { i, ref -> if(kotlin.math.abs(i-chapter)<=2)ref else ref.copy(body="") })
            withContext(Dispatchers.IO){OnlineChapterPrefetchWorker.enqueue(context, loaded, chapter)}
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            chapterError = error.message ?: "章节加载失败"
        }
        loadingChapter = false
    }
    val existingBookmark=store.bookmarks.firstOrNull {
        it.bookId==book.id && store.positionFor(book, it)?.let { p -> p.chapter==chapter && p.offset in info.start until info.end } == true
    }
    val bookmark=existingBookmark ?: Bookmark(book.id,chapter,info.start,book.chapters[chapter].text.substring(info.start.coerceIn(0, book.chapters[chapter].text.length)).trim().replace("\n"," ").take(80),
        RevisionAnchor.capture(book, chapter, info.start))
    val bookmarked=existingBookmark!=null
    val selection = selectedSentence
    val selectedComment = selection?.let { store.commentFor(book, chapter, it) }
    LaunchedEffect(Unit) { delay(4200); hint=false }
    LaunchedEffect(toast) { if(toast.isNotEmpty()) { delay(1800); toast="" } }
    LaunchedEffect(audioChapterIndex,audioOffset,followAudio,AudioPlayback.playing,inPlayer) {
        if(!inPlayer && followAudio && AudioPlayback.playing && audioChapterIndex>=0 && audioOffset!=null) {
            chapter=audioChapterIndex
            jumpOffset=audioOffset
            // Keep the Android reader view in sync immediately. Waiting for the
            // next recomposition leaves the spoken sentence highlighted off-page.
            reader.followAudioTo(audioChapterIndex, audioOffset)
        }
    }
    BackHandler(!inPlayer && (sheet!=null || controls)) { if(sheet!=null) sheet=null else controls=false }
    fun moveTo(position:ReadingPosition,fromAudio:Boolean,saveAsReading:Boolean,anchor:RevisionAnchor?=null) {
        pendingResume=false;pendingBookmark=anchor?.takeIf{book.chapters[position.chapter].body.isBlank()}
        followAudio=fromAudio; chapter=position.chapter; jumpOffset=position.offset; sheet=null; controls=false
        selectedSentence=null
        reader.clearSelection()
        reader.followAudioTo(position.chapter,position.offset)
        if(saveAsReading && pendingBookmark==null) store.savePosition(book,position.chapter,position.offset)
    }
    fun jump(index:Int,offset:Int=0) = moveTo(ReadingPosition(index,offset),fromAudio=false,saveAsReading=true)
    LaunchedEffect(sheet, chapter) {
        if (sheet == "contents" && book.chapters.isNotEmpty()) {
            contentsState.scrollToItem(chapter.coerceIn(book.chapters.indices))
        }
    }
    if (inPlayer) {
        val entryChapter = remember(sheet) { chapter.takeIf { sheet=="audio" } }
        val entryPage = remember { info }
        val resumeArtifact = remember(entryChapter) {
            entryChapter?.let { index ->
                store.listeningPosition(book)?.takeIf {
                    val anchor=it.anchor
                    anchor.chapterId == book.chapters[index].id && anchor.textOffset >= entryPage.start &&
                        (anchor.textOffset < entryPage.end || anchor.textOffset == entryPage.end && entryPage.index == entryPage.count - 1)
                }?.artifactId
            }
        }
        AudioPlayerScreen(book,audioLibrary,audioItems,onAudioImported,{ checkpoint ->
            val position=checkpoint?.let { store.positionAfterListening(book,it) }
            if(position==null) sheet=null else moveTo(position,fromAudio=true,saveAsReading=false)
        },
            initialChapterIndex=entryChapter,initialTextOffset=entryPage.start,resumeArtifactId=resumeArtifact,onRead={item,offset ->
            item.readingAnchor(offset).positionIn(book)?.let { moveTo(it,fromAudio=true,saveAsReading=true) }
        })
        return
    }

    Box(Modifier.fillMaxSize().background(colors.paper)) {
        AndroidView(factory={reader},modifier=Modifier.fillMaxSize(),update={ view ->
            view.onPosition={ page ->
                chapter=page.chapter
                info=page
            }
            view.onToggleMenu={ controls=!controls; hint=false }
            view.onPageTurn={ store.countPage() }
            view.onPageTurned={ page -> store.savePosition(book,page.chapter,page.start) }
            view.onTurnStarted={controls=false; hint=false; followAudio=false}
            view.onChapterRequested={ index, offset -> pendingResume=false;pendingBookmark=null;chapter=index; jumpOffset=offset }
            view.onBoundary={message ->
                if (message == "这本故事读完了" && chapter + 1 < book.chapters.size) {
                    chapter += 1
                    jumpOffset = 0
                } else toast=message
            }
            view.onSentenceSelected={ sentence ->
                selectedSentence=sentence
                if (sentence != null) { controls=false; hint=false }
            }
            view.configure(book,chapter,jumpOffset,store.fontSize,store.spacious,store.serif,colors.ink.toArgb(),colors.muted.toArgb(),colors.paper.toArgb(),store.pageTurnStyle,script)
            view.setAudioCue(audioChapterIndex,audioCue?.start ?: 0,audioCue?.end ?: 0)
        })
        if (loadingChapter || chapterError != null) {
            Box(Modifier.fillMaxSize().background(colors.paper.copy(alpha=.94f)), contentAlignment=Alignment.Center) {
                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(book.chapters[chapter].title,color=colors.ink,fontSize=18.sp)
                    if (loadingChapter) CircularProgressIndicator(Modifier.padding(top=16.dp),color=Accent)
                    Text(chapterError ?: "正在加载本章…", Modifier.padding(top=14.dp), color=colors.muted, fontSize=13.sp)
                    chapterError?.let {
                        TextButton(onClick={ chapterError = null; chapterRetry++ }) { Text("重试") }
                        TextButton(onClick={sheet="contents"}){Text("查看目录")}
                    }
                }
            }
        }
        // Remove the actions immediately when the selection clears; an exit animation
        // would keep them composed and potentially clickable without a selected sentence.
        if (selection != null && !controls) {
            SentenceActionBar(
                sentence=selection,
                comment=selectedComment,
                colors=colors,
                modifier=Modifier.align(Alignment.BottomCenter),
                onComment={sheet="comment"},
                onClear={selectedSentence=null; reader.clearSelection()}
            )
        }
        AnimatedVisibility(controls,Modifier.align(Alignment.TopCenter),enter=fadeIn(),exit=fadeOut()) {
            Row(Modifier.fillMaxWidth().background(colors.paper).padding(start=12.dp,end=12.dp,top=18.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
                IconAction("back","返回书房",colors.ink,onExit)
                Column(Modifier.weight(1f).padding(start=8.dp)) {
                    Text(book.title,fontSize=15.sp,fontFamily=Serif,fontWeight=FontWeight.Medium,color=colors.ink)
                    Text(book.author,Modifier.padding(top=5.dp),fontSize=10.sp,color=colors.muted)
                }
                IconAction(if(bookmarked) "bookmarked" else "bookmark",if(bookmarked) "移除书签" else "添加书签",if(bookmarked) Accent else colors.ink) {
                    store.toggleBookmark(bookmark)
                    if (book.remoteBookId != null) {
                        val selected=book.chapters[chapter].id
                        val hasBookmark=store.bookmarks.any{it.bookId==book.id && it.anchor?.chapterId==selected}
                        scope.launch(Dispatchers.IO) {
                            if (hasBookmark) OnlineChapterStore(context).protect(book.id,selected,2)
                            else OnlineChapterStore(context).unprotect(book.id,selected,2)
                        }
                    }
                    toast=if(bookmarked) "书签已移除" else "已收藏这一页"
                }
            }
        }
        AnimatedVisibility(controls,Modifier.align(Alignment.BottomCenter),enter=fadeIn(),exit=fadeOut()) {
            Column(Modifier.fillMaxWidth().background(colors.paper).padding(bottom=26.dp)) {
                HorizontalDivider(color=colors.muted.copy(.15f))
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=11.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconAction("back","上一页",colors.muted) { reader.turn(-1) }
                    Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
                        Text(book.chapters[chapter].title,fontSize=11.sp,color=colors.ink)
                        Text("本章 ${info.index+1} / ${info.count} 页",Modifier.padding(top=5.dp),fontSize=10.sp,color=colors.muted)
                    }
                    IconAction("next","下一页",colors.muted) { reader.turn(1) }
                }
                Row(Modifier.fillMaxWidth().padding(top=5.dp),horizontalArrangement=Arrangement.SpaceEvenly) {
                    ReaderTool("list","目录",colors) {sheet="contents"}
                    ReaderTool(if(store.theme==1) "sun" else "moon",if(store.theme==1) "日间" else "夜读",colors) {store.changeTheme(if(store.theme==1) 0 else 1)}
                    Column(Modifier.width(56.dp).clickable {sheet="settings"}.padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Text("Aa",fontSize=23.sp,fontFamily=FontFamily.Serif,color=colors.ink)
                        Text("排版",Modifier.padding(top=7.dp),fontSize=10.sp,color=colors.muted)
                    }
                    ReaderTool("bookmark","书签",colors) {sheet="bookmarks"}
                    ReaderTool("audio","听书",colors) {sheet="audio"}
                    ScriptTool(script,colors) {
                        script=script.opposite()
                        store.changeScript(book.id,script)
                        selectedSentence=null
                        reader.clearSelection()
                    }
                }
            }
        }
        if(activeAudio!=null && !controls && selectedSentence==null && sheet==null) {
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom=46.dp).background(colors.panel,RoundedCornerShape(24.dp)),verticalAlignment=Alignment.CenterVertically) {
                IconAction(if(AudioPlayback.playing) "pause" else "play",if(AudioPlayback.playing) "暂停听书" else "继续听书",Accent) {
                    AudioPlayback.command(context,if(AudioPlayback.playing) "pause" else "resume")
                }
                Text("听书 · ${audioTime(AudioPlayback.position)} / ${audioTime(activeAudio.durationMs)}",Modifier.clickable {sheet="activeAudio"}.padding(end=12.dp),fontSize=11.sp,color=colors.ink)
                Box(Modifier.width(1.dp).height(16.dp).background(colors.muted.copy(.25f)))
                IconAction("close","退出听书",colors.muted) {
                    followAudio=false
                    hint=false
                    AudioPlayback.command(context,"stop")
                }
            }
        }
        if(hint && !controls && activeAudio==null) {
            Text("轻点中央唤出菜单 · 长按一句发表评论",Modifier.align(Alignment.BottomCenter).padding(bottom=58.dp).background(colors.ink.copy(.88f),RoundedCornerShape(24.dp)).padding(horizontal=18.dp,vertical=11.dp),fontSize=11.sp,color=colors.paper)
        }
        if(toast.isNotEmpty()) {
            Text(toast,Modifier.align(Alignment.Center).background(colors.ink.copy(.9f),RoundedCornerShape(10.dp)).padding(20.dp),color=colors.paper,fontSize=13.sp)
        }
    }
    if(sheet!=null) {
        ModalBottomSheet(onDismissRequest={sheet=null},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=colors.panel,contentColor=colors.ink,dragHandle={Box(Modifier.padding(top=12.dp,bottom=7.dp).size(32.dp,3.dp).background(colors.muted.copy(.3f),CircleShape))}) {
            when(sheet) {
                "settings" -> ReaderSettings(store,colors)
                "comment" -> {
                    selection?.let { sentence ->
                        CommentEditor(
                            sentence=sentence,
                            existing=selectedComment,
                            colors=colors,
                            onSave={ body ->
                                store.saveComment(book,chapter,sentence,body)
                                sheet=null
                                toast="这句评论已保存"
                            }
                        )
                    }
                }
                "contents" -> {
                    SheetTitle("目录", "${book.title} · ${book.chapters.size} 章",colors)
                    if (book.remoteBookId != null) {
                        Row(Modifier.fillMaxWidth().padding(start=24.dp, end=24.dp, bottom=8.dp), verticalAlignment=Alignment.CenterVertically) {
                            Text("正文缓存", Modifier.weight(1f), fontSize=12.sp, color=colors.muted)
                            TextButton(onClick={ cacheRangeDialog=true }) { Text("缓存范围", color=Accent) }
                            TextButton(onClick={
                                scope.launch(Dispatchers.IO){ OnlineChapterPrefetchWorker.enqueueRange(context, book, book.chapters.indices.toList(), "full") }
                                toast="已加入后台缓存全书，不影响当前阅读"
                            }) { Text("缓存全书", color=Accent) }
                            TextButton(onClick={
                                scope.launch(Dispatchers.IO){OnlineChapterPrefetchWorker.cancel(context, book)}
                                toast="已暂停后台缓存"
                            }) { Text("暂停", color=colors.muted) }
                        }
                        cacheJobs.filter { it.scope != "adjacent" }.forEach { job ->
                            Column(Modifier.fillMaxWidth().padding(horizontal=24.dp)) {
                                Text("${if(job.scope=="full") "全书缓存" else "范围缓存"} · ${job.completed}/${job.total} 章${if(job.state=="paused") " · 已暂停" else ""}", fontSize=12.sp,color=colors.muted)
                                if(job.total>0)LinearProgressIndicator(progress={job.completed.toFloat()/job.total},modifier=Modifier.fillMaxWidth().padding(top=6.dp),color=Accent,trackColor=Rule)
                                if(job.failed.isNotEmpty())TextButton(onClick={failedCacheJob=job.id}){Text("查看 ${job.failed.size} 个失败章节",color=Accent)}
                                if(job.state=="paused" || job.failed.isNotEmpty()) TextButton(onClick={scope.launch(Dispatchers.IO){OnlineChapterPrefetchWorker.resume(context,book)}}){Text(if(job.failed.isNotEmpty())"重试失败章节" else "继续缓存")}
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp)) {
                            TextButton(onClick={OnlineFullBookExportWorker.enqueue(context,book)}){Text("准备整本导出",color=Accent)}
                            if(exportFile!=null)TextButton(onClick={saveExport.launch("${book.title}.zip")}){Text("保存 ZIP",color=Accent)}
                            if(exportInfo?.state in listOf(WorkInfo.State.RUNNING,WorkInfo.State.ENQUEUED))TextButton(onClick={OnlineFullBookExportWorker.cancel(context,book)}){Text("暂停导出")}
                        }
                        exportInfo?.let { task ->
                            Text(task.outputData.getString("error") ?: task.progress.getString("phase") ?: when(task.state){WorkInfo.State.SUCCEEDED->"导出包已就绪"; WorkInfo.State.CANCELLED->"导出已暂停，可继续准备"; else->""}, Modifier.padding(horizontal=24.dp),fontSize=11.sp,color=colors.muted)
                            val total=task.progress.getLong("total",0L)
                            if(total>0)LinearProgressIndicator(progress={task.progress.getLong("received",0L).toFloat()/total},modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=6.dp),color=Accent,trackColor=Rule)
                        }

                    }
                    LazyColumn(state=contentsState,modifier=Modifier.heightIn(max=410.dp).padding(bottom=24.dp)) {
                        itemsIndexed(book.chapters) { index,item ->
                            Row(Modifier.fillMaxWidth().clickable { jump(index) }.padding(horizontal=28.dp,vertical=22.dp),verticalAlignment=Alignment.CenterVertically) {
                                Text("%02d".format(index+1),fontSize=11.sp,color=if(index==chapter) Accent else colors.muted)
                                Text(item.title,Modifier.padding(start=20.dp).weight(1f),fontFamily=Serif,fontSize=17.sp,color=if(index==chapter) Accent else colors.ink)
                                if(item.id in availableChapters) Text("可听",Modifier.padding(start=8.dp),fontSize=10.sp,color=Accent)
                                if(index==chapter) Glyph("book",Accent,size=18.dp)
                            }
                        }
                    }
                }
                "bookmarks" -> {
                    SheetTitle("故事里的停留", "本书的所有书签",colors)
                    val marks=store.bookmarks.filter { it.bookId==book.id }
                    if(marks.isEmpty()) {
                        Text("还没有书签。点右上角，留住喜欢的那一页。",Modifier.padding(horizontal=28.dp,vertical=35.dp),fontSize=12.sp,color=colors.muted)
                    } else LazyColumn(Modifier.heightIn(max=420.dp).padding(horizontal=28.dp)) {
                        items(marks.size) { index ->
                            val mark=marks[index]
                            val position=store.positionFor(book,mark)
                            Column(Modifier.fillMaxWidth().clickable {position?.let{moveTo(it,false,true,mark.anchor)}}.padding(vertical=16.dp)) {
                                Text(mark.quote,fontFamily=Serif,fontSize=15.sp,lineHeight=25.sp,color=colors.ink,maxLines=3)
                                Text(position?.let{"第${it.chapter+1}章 · ${book.chapters[it.chapter].title}"} ?: "章节已移除，请重新定位",Modifier.padding(top=10.dp),fontSize=10.sp,color=Accent)
                                HorizontalDivider(Modifier.padding(top=18.dp),color=colors.muted.copy(.15f))
                            }
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }
    failedCacheJob?.let { id ->
        val failures=cacheJobs.firstOrNull{it.id==id}?.failed.orEmpty()
        AlertDialog(onDismissRequest={failedCacheJob=null},title={Text("缓存失败章节")},
            text={LazyColumn(Modifier.heightIn(max=360.dp)){itemsIndexed(failures){_,failure ->
                Text("${book.chapters.firstOrNull{it.id==failure.first}?.title ?: "已移除章节"}：${failure.second}",Modifier.padding(vertical=8.dp),fontSize=13.sp)
            }}},confirmButton={TextButton(onClick={scope.launch(Dispatchers.IO){OnlineChapterPrefetchWorker.resume(context,book)};failedCacheJob=null}){Text("重试失败章节")}},
            dismissButton={TextButton(onClick={failedCacheJob=null}){Text("关闭")}})
    }
    if (cacheRangeDialog) {
        AlertDialog(
            onDismissRequest={ cacheRangeDialog=false },
            title={ Text("缓存章节范围", fontFamily=Serif, color=colors.ink) },
            text={ Column {
                Text("按目录序号填写，例如 120–140。任务会在后台运行，不阻塞当前阅读。", fontSize=12.sp, color=colors.muted)
                Row(Modifier.padding(top=14.dp), horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(cacheRangeStart, { cacheRangeStart=it.filter(Char::isDigit) }, Modifier.weight(1f), singleLine=true, label={ Text("起始章") })
                    OutlinedTextField(cacheRangeEnd, { cacheRangeEnd=it.filter(Char::isDigit) }, Modifier.weight(1f), singleLine=true, label={ Text("结束章") })
                }
            } },
            dismissButton={ TextButton(onClick={ cacheRangeDialog=false }) { Text("取消", color=colors.muted) } },
            confirmButton={ TextButton(onClick={
                val start = cacheRangeStart.toIntOrNull()
                val end = cacheRangeEnd.toIntOrNull()
                if (start == null || end == null || start !in 1..book.chapters.size || end !in start..book.chapters.size) {
                    toast="请输入有效的目录序号范围"
                } else {
                    scope.launch(Dispatchers.IO){OnlineChapterPrefetchWorker.enqueueRange(context, book, (start - 1 until end).toList(), "range")}
                    cacheRangeDialog=false
                    toast="已加入第 $start–$end 章后台缓存"
                }
            }) { Text("开始缓存", color=Accent) } }
        )
    }
}

@Composable
private fun SentenceActionBar(
    sentence: SentenceRange,
    comment: SentenceComment?,
    colors: ReaderColors,
    modifier: Modifier,
    onComment: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,bottom=20.dp)
            .clip(RoundedCornerShape(16.dp)).background(colors.panel)
            .padding(start=18.dp,end=12.dp,top=14.dp,bottom=12.dp)
    ) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically) {
                Glyph("comment",Accent,size=18.dp)
                Text("已选中一句",Modifier.padding(start=8.dp),fontSize=11.sp,color=colors.muted)
            }
            IconAction("close","取消选择",colors.muted,onClear)
        }
        Text(sentence.cleanText,Modifier.padding(top=9.dp,end=4.dp),fontFamily=Serif,fontSize=15.sp,lineHeight=24.sp,color=colors.ink,maxLines=3)
        Row(Modifier.fillMaxWidth().padding(top=11.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(if (comment == null) "给这句话留一句话" else "已写评论 · 可继续修改",fontSize=10.sp,color=colors.muted)
            Spacer(Modifier.weight(1f))
            Text(if (comment == null) "评论" else "修改评论",Modifier.clip(RoundedCornerShape(8.dp)).background(Accent.copy(.12f)).clickable(onClick=onComment).padding(horizontal=14.dp,vertical=8.dp),fontSize=11.sp,color=Accent)
        }
    }
}

@Composable
private fun CommentEditor(
    sentence: SentenceRange,
    existing: SentenceComment?,
    colors: ReaderColors,
    onSave: (String) -> Unit
) {
    var body by remember(existing?.key) { mutableStateOf(existing?.body ?: "") }
    Column(Modifier.fillMaxWidth().padding(start=28.dp,end=28.dp,bottom=28.dp)) {
        Text(if (existing == null) "评论这一句" else "修改评论",fontFamily=Serif,fontSize=24.sp,color=colors.ink)
        Text("你的评论只会跟着这句原文保存。",Modifier.padding(top=7.dp),fontSize=11.sp,color=colors.muted)
        Text(sentence.cleanText,Modifier.padding(top=20.dp).clip(RoundedCornerShape(10.dp)).background(colors.muted.copy(.08f)).padding(15.dp),fontFamily=Serif,fontSize=15.sp,lineHeight=24.sp,color=colors.ink,maxLines=4)
        OutlinedTextField(
            value=body,
            onValueChange={if (it.length <= 300) body=it},
            modifier=Modifier.fillMaxWidth().padding(top=18.dp),
            minLines=3,
            maxLines=6,
            placeholder={Text("写下你读到这里时的想法…",fontSize=13.sp)},
            supportingText={Text("${body.length}/300",fontSize=10.sp)},
            shape=RoundedCornerShape(12.dp),
            colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Accent,unfocusedBorderColor=colors.muted.copy(.35f),focusedTextColor=colors.ink,unfocusedTextColor=colors.ink,cursorColor=Accent)
        )
        Button(onClick={if (body.trim().isNotEmpty()) onSave(body)},modifier=Modifier.fillMaxWidth().padding(top=12.dp),shape=RoundedCornerShape(10.dp),colors=ButtonDefaults.buttonColors(containerColor=Accent,contentColor=Color.White),enabled=body.trim().isNotEmpty()) {
            Text(if (existing == null) "保存评论" else "更新评论",fontSize=13.sp)
        }
    }
}

@Composable
private fun ReaderTool(icon:String,label:String,colors:ReaderColors,action:()->Unit) {
    Column(Modifier.width(56.dp).clickable(onClick=action).padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Glyph(icon,colors.ink,size=24.dp)
        Text(label,Modifier.padding(top=9.dp),fontSize=10.sp,color=colors.muted)
    }
}

@Composable
private fun ScriptTool(script: ChineseScript, colors: ReaderColors, action: () -> Unit) {
    val target = script.opposite()
    Column(
        Modifier.width(56.dp).clickable(onClick=action).padding(vertical=8.dp),
        horizontalAlignment=Alignment.CenterHorizontally
    ) {
        Text(if (target == ChineseScript.TRADITIONAL) "繁" else "简", fontFamily=Serif, fontSize=23.sp, color=colors.ink)
        Text(if (target == ChineseScript.TRADITIONAL) "繁体" else "简体", Modifier.padding(top=9.dp), fontSize=10.sp, color=colors.muted)
    }
}

@Composable
private fun SheetTitle(title:String,subtitle:String,colors:ReaderColors) {
    Column(Modifier.padding(start=28.dp,end=28.dp,top=12.dp,bottom=23.dp)) {
        Text(title,fontFamily=Serif,fontSize=23.sp,color=colors.ink)
        Text(subtitle,Modifier.padding(top=7.dp),fontSize=11.sp,color=colors.muted)
    }
}

@Composable
private fun ReaderSettings(store:ReaderStore,colors:ReaderColors) {
    Column(Modifier.fillMaxWidth().heightIn(max=680.dp).verticalScroll(rememberScrollState())) {
    SheetTitle("让阅读，刚刚好", "只需调整一次，记住你的偏好。",colors)
    Column(Modifier.fillMaxWidth().padding(horizontal=28.dp).padding(bottom=30.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            listOf("纸张","夜读","青苔").forEachIndexed { index,label ->
                val preview=readerColors(index)
                Column(Modifier.weight(1f).height(80.dp).border(if(store.theme==index) 1.5.dp else .7.dp,if(store.theme==index) Accent else colors.muted.copy(.25f),RoundedCornerShape(10.dp)).background(preview.paper,RoundedCornerShape(10.dp)).clickable {store.changeTheme(index)}.padding(vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("山页",fontFamily=Serif,fontSize=18.sp,color=preview.ink)
                    Text(label,Modifier.padding(top=8.dp),fontSize=10.sp,color=preview.muted)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top=22.dp,bottom=20.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("字号",fontSize=12.sp,color=colors.muted,modifier=Modifier.weight(1f))
            Box(Modifier.size(42.dp).border(.7.dp,colors.muted.copy(.25f),RoundedCornerShape(8.dp)).clickable {store.changeFontSize(store.fontSize-1)}.semantics {contentDescription="减小字号"},contentAlignment=Alignment.Center) {Glyph("minus",colors.ink,size=18.dp)}
            Text(store.fontSize.toString(),Modifier.width(55.dp),fontSize=15.sp,color=colors.ink,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            Box(Modifier.size(42.dp).border(.7.dp,colors.muted.copy(.25f),RoundedCornerShape(8.dp)).clickable {store.changeFontSize(store.fontSize+1)}.semantics {contentDescription="增大字号"},contentAlignment=Alignment.Center) {Glyph("plus",colors.ink,size=18.dp)}
        }
        HorizontalDivider(color=colors.muted.copy(.15f))
        Row(Modifier.fillMaxWidth().padding(vertical=18.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("字体",Modifier.weight(1f),fontSize=12.sp,color=colors.muted)
            SettingPill("宋体",store.serif,colors) {store.changeSerif(true)}
            Spacer(Modifier.width(9.dp))
            SettingPill("黑体",!store.serif,colors) {store.changeSerif(false)}
        }
        Row(Modifier.fillMaxWidth().padding(bottom=17.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("行间距",Modifier.weight(1f),fontSize=12.sp,color=colors.muted)
            SettingPill("紧凑",!store.spacious,colors) {store.changeSpacious(false)}
            Spacer(Modifier.width(9.dp))
            SettingPill("舒展",store.spacious,colors) {store.changeSpacious(true)}
        }
        HorizontalDivider(color=colors.muted.copy(.15f))
        Text("翻页效果",Modifier.padding(top=19.dp,bottom=13.dp),fontSize=12.sp,color=colors.muted)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(9.dp)) {
            PageTurnStyle.entries.forEach { style ->
                Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {
                    SettingPill(style.label,store.pageTurnStyle==style,colors) {store.changePageTurnStyle(style)}
                }
            }
        }
        Text("阅读位置自动保存 · 离线也能安心阅读",Modifier.align(Alignment.CenterHorizontally).padding(top=13.dp),fontSize=10.sp,color=colors.muted)
    }
    }
}

@Composable
private fun SettingPill(label:String,selected:Boolean,colors:ReaderColors,action:()->Unit) {
    Text(label,Modifier.background(if(selected) Accent.copy(.12f) else colors.muted.copy(.07f),RoundedCornerShape(7.dp)).clickable(onClick=action).padding(horizontal=22.dp,vertical=10.dp),fontSize=12.sp,color=if(selected) Accent else colors.muted)
}
