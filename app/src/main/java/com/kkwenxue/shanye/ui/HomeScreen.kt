package com.kkwenxue.shanye.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kkwenxue.shanye.data.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun IconAction(name: String, label: String, tint: Color = Ink, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick=onClick).semantics { contentDescription = label }, contentAlignment=Alignment.Center) {
        Glyph(name,tint)
    }
}

@Composable
fun HomeScreen(store: ReaderStore, onOpen: (Book) -> Unit, onBookmark: (Bookmark) -> Unit, onAudio: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try {
                val book = withContext(Dispatchers.IO) { LocalBookRepository(context).import(uri) }
                Library.addImported(book)
                Toast.makeText(context, "已导入《${book.title}》 · ${book.chapters.size} 章", Toast.LENGTH_LONG).show()
                onOpen(book)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                snackbar.showSnackbar(error.message ?: "导入失败，请检查文件后重试。")
            } finally { importing = false }
        }
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Paper).statusBarsPadding()) {
        Box(Modifier.weight(1f)) {
            when(tab) {
                0 -> Discover(store,onOpen,onAll={tab=1},onSearch={searching=true; tab=1})
                1 -> Shelf(context,store,onOpen,searching,query,{searching=!searching},{query=it},
                    onImport={importFile.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream"))})
                else -> Profile(store,onBookmark)
            }
        }
        Column(Modifier.background(Paper).navigationBarsPadding()) {
            HorizontalDivider(color=Rule,thickness=.7.dp)
            Row(Modifier.fillMaxWidth().height(68.dp),verticalAlignment=Alignment.CenterVertically) {
                listOf("书房" to "home","书架" to "book","我的" to "user","听书" to "audio").forEachIndexed { index,(label,icon) ->
                    Column(Modifier.weight(1f).fillMaxHeight().clickable { if(index==3) onAudio() else tab=index }.padding(top=11.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Glyph(icon,if(tab==index) Accent else Muted,size=22.dp)
                        Spacer(Modifier.height(5.dp))
                        Text(label,fontSize=11.sp,letterSpacing=1.sp,color=if(tab==index) Accent else Muted,fontWeight=if(tab==index) FontWeight.Medium else FontWeight.Normal)
                    }
                }
            }
        }
    }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start=16.dp,end=16.dp,bottom=78.dp))
    }
    if (importing) AlertDialog(
        onDismissRequest={},
        title={Text("正在导入书籍",fontFamily=Serif)},
        text={Row(verticalAlignment=Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp),color=Accent,strokeWidth=2.dp)
            Text("正在整理目录和正文…",Modifier.padding(start=16.dp),color=Muted)
        }},
        confirmButton={},
        containerColor=Paper
    )
}

