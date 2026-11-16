package com.kkwenxue.shanye.ui

import androidx.activity.compose.BackHandler
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
import com.kkwenxue.shanye.data.*
import com.kkwenxue.shanye.reader.*
import com.kkwenxue.shanye.audio.AudioPlayback
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(book:Book,store:ReaderStore,audioLibrary:AudioLibrary,audioItems:List<AudioChapter>,onAudioImported:()->Unit,onExit:()->Unit) {
    val context=LocalContext.current
    val initial=remember { store.positions[book.id] ?: ReadingPosition() }
    var chapter by remember { mutableIntStateOf(initial.chapter) }
    var jumpOffset by remember { mutableIntStateOf(initial.offset) }
    var jumpToken by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf(PageInfo()) }
    var controls by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf("") }
    var selectedSentence by remember { mutableStateOf<SentenceRange?>(null) }
    val contentsState = rememberLazyListState()
    var script by remember(book.id) { mutableStateOf(store.scriptFor(book)) }
    var followAudio by remember { mutableStateOf(true) }
    val activeAudio=AudioPlayback.current?.takeIf { it.bookId==book.id }
    val audioCue=activeAudio?.cueAt(AudioPlayback.position)
    val audioOffset=activeAudio?.offsetAt(AudioPlayback.position)
    val availableChapters=remember(audioItems,book.id) { audioItems.filter { it.bookId==book.id }.map { it.chapterId }.toSet() }
    val colors=readerColors(store.theme)
    val reader=remember { ReadingView(context) }
    val existingBookmark=store.bookmarks.firstOrNull { it.bookId==book.id && it.chapter==chapter && it.offset>=info.start && it.offset<info.end }
    val bookmark=existingBookmark ?: Bookmark(book.id,chapter,info.start,book.chapters[chapter].text.substring(info.start.coerceAtMost(book.chapters[chapter].text.length)).trim().replace("\n"," ").take(80))
    val bookmarked=existingBookmark!=null
    val selectedComment = selectedSentence?.let { store.commentFor(book.id, chapter, it) }
    LaunchedEffect(Unit) { delay(4200); hint=false }
    LaunchedEffect(toast) { if(toast.isNotEmpty()) { delay(1800); toast="" } }
    LaunchedEffect(jumpToken) { reader.goToOffset(jumpOffset) }
    LaunchedEffect(activeAudio?.id,audioOffset,followAudio,AudioPlayback.playing) {
        if(followAudio && AudioPlayback.playing && activeAudio!=null && audioOffset!=null) {
            chapter=activeAudio.chapterIndex
            jumpOffset=audioOffset
            // Keep the Android reader view in sync immediately. Waiting for the
            // next recomposition leaves the spoken sentence highlighted off-page.
            reader.followAudioTo(activeAudio.chapterIndex, audioOffset)
        }
    }
    BackHandler((sheet!=null && sheet!="audio") || controls) { if(sheet!=null) sheet=null else controls=false }
    fun jump(index:Int,offset:Int=0) { followAudio=false; chapter=index; jumpOffset=offset; jumpToken++; sheet=null; controls=false }
    LaunchedEffect(sheet, chapter) {
        if (sheet == "contents" && book.chapters.isNotEmpty()) {
            contentsState.scrollToItem(chapter.coerceIn(book.chapters.indices))
        }
    }
    if (sheet == "audio") {
        AudioPlayerScreen(book,audioLibrary,audioItems,onAudioImported,{sheet=null},
            initialChapterIndex=chapter,initialTextOffset=info.start,onRead={item,offset ->
            jump(item.chapterIndex,offset)
            followAudio=true
        })
        return
    }

    Box(Modifier.fillMaxSize().background(colors.paper)) {
        AndroidView(factory={reader},modifier=Modifier.fillMaxSize(),update={ view ->
            view.onPosition={ page ->
                chapter=page.chapter
                info=page
                store.savePosition(book,page.chapter,page.start)
            }
            view.onToggleMenu={ controls=!controls; hint=false }
            view.onPageTurn={ store.countPage() }
            view.onTurnStarted={controls=false; hint=false; followAudio=false}
            view.onBoundary={toast=it}
            view.onSentenceSelected={ sentence ->
                selectedSentence=sentence
                if (sentence != null) { controls=false; hint=false }
            }
            view.configure(book,chapter,jumpOffset,store.fontSize,store.spacious,store.serif,colors.ink.toArgb(),colors.muted.toArgb(),colors.paper.toArgb(),store.pageTurnStyle,script)
            view.setAudioCue(activeAudio?.chapterIndex ?: -1,audioCue?.start ?: 0,audioCue?.end ?: 0)
        })
        AnimatedVisibility(selectedSentence != null && !controls,Modifier.align(Alignment.BottomCenter),enter=fadeIn(),exit=fadeOut()) {
            SentenceActionBar(
                sentence=selectedSentence!!,
                comment=selectedComment,
                colors=colors,
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
                    store.toggleBookmark(bookmark); toast=if(bookmarked) "书签已移除" else "已收藏这一页"
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
                Text("听书 · ${audioTime(AudioPlayback.position)} / ${audioTime(activeAudio.durationMs)}",Modifier.clickable {sheet="audio"}.padding(end=12.dp),fontSize=11.sp,color=colors.ink)
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
                    selectedSentence?.let { sentence ->
                        CommentEditor(
                            sentence=sentence,
                            existing=selectedComment,
                            colors=colors,
                            onSave={ body ->
                                store.saveComment(book.id,chapter,sentence,body)
                                sheet=null
                                toast="这句评论已保存"
                            }
                        )
                    }
                }
                "contents" -> {
                    SheetTitle("目录", "${book.title} · ${book.chapters.size} 章",colors)
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
                            Column(Modifier.fillMaxWidth().clickable {jump(mark.chapter,mark.offset)}.padding(vertical=16.dp)) {
                                Text(mark.quote,fontFamily=Serif,fontSize=15.sp,lineHeight=25.sp,color=colors.ink,maxLines=3)
                                Text("第${mark.chapter+1}章 · ${book.chapters[mark.chapter].title}",Modifier.padding(top=10.dp),fontSize=10.sp,color=Accent)
                                HorizontalDivider(Modifier.padding(top=18.dp),color=colors.muted.copy(.15f))
                            }
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }
}

@Composable
private fun SentenceActionBar(
    sentence: SentenceRange,
    comment: SentenceComment?,
    colors: ReaderColors,
    onComment: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,bottom=20.dp)
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