@Composable
private fun Discover(store: ReaderStore, onOpen: (Book)->Unit,onAll:()->Unit,onSearch:()->Unit) {
    var category by rememberSaveable { mutableStateOf("全部") }
    val featured=Library.book(store.lastBook)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom=20.dp)) {
        Row(Modifier.fillMaxWidth().padding(start=24.dp,end=16.dp,top=16.dp),verticalAlignment=Alignment.CenterVertically) {
            Glyph("mountain",Accent,size=25.dp)
            Text("山页",Modifier.padding(start=9.dp),fontFamily=Serif,fontSize=23.sp,letterSpacing=3.sp,fontWeight=FontWeight.SemiBold,color=Ink)
            Spacer(Modifier.weight(1f))
            Text(LocalDate.now().format(DateTimeFormatter.ofPattern("MM.dd"))+"  /  "+listOf("周一","周二","周三","周四","周五","周六","周日")[LocalDate.now().dayOfWeek.value-1],fontSize=11.sp,color=Muted,letterSpacing=1.sp)
            Spacer(Modifier.width(8.dp))
            IconAction("search","搜索书籍",onClick=onSearch)
        }
        Spacer(Modifier.height(19.dp))
        Text("把时间，留给好故事。",Modifier.padding(horizontal=24.dp),fontFamily=Serif,fontWeight=FontWeight.Medium,fontSize=25.sp,color=Ink,letterSpacing=.7.sp)
        Text("世界很快，在这里慢一点。",Modifier.padding(start=24.dp,top=9.dp),fontSize=12.sp,color=Muted,letterSpacing=1.sp)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.padding(horizontal=24.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFE9EBE3)).clickable { onOpen(featured) }.padding(18.dp),verticalAlignment=Alignment.CenterVertically) {
            BookCover(featured,Modifier.width(100.dp).height(150.dp))
            Column(Modifier.weight(1f).padding(start=18.dp)) {
                Text("正在读  /  CONTINUE",fontSize=9.sp,color=Color(0xFF72806C),letterSpacing=1.1.sp,fontWeight=FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text(featured.title,fontFamily=Serif,fontSize=21.sp,color=Ink,fontWeight=FontWeight.SemiBold)
                Text(featured.author+" 著",Modifier.padding(top=6.dp),fontSize=11.sp,color=Color(0xFF7A8275))
                val pos=store.resumePosition(featured)
                Text("第${pos.chapter+1}章 · "+featured.chapters[pos.chapter].title,Modifier.padding(top=12.dp),fontSize=10.sp,color=Color(0xFF7A8275),maxLines=1)
                Spacer(Modifier.height(7.dp))
                LinearProgressIndicator(progress={store.progress(featured)},modifier=Modifier.fillMaxWidth().height(2.dp),color=Accent,trackColor=Color(0xFFD5D9CD),drawStopIndicator={})
                Row(Modifier.fillMaxWidth().padding(top=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("继续阅读",fontSize=12.sp,fontWeight=FontWeight.Medium,color=Accent)
                    Spacer(Modifier.weight(1f))
                    Glyph("arrow",Accent,size=18.dp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.padding(start=24.dp,end=19.dp).fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("为你精选",fontSize=19.sp,fontFamily=Serif,fontWeight=FontWeight.SemiBold,color=Ink)
            Spacer(Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick=onAll).padding(5.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("全部书籍",fontSize=11.sp,color=Muted)
                Glyph("next",Muted,Modifier.padding(start=4.dp),14.dp)
            }
        }
        Row(Modifier.padding(start=24.dp,top=13.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            listOf("全部","文学","悬疑","幻想").forEach { label ->
                Text(label,Modifier.clip(RoundedCornerShape(7.dp)).background(if(category==label) Ink else Color.Transparent).clickable{category=label}.padding(horizontal=15.dp,vertical=7.dp),color=if(category==label) Paper else Muted,fontSize=11.sp)
            }
        }
        Spacer(Modifier.height(15.dp))
        LazyRow(contentPadding=PaddingValues(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(18.dp)) {
            items(Library.books.filter { category=="全部" || it.genre==category },key={it.id}) { book ->
                Column(Modifier.width(105.dp).clickable { onOpen(book) }) {
                    BookCover(book,Modifier.fillMaxWidth().height(146.dp))
                    Text(book.title,Modifier.padding(top=11.dp),fontSize=12.sp,color=Ink,fontWeight=FontWeight.Medium,maxLines=1)
                    Text(book.author+" · "+book.genre,Modifier.padding(top=4.dp),fontSize=10.sp,color=Muted)
                }
            }
        }
        Row(Modifier.padding(start=24.dp,end=24.dp,top=29.dp).fillMaxWidth().background(Color(0xFFEEEBE3),RoundedCornerShape(10.dp)).padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("“",fontFamily=Serif,fontSize=35.sp,color=Accent.copy(.7f))
            Column(Modifier.padding(start=13.dp)) {
                Text("总有一句话，让你停留。",fontFamily=Serif,fontSize=14.sp,color=Ink)
                Text("打开一本书，去别处生活。",Modifier.padding(top=6.dp),fontSize=10.sp,color=Muted)
            }
        }
    }
}

@Composable
private fun Shelf(context: android.content.Context, store: ReaderStore,onOpen:(Book)->Unit,searching:Boolean,query:String,onSearch:()->Unit,onQuery:(String)->Unit,onImport:()->Unit) {
    val catalog = remember { CatalogClient() }
    var network by rememberSaveable { mutableStateOf(false) }
    var remoteResults by remember { mutableStateOf<List<CatalogBook>>(emptyList()) }
    var remoteLoading by remember { mutableStateOf(false) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var selectedRemote by remember { mutableStateOf<CatalogBook?>(null) }
    var importingRemote by remember { mutableStateOf(false) }
    val remoteScope = rememberCoroutineScope()
    LaunchedEffect(network, query) {
        if (!network || query.trim().length < 1) { remoteResults = emptyList(); remoteError = null; remoteLoading = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        remoteLoading = true; remoteError = null
        runCatching { withContext(Dispatchers.IO) { catalog.search(query) } }
            .onSuccess { remoteResults = it }
            .onFailure { remoteResults = emptyList(); remoteError = it.message ?: "网络搜索失败" }
        remoteLoading = false
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start=24.dp,end=14.dp,top=22.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("我的书架",fontFamily=Serif,fontSize=28.sp,color=Ink,fontWeight=FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick=onImport) {
                Glyph("plus",Accent,size=16.dp)
                Text("导入",Modifier.padding(start=5.dp),fontSize=12.sp,color=Accent)
            }
            IconAction(if(searching) "close" else "search","搜索书架",onClick=onSearch)
        }
        Text("${Library.books.size} 本书 · 支持导入 TXT / EPUB",Modifier.padding(start=24.dp,top=8.dp,bottom=24.dp),fontSize=12.sp,color=Muted)
        if(searching) {
            OutlinedTextField(query,onQuery,Modifier.fillMaxWidth().padding(start=24.dp,end=24.dp,bottom=18.dp),placeholder={Text("搜索书名、作者或类型",fontSize=13.sp)},singleLine=true,shape=RoundedCornerShape(12.dp),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Accent,unfocusedBorderColor=Rule))
            Row(Modifier.padding(horizontal=24.dp).fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("本地" to false, "全网" to true).forEach { (label, value) ->
                    Text(label, Modifier.clip(RoundedCornerShape(8.dp)).background(if (network == value) Ink else Color.Transparent)
                        .clickable { network = value }.padding(horizontal = 18.dp, vertical = 8.dp),
                        color = if (network == value) Paper else Muted, fontSize = 12.sp)
                }
            }
        }
        if (searching && network) {
            when {
                remoteLoading -> Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement=Arrangement.Center) { CircularProgressIndicator(Modifier.size(22.dp), color=Accent, strokeWidth=2.dp) }
                remoteError != null -> Text(remoteError ?: "网络搜索失败", Modifier.padding(24.dp), color=Color(0xFFAA5544), fontSize=13.sp)
                query.isNotBlank() && remoteResults.isEmpty() -> Text("全网没有找到匹配的书。", Modifier.padding(24.dp), color=Muted, fontSize=13.sp)
                else -> LazyColumn(contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=24.dp), verticalArrangement=Arrangement.spacedBy(18.dp)) {
                    items(remoteResults, key={it.id}) { item ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFEEEBE3)).clickable { selectedRemote = item }.padding(18.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text(item.title, fontFamily=Serif, fontSize=19.sp, color=Ink, fontWeight=FontWeight.Medium)
                                Spacer(Modifier.weight(1f)); Text(item.status, fontSize=10.sp, color=Accent)
                            }
                            Text("${item.author} · ${item.category}", Modifier.padding(top=7.dp), fontSize=11.sp, color=Muted)
                            Text("${item.wordsLabel} · 更新 ${item.lastUpdate.ifBlank { "未知" }}", Modifier.padding(top=7.dp), fontSize=10.sp, color=Muted)
                            Text(item.latestChapter.ifBlank { "暂无最新章节" }, Modifier.padding(top=7.dp), fontSize=11.sp, color=Ink, maxLines=1)
                        }
                    }
                }
            }
        } else {
        val books=Library.books.filter { !searching || query.isBlank() || it.title.contains(query) || it.author.contains(query) || it.genre.contains(query) }
        LazyColumn(contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
            if(books.isEmpty()) item { Text("没有找到这本书，换个词试试。",Modifier.padding(vertical=32.dp),color=Muted,fontSize=14.sp) }
            items(books,key={it.id}) { book ->
                Row(Modifier.fillMaxWidth().clickable {onOpen(book)},verticalAlignment=Alignment.CenterVertically) {
                    BookCover(book,Modifier.width(80.dp).height(118.dp))
                    Column(Modifier.weight(1f).padding(start=20.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(book.title,fontFamily=Serif,fontSize=20.sp,color=Ink,fontWeight=FontWeight.Medium)
                            Spacer(Modifier.weight(1f)); Glyph("next",Muted,size=16.dp)
                        }
                        Text(book.author+" · "+book.genre,Modifier.padding(top=8.dp),fontSize=11.sp,color=Muted)
                        Text(book.subtitle,Modifier.padding(top=12.dp),fontSize=11.sp,color=Muted,maxLines=1)
                        Row(Modifier.padding(top=15.dp),verticalAlignment=Alignment.CenterVertically) {
                            Box(Modifier.size(4.dp).background(Color(0xFF8B9C7D),CircleShape))
                            Text("已离线",Modifier.padding(start=5.dp),fontSize=9.sp,color=Color(0xFF819175))
                            Spacer(Modifier.weight(1f))
                            Text(if(store.progress(book)==0f) "未开始" else "已读 ${(store.progress(book)*100).toInt()}%",fontSize=10.sp,color=Accent)
                        }
                    }
                }
            }
        }
        }
    }
    selectedRemote?.let { item ->
        CatalogBookDialog(item, context, catalog, importingRemote,
            onDismiss={ if (!importingRemote) selectedRemote = null },
            onImported={ book -> Library.addImported(book); importingRemote = false; selectedRemote = null; onOpen(book) },
            setImporting={ importingRemote = it })
    }
}

@Composable
private fun CatalogBookDialog(item: CatalogBook, context: android.content.Context, catalog: CatalogClient, importing: Boolean,
    onDismiss: () -> Unit, onImported: (Book) -> Unit, setImporting: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=onDismiss, containerColor=Paper,
        title={Text(item.title, fontFamily=Serif, color=Ink)},
        text={Column { Text("${item.author} · ${item.category} · ${item.status}", color=Muted, fontSize=12.sp)
            Text("${item.wordsLabel} · ${item.chapterNo} 章 · 更新 ${item.lastUpdate.ifBlank { "未知" }}", Modifier.padding(top=10.dp), color=Muted, fontSize=12.sp)
            Text(item.latestChapter.ifBlank { "暂无最新章节" }, Modifier.padding(top=10.dp), color=Ink, fontSize=13.sp)
            if (error != null) Text(error ?: "", Modifier.padding(top=12.dp), color=Color(0xFFAA5544), fontSize=12.sp)
            if (importing) Row(Modifier.padding(top=16.dp), verticalAlignment=Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), color=Accent, strokeWidth=2.dp); Text(phase, Modifier.padding(start=10.dp), color=Muted, fontSize=12.sp) }
        }},
        dismissButton={ TextButton(enabled=!importing, onClick=onDismiss) { Text("关闭", color=Muted) } },
        confirmButton={ Button(enabled=!importing, onClick={
            setImporting(true); error = null
            scope.launch {
                try {
                    phase = "服务器正在准备正文…"
                    var task = withContext(Dispatchers.IO) { catalog.startContent(item.id) }
                    var pollCount = 0
                    while (!task.done) {
                        check(task.state !in setOf("failed", "expired", "cancelled")) {
                            task.message ?: "服务器未能准备正文，请重试"
                        }
                        check(pollCount++ < 360) { "服务器仍在处理正文，请稍后重试" }
                        kotlinx.coroutines.delay(1000)
                        task = withContext(Dispatchers.IO) { catalog.status(task.taskId) }
                    }
                    phase = "正在通过中转下载正文…"
                    val book = withContext(Dispatchers.IO) {
                        val target = File.createTempFile("catalog-", ".zip", context.cacheDir)
                        try {
                            var waits = 0
                            while (!catalog.download(task, target)) {
                                check(waits++ < 60) { "中转仍在上传，请稍后重试" }
                                kotlinx.coroutines.delay(2000)
                            }
                            ContentPackageImporter.import(target, item).also {
                                LocalBookRepository(context).save(it)
                            }
                        } finally { target.delete() }
                    }
                    onImported(book)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    error = e.message ?: "导入失败"
                } finally { setImporting(false) }
            }
        }) { Text("导入正文") } })
}

@Composable
private fun Profile(store:ReaderStore,onBookmark:(Bookmark)->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top=35.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(62.dp).background(Color(0xFFE6E9DE),CircleShape),contentAlignment=Alignment.Center) { Glyph("mountain",Color(0xFF78836E),size=30.dp) }
            Column(Modifier.padding(start=18.dp)) {
                Text("山间读者",fontFamily=Serif,fontSize=24.sp,color=Ink)
                Text("慢一点，也很好。",Modifier.padding(top=7.dp),fontSize=12.sp,color=Muted)
            }
        }
        Spacer(Modifier.height(32.dp))
        Row(Modifier.fillMaxWidth().background(Color(0xFFEFECE4),RoundedCornerShape(12.dp)).padding(vertical=24.dp)) {
            listOf(Library.books.size.toString() to "离线藏书",store.pagesRead.toString() to "累计翻页",store.bookmarks.size.toString() to "我的书签",store.comments.size.toString() to "我的评论").forEach { (value,label) ->
                Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(value,fontSize=27.sp,fontFamily=Serif,color=Ink)
                    Text(label,Modifier.padding(top=6.dp),fontSize=10.sp,color=Muted)
                }
            }
        }
        Text("我的书签",Modifier.padding(top=33.dp,bottom=18.dp),fontSize=19.sp,fontFamily=Serif,color=Ink)
        if(store.bookmarks.isEmpty()) {
            Column(Modifier.fillMaxWidth().border(1.dp,Rule,RoundedCornerShape(12.dp)).padding(25.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Glyph("bookmark",Muted,size=27.dp)
                Text("留住故事里的某一页",Modifier.padding(top=14.dp),fontSize=13.sp,color=Ink)
                Text("阅读时轻点屏幕，点右上角添加书签。",Modifier.padding(top=8.dp),fontSize=10.sp,color=Muted)
            }
        } else store.bookmarks.forEach { mark ->
            val book=Library.book(mark.bookId)
            Column(Modifier.fillMaxWidth().padding(bottom=14.dp).background(Color(0xFFEEEBE3),RoundedCornerShape(10.dp)).clickable { onBookmark(mark) }.padding(18.dp)) {
                Text(mark.quote,fontFamily=Serif,fontSize=15.sp,lineHeight=25.sp,color=Ink,maxLines=3)
                Text("《${book.title}》 · 第${mark.chapter+1}章",Modifier.padding(top=12.dp),fontSize=10.sp,color=Accent)
            }
        }
        if (store.comments.isNotEmpty()) {
            Text("我的评论",Modifier.padding(top=29.dp,bottom=18.dp),fontSize=19.sp,fontFamily=Serif,color=Ink)
            store.comments.forEach { comment ->
                Column(Modifier.fillMaxWidth().padding(bottom=14.dp).background(Color(0xFFE9EDE5),RoundedCornerShape(10.dp)).padding(18.dp)) {
                    Text(comment.body,fontSize=13.sp,color=Ink,lineHeight=22.sp)
                    Text("“${comment.quote}”",Modifier.padding(top=10.dp),fontFamily=Serif,fontSize=13.sp,color=Muted,maxLines=2)
                    val commentedBook=Library.book(comment.bookId)
                    Text("《${commentedBook.title}》 · 第${comment.chapter+1}章",Modifier.padding(top=9.dp),fontSize=10.sp,color=Accent)
                }
            }
        }
        HorizontalDivider(Modifier.padding(top=30.dp),color=Rule)
        Row(Modifier.padding(vertical=23.dp),verticalAlignment=Alignment.CenterVertically) {
            Glyph("leaf",Muted,size=19.dp)
            Text("所有故事已保存在本机，随时离线阅读。",Modifier.padding(start=10.dp),fontSize=11.sp,color=Muted)
        }
        Text("山页  0.5.1  /  精品小说阅读原型",fontSize=10.sp,color=Muted.copy(.7f),modifier=Modifier.padding(bottom=28.dp))
    }
}
