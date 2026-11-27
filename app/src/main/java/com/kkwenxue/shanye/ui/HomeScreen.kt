package com.kkwenxue.shanye.ui

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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun IconAction(name: String, label: String, tint: Color = Ink, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick=onClick).semantics { contentDescription = label }, contentAlignment=Alignment.Center) {
        Glyph(name,tint)
    }
}

@Composable
fun HomeScreen(store: ReaderStore, onOpen: (Book) -> Unit, onBookmark: (Bookmark) -> Unit, onAudio: () -> Unit,
    onSync: () -> Unit = {}, onlineStatuses: Map<String, OnlineSyncStatus> = emptyMap(),
    syncSettings: OnlineSyncSettings = OnlineSyncSettings(true, false),
    onSyncSettings: (OnlineSyncSettings) -> Unit = {}, onCancelSync: (String) -> Unit = {},
    onRetrySync: (Book, OnlineUpdatePlan?) -> Unit = { _, _ -> },
    onPostponeSync: (Book, OnlineUpdatePlan) -> Unit = { _, _ -> }, onRollback: (Book) -> Unit = {}) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Paper).statusBarsPadding()) {
        Box(Modifier.weight(1f)) {
            when(tab) {
                0 -> Discover(store,onOpen,onAll={tab=1},onSearch={searching=true; tab=1})
                1 -> Shelf(context,store,onOpen,searching,query,{searching=!searching},{query=it}, onSync, onlineStatuses,
                    syncSettings, onSyncSettings, onCancelSync, onRetrySync, onPostponeSync, onRollback,
                    )
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
}

@Composable
private fun Discover(store: ReaderStore, onOpen: (Book)->Unit, onAll: ()->Unit, onSearch: ()->Unit) {
    val featured = Library.books.firstOrNull{it.id==store.lastBook} ?: Library.books.firstOrNull()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom=20.dp)) {
        Row(Modifier.fillMaxWidth().padding(start=24.dp,end=16.dp,top=16.dp),verticalAlignment=Alignment.CenterVertically) {
            Glyph("mountain",Accent,size=25.dp)
            Text("山页",Modifier.padding(start=9.dp),fontFamily=Serif,fontSize=23.sp,letterSpacing=3.sp,fontWeight=FontWeight.SemiBold,color=Ink)
            Spacer(Modifier.weight(1f))
            Text(LocalDate.now().format(DateTimeFormatter.ofPattern("MM.dd")),fontSize=11.sp,color=Muted,letterSpacing=1.sp)
            Spacer(Modifier.width(8.dp))
            IconAction("search","搜索在线书库",onClick=onSearch)
        }
        Spacer(Modifier.height(28.dp))
        Text("把时间，留给好故事。",Modifier.padding(horizontal=24.dp),fontFamily=Serif,fontWeight=FontWeight.Medium,fontSize=25.sp,color=Ink,letterSpacing=.7.sp)
        Text("从在线书库选择一本书，目录会立即出现在书架。",Modifier.padding(start=24.dp,top=9.dp,end=24.dp),fontSize=12.sp,color=Muted,letterSpacing=.4.sp)
        if (featured == null) {
            Column(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=54.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Glyph("book",Muted,size=42.dp)
                Text("书架还是空的",Modifier.padding(top=18.dp),fontFamily=Serif,fontSize=20.sp,color=Ink)
                Text("先搜索在线书库，导入目录后即可开始阅读。",Modifier.padding(top=8.dp),fontSize=12.sp,color=Muted)
                Button(onClick=onSearch,Modifier.padding(top=22.dp)) { Text("搜索在线书库") }
            }
            return@Column
        }
        Row(Modifier.padding(horizontal=24.dp,vertical=24.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFE9EBE3)).clickable { onOpen(featured) }.padding(18.dp),verticalAlignment=Alignment.CenterVertically) {
            BookCover(featured,Modifier.width(100.dp).height(150.dp))
            Column(Modifier.weight(1f).padding(start=18.dp)) {
                Text("继续阅读",fontSize=9.sp,color=Color(0xFF72806C),letterSpacing=1.1.sp,fontWeight=FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text(featured.title,fontFamily=Serif,fontSize=21.sp,color=Ink,fontWeight=FontWeight.SemiBold)
                Text(featured.author+" 著",Modifier.padding(top=6.dp),fontSize=11.sp,color=Color(0xFF7A8275))
                val pos=store.resumePosition(featured)
                Text("第${pos.chapter+1}章 · "+featured.chapters[pos.chapter].title,Modifier.padding(top=12.dp),fontSize=10.sp,color=Color(0xFF7A8275),maxLines=1)
                Spacer(Modifier.height(7.dp))
                LinearProgressIndicator(progress={store.progress(featured)},modifier=Modifier.fillMaxWidth().height(2.dp),color=Accent,trackColor=Color(0xFFD5D9CD),drawStopIndicator={})
            }
        }
        Row(Modifier.padding(start=24.dp,end=19.dp).fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("我的书架",fontSize=19.sp,fontFamily=Serif,fontWeight=FontWeight.SemiBold,color=Ink)
            Spacer(Modifier.weight(1f))
            TextButton(onClick=onAll) { Text("查看全部") }
        }
        LazyRow(contentPadding=PaddingValues(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(18.dp)) {
            items(Library.books,key={it.id}) { book ->
                Column(Modifier.width(105.dp).clickable { onOpen(book) }) {
                    BookCover(book,Modifier.fillMaxWidth().height(146.dp))
                    Text(book.title,Modifier.padding(top=11.dp),fontSize=12.sp,color=Ink,fontWeight=FontWeight.Medium,maxLines=1)
                    Text(book.author+" · "+book.genre,Modifier.padding(top=4.dp),fontSize=10.sp,color=Muted)
                }
            }
        }
    }
}

@Composable
private fun Shelf(context: android.content.Context, store: ReaderStore,onOpen:(Book)->Unit,searching:Boolean,query:String,onSearch:()->Unit,onQuery:(String)->Unit,onSync:()->Unit,onlineStatuses: Map<String, OnlineSyncStatus>,
    syncSettings: OnlineSyncSettings, onSyncSettings: (OnlineSyncSettings) -> Unit, onCancelSync: (String) -> Unit,
    onRetrySync: (Book, OnlineUpdatePlan?) -> Unit, onPostponeSync: (Book, OnlineUpdatePlan) -> Unit,
    onRollback: (Book) -> Unit) {
    val catalog = remember { CatalogClient() }
    var network by rememberSaveable { mutableStateOf(false) }
    var remoteResults by remember { mutableStateOf<List<CatalogBook>>(emptyList()) }
    var remoteLoading by remember { mutableStateOf(false) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var selectedRemote by remember { mutableStateOf<CatalogBook?>(null) }
    var importingRemote by remember { mutableStateOf(false) }
    var showSyncSettings by rememberSaveable { mutableStateOf(false) }
    var pendingPlan by remember { mutableStateOf<Pair<Book, OnlineUpdatePlan>?>(null) }
    var pendingRollback by remember { mutableStateOf<Book?>(null) }
    var legacyToLink by remember { mutableStateOf<Book?>(null) }
    var sourceBook by remember { mutableStateOf<Book?>(null) }
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
            IconAction("refresh", "检查在线更新", onClick=onSync)
            IconAction(if(searching) "close" else "search","搜索书架",onClick=onSearch)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=24.dp), verticalAlignment=Alignment.CenterVertically) {
            Text("${Library.books.size} 本书 · 书籍来自在线书库",Modifier.weight(1f),fontSize=12.sp,color=Muted)
            TextButton(onClick={ showSyncSettings = true }) { Text("更新设置") }
        }
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
                Column {
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
                            Text(if (book.catalogBookId != null) "已关联在线书库" else "已离线",
                                Modifier.padding(start=5.dp),fontSize=9.sp,color=Color(0xFF819175))
                            Spacer(Modifier.weight(1f))
                            Text(if(store.progress(book)==0f) "未开始" else "已读 ${(store.progress(book)*100).toInt()}%",fontSize=10.sp,color=Accent)
                        }
                    }
                }
                if (book.catalogBookId != null) {
                    OnlineShelfStatus(onlineStatuses[book.id] ?: OnlineSyncStatus("idle"),
                        onCancel={ onCancelSync(book.id) }, onRetry={ onRetrySync(book, null) },
                        onPlan={ pendingPlan = book to it }, onRollback={ pendingRollback = book })
                    TextButton(
                        onClick = { sourceBook = book },
                        modifier = Modifier.padding(start = 94.dp)
                    ) { Text("切换来源") }
                } else if (book.isLegacyOnlineImport) {
                    LegacyOnlineAction(Library.books.any { it.legacyBookId == book.id }) { legacyToLink = book }
                }
                }
            }
        }
        }
    }
    selectedRemote?.let { item ->
        val existing = Library.books.firstOrNull {
            it.catalogBookId == item.catalogBookId &&
                (item.sourceKey == null || it.catalogSourceKey == item.sourceKey)
        }
        if (existing != null) {
            AlertDialog(onDismissRequest={ selectedRemote = null }, title={ Text(existing.title) },
                text={ Text("这本书已经在书架上。") },
                confirmButton={ TextButton(onClick={ selectedRemote = null; onOpen(existing) }) { Text("打开本地版本") } })
        } else {
        CatalogBookDialog(item, context, catalog, importingRemote,
            onDismiss={ if (!importingRemote) selectedRemote = null },
            onImported={ book -> Library.addImported(book); importingRemote = false; selectedRemote = null; onOpen(book) },
            setImporting={ importingRemote = it })
        }
    }
    if (showSyncSettings) AlertDialog(onDismissRequest={ showSyncSettings = false },
        title={ Text("在线书籍更新") },
        text={ Column {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("自动下载新增章节", Modifier.weight(1f))
                Switch(syncSettings.automatic, { onSyncSettings(syncSettings.copy(automatic=it)) })
            }
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("仅在不限流量网络下载", Modifier.weight(1f))
                Switch(syncSettings.unmeteredOnly, { onSyncSettings(syncSettings.copy(unmeteredOnly=it)) })
            }
            Text("暂停下载不影响检查更新和离线阅读。", fontSize=12.sp, color=Muted)
        } },
        confirmButton={ TextButton(onClick={ showSyncSettings = false }) { Text("完成") } })
    pendingPlan?.let { (book, plan) ->
        OnlinePlanDialog(plan, onConfirm={ pendingPlan = null; onRetrySync(book, plan) },
            onLater={ pendingPlan = null; onPostponeSync(book, plan) }, onDismiss={ pendingPlan = null })
    }
    pendingRollback?.let { book ->
        AlertDialog(onDismissRequest={ pendingRollback = null }, title={ Text("恢复上一正文版本？") },
            text={ Text("不会解除在线关联。同一远程版本不会自动再次安装，可以手动重试更新。") },
            dismissButton={ TextButton(onClick={ pendingRollback = null }) { Text("取消") } },
            confirmButton={ TextButton(onClick={ pendingRollback = null; onRollback(book) }) { Text("恢复上一版本") } })
    }
    legacyToLink?.let { legacy ->
        val linkCatalog = remember { CatalogClient() }
        LegacyLinkDialog(legacy, linkCatalog, context, onDismiss={ legacyToLink = null }, onLinked={ online ->
            Library.addImported(online)
            store.copyLegacyAnchors(legacy, online)
            legacyToLink = null
            onOpen(online)
        })
    }
    sourceBook?.let { book ->
        SourceSwitchDialog(
            book = book,
            catalog = catalog,
            context = context,
            onDismiss = { sourceBook = null },
            onSwitched = { updated ->
                Library.addImported(updated)
                sourceBook = null
                onOpen(updated)
            }
        )
    }
}

@Composable
internal fun LegacyOnlineAction(linked: Boolean, onLink: () -> Unit) {
    if (linked) {
        Text("旧版离线备份", Modifier.padding(start=100.dp, top=6.dp), fontSize=11.sp, color=Muted)
    } else {
        TextButton(onClick=onLink, modifier=Modifier.padding(start=94.dp)) { Text("关联在线书籍") }
    }
}

@Composable
private fun LegacyLinkDialog(legacy: Book, catalog: CatalogClient, context: android.content.Context,
    onDismiss: () -> Unit, onLinked: (Book) -> Unit) {
    var search by remember { mutableStateOf(legacy.title) }
    var options by remember { mutableStateOf<List<CatalogBook>>(emptyList()) }
    var selection by remember { mutableStateOf<CatalogBook?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(search) {
        options = emptyList()
        problem = null
        delay(300)
        try { options = runInterruptible(Dispatchers.IO) { catalog.search(search) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { problem = e.message ?: "无法搜索在线书库" }
    }
    AlertDialog(onDismissRequest={ if (!busy) onDismiss() },
        title={ Text("关联在线书籍") },
        text={ Column(Modifier.heightIn(max=340.dp).verticalScroll(rememberScrollState())) {
            Text("原书：《${legacy.title}》 · ${legacy.author}。关联后原书和原音频仍会保留。")
            val candidate = selection
            if (candidate == null) {
                OutlinedTextField(search, { search = it }, label={ Text("搜索在线书名") }, singleLine=true)
                options.forEach { item ->
                    Text("${item.title} · ${item.author}", Modifier.fillMaxWidth().clickable { selection = item }
                        .padding(vertical=10.dp))
                }
            } else {
                Text("所选：《${candidate.title}》 · ${candidate.author}", Modifier.padding(top=10.dp), fontWeight=FontWeight.Medium)
                Text("请确认书名和作者。无法迁移的进度需重新定位，原书始终可读。", fontSize=12.sp, color=Muted)
                if (!busy) TextButton(onClick={ selection = null }) { Text("重新选择") }
            }
            if (busy) Text("正在通过服务器中转取得正文…", Modifier.padding(top=10.dp), color=Accent)
            problem?.let { Text(it, Modifier.padding(top=8.dp), color=Color(0xFFAA5544)) }
        } },
        dismissButton={ TextButton(enabled=!busy, onClick=onDismiss) { Text("取消") } },
        confirmButton={ Button(enabled=selection != null && !busy, onClick={
            val chosen = selection ?: return@Button
            busy = true; problem = null
            scope.launch {
                try {
                    val online = downloadCatalogBook(context, catalog, chosen)
                    val linked = withContext(Dispatchers.IO) {
                        BookDatabase(context.applicationContext).use { it.linkLegacy(legacy.id, online) }
                    }
                    onLinked(linked)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { problem = e.message ?: "关联失败，原书未改动" }
                finally { busy = false }
            }
        }) { Text("确认关联") } })
}

@Composable
internal fun OnlineShelfStatus(status: OnlineSyncStatus, onCancel: () -> Unit, onRetry: () -> Unit,
    onPlan: (OnlineUpdatePlan) -> Unit, onRollback: () -> Unit) {
    val label = when (status.state) {
        "checking" -> "检查中"
        "planning" -> "服务器正在准备更新"
        "fetching" -> "服务器正在获取正文"
        "relaying" -> "正在中转"
        "downloading" -> "正在下载"
        "validating" -> "正在校验"
        "applying" -> "正在保存章节"
        "completed" -> "已更新"
        "available" -> "有更新"
        "needs_confirmation" -> "更新待确认"
        "postponed" -> "稍后更新"
        "failed" -> "检查或更新失败"
        "cancelled" -> "已取消等待"
        "paused" -> "待下载"
        "rolled_back" -> "已回滚"
        "up_to_date" -> "已是最新"
        else -> "待检查"
    }
    Column(Modifier.fillMaxWidth().padding(start=100.dp, top=6.dp)) {
        Text(label, fontSize=12.sp, color=if (status.state == "failed") Color(0xFFAA5544) else Accent)
        if (status.checkedAt > 0) {
            val date = Instant.ofEpochMilli(status.checkedAt).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            Text("上次成功检查 $date", fontSize=10.sp, color=Muted)
        }
        status.latestTitle?.takeIf { it.isNotBlank() }?.let {
            Text("最新：$it", fontSize=11.sp, color=Muted, maxLines=2)
        }
        if (status.newChapters > 0 && status.state != "completed") {
            Text("比本地多 ${status.newChapters} 章", fontSize=11.sp, color=Muted)
        }
        status.message?.let { Text(it, fontSize=11.sp, color=Muted) }
        val plan = status.plan
        if (status.state == "downloading" && plan != null) {
            LinearProgressIndicator(progress={ (status.downloaded.toFloat() / plan.bytes).coerceIn(0f, 1f) },
                modifier=Modifier.fillMaxWidth().padding(top=5.dp))
        }
        when (status.state) {
            "checking", "planning", "fetching", "relaying", "downloading" ->
                TextButton(onClick=onCancel) { Text("取消等待") }
            "failed", "cancelled", "paused", "available", "rolled_back" ->
                TextButton(onClick=onRetry) { Text("重试更新") }
            "needs_confirmation", "postponed" -> if (plan != null) {
                TextButton(onClick={ onPlan(plan) }) { Text("查看更新计划") }
            }
        }
        if (status.canRollback && status.state in setOf("completed", "up_to_date", "needs_confirmation", "postponed", "failed")) {
            TextButton(onClick=onRollback) { Text("回滚正文") }
        }
    }
}

@Composable
private fun SourceSwitchDialog(
    book: Book,
    catalog: CatalogClient,
    context: android.content.Context,
    onDismiss: () -> Unit,
    onSwitched: (Book) -> Unit
) {
    var sources by remember { mutableStateOf<List<CatalogSource>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var resolvedCatalogBookId by remember(book.id) { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val currentSourceKey = book.catalogSourceKey
    LaunchedEffect(book.id, book.catalogBookId, currentSourceKey) {
        try {
            val result = withContext(Dispatchers.IO) {
                catalog.sources(requireNotNull(book.catalogBookId), currentSourceKey)
            }
            resolvedCatalogBookId = result.bookId
            sources = result.items
                .filter { it.state == "confirmed" && it.sourceKey.isNotBlank() }
                .distinctBy { it.sourceKey }
            selected = result.recommendedSource
                ?.takeIf { it != currentSourceKey && sources.any { source -> source.sourceKey == it } }
                ?: sources.firstOrNull { it.sourceKey != currentSourceKey }?.sourceKey
        } catch (e: Exception) {
            error = e.message ?: "无法读取来源列表"
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("切换在线来源") },
        text = {
            Column {
                Text("不会创建第二本书，阅读进度和书签仍保留。")
                Text(
                    if (currentSourceKey == null) {
                        "当前来源：未记录（优先使用推荐来源）"
                    } else {
                        "当前来源：${sourceDisplayName(currentSourceKey)}（$currentSourceKey）"
                    },
                    Modifier.padding(top = 8.dp),
                    color = Muted,
                    fontSize = 12.sp
                )
                if (sources.isEmpty() && error == null) {
                    CircularProgressIndicator(Modifier.padding(top = 14.dp).size(20.dp), color = Accent)
                }
                sources.forEach { source ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = source.sourceKey }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected == source.sourceKey, { selected = source.sourceKey })
                        Column {
                            Text(
                                "${sourceDisplayName(source.sourceKey)}（${source.sourceKey}）" +
                                    if (source.preferred) " · 推荐" else ""
                            )
                            Text("${source.title} · ${source.author}", fontSize = 11.sp, color = Muted)
                        }
                    }
                }
                error?.let { Text(it, Modifier.padding(top = 8.dp), color = Color(0xFFAA5544)) }
                if (busy) Text("正在加载新来源目录…", Modifier.padding(top = 8.dp), color = Accent)
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(enabled = selected != null && selected != currentSourceKey && !busy, onClick = {
                val source = selected ?: return@Button
                busy = true
                error = null
                scope.launch {
                    try {
                        val catalogBookId = resolvedCatalogBookId ?: requireNotNull(book.catalogBookId)
                        withContext(Dispatchers.IO) {
                            catalog.chooseSource(catalogBookId, source)
                        }
                        val item = CatalogBook(
                            id = catalogBookId,
                            title = book.title,
                            author = book.author,
                            category = book.genre,
                            status = "",
                            words = 0,
                            chapterNo = book.chapters.size.toLong(),
                            lastUpdate = "",
                            latestChapter = "",
                            latestChapterId = book.remoteLatestChapterId,
                            sourceKey = source
                        )
                        val switched = downloadCatalogBook(context, catalog, item)
                            .copy(id = book.id, legacyBookId = book.legacyBookId)
                        val saved = withContext(Dispatchers.IO) {
                            BookDatabase(context.applicationContext).use { db ->
                                db.save(switched)
                                db.load(book.id).firstOrNull() ?: switched
                            }
                        }
                        onSwitched(saved)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "切换来源失败"
                    } finally {
                        busy = false
                    }
                }
            }) { Text("切换") }
        }
    )
}

@Composable
internal fun OnlinePlanDialog(plan: OnlineUpdatePlan, onConfirm: () -> Unit, onLater: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest=onDismiss, title={ Text("确认正文更新") },
        text={ Column(Modifier.heightIn(max=340.dp).verticalScroll(rememberScrollState())) {
            Text("更新会改动已有章节或顺序。旧版本可以回滚。")
            Text("预计下载 ${"%.1f".format(plan.bytes / 1024.0)} KB")
            if (plan.reordered) Text("章节位置或顺序有变化")
            listOf("新增" to plan.added, "变更" to plan.modified, "删除" to plan.deleted).forEach { (label, titles) ->
                Text("$label ${titles.size} 章", Modifier.padding(top=8.dp), fontWeight=FontWeight.Medium)
                titles.forEach { Text(it, fontSize=12.sp) }
            }
        } },
        dismissButton={ TextButton(onClick=onLater) { Text("稍后") } },
        confirmButton={ TextButton(onClick=onConfirm) { Text("确认更新") } })
}

@Composable
private fun CatalogBookDialog(item: CatalogBook, context: android.content.Context, catalog: CatalogClient, importing: Boolean,
    onDismiss: () -> Unit, onImported: (Book) -> Unit, setImporting: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf("") }
    var sourceMenu by remember { mutableStateOf(false) }
    var selectedSource by remember {
        mutableStateOf(
            item.sourceKey
                ?: item.sources.firstOrNull { it.preferred }?.sourceKey
                ?: item.sources.firstOrNull()?.sourceKey
        )
    }
    val sourceOptions = item.sources.filter { it.state == "confirmed" }.ifEmpty {
        item.sourceKey?.let { listOf(CatalogSource(it, "", item.title, item.author, "confirmed", true)) }.orEmpty()
    }
    AlertDialog(onDismissRequest=onDismiss, containerColor=Paper,
        title={Text(item.title, fontFamily=Serif, color=Ink)},
        text={Column { Text("${item.author} · ${item.category} · ${item.status}", color=Muted, fontSize=12.sp)
            if (sourceOptions.size > 1) {
                Box {
                    TextButton(onClick={ sourceMenu = true }) {
                        Text("来源：${selectedSource ?: "未选择"}")
                    }
                    DropdownMenu(expanded=sourceMenu, onDismissRequest={ sourceMenu = false }) {
                        sourceOptions.forEach { source ->
                            DropdownMenuItem(
                                text={
                                    Text(
                                        "${sourceDisplayName(source.sourceKey)}（${source.sourceKey}）" +
                                            if (source.preferred) " · 推荐" else ""
                                    )
                                },
                                onClick={
                                    selectedSource = source.sourceKey
                                    sourceMenu = false
                                }
                            )
                        }
                    }
                }
            }
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
                    val imported = downloadCatalogBook(
                        context,
                        catalog,
                        item.copy(sourceKey = selectedSource),
                    ) { phase = it }
                    val book = withContext(Dispatchers.IO) {
                        BookDatabase(context.applicationContext).use { it.saveOnlineImport(imported) }
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

private fun sourceDisplayName(sourceKey: String): String = when (sourceKey) {
    "sudugu520" -> "速读谷 520"
    "suduguu" -> "速读谷"
    else -> sourceKey
}

private suspend fun downloadCatalogBook(context: android.content.Context, catalog: CatalogClient, item: CatalogBook,
    phase: (String) -> Unit = {}): Book {
    phase("正在读取章节目录…")
    val manifest=runInterruptible(Dispatchers.IO){catalog.completeManifest(item.catalogBookId, item.sourceKey)}
    val chapters=manifest.chapters
    val revision=manifest.revision
    phase("目录已就绪，共 ${chapters.size} 章")
    return Book(
        id = "online:catalog:${item.catalogBookId}", title = item.title, author = item.author,
        genre = item.category, subtitle = "${chapters.size} 章 · 在线目录",
        description = "从在线书库导入，正文按章节加载。", cover = 1,
        chapters = chapters.sortedBy { it.index }.map { ref ->
            Chapter("online:catalog:${item.catalogBookId}:chapter:${ref.chapterId}", ref.title, "", ref.bodySha256)
        }, remoteSource = item.sourceKey ?: "suduguu", remoteBookId = item.catalogBookId,
        catalogBookId = item.catalogBookId,
        activeRevision = revision, remoteLatestChapterId = item.latestChapterId,
        remoteLatestVersion = revision
    )
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
            val position = store.positionFor(book, mark)
            Column(Modifier.fillMaxWidth().padding(bottom=14.dp).background(Color(0xFFEEEBE3),RoundedCornerShape(10.dp)).clickable(enabled=position!=null) { onBookmark(mark) }.padding(18.dp)) {
                Text(mark.quote,fontFamily=Serif,fontSize=15.sp,lineHeight=25.sp,color=Ink,maxLines=3)
                Text("《${book.title}》 · " + (position?.let { "第${it.chapter+1}章" } ?: "原文已变更，请重新定位"),
                    Modifier.padding(top=12.dp),fontSize=10.sp,color=Accent)
            }
        }
        if (store.comments.isNotEmpty()) {
            Text("我的评论",Modifier.padding(top=29.dp,bottom=18.dp),fontSize=19.sp,fontFamily=Serif,color=Ink)
            store.comments.forEach { comment ->
                Column(Modifier.fillMaxWidth().padding(bottom=14.dp).background(Color(0xFFE9EDE5),RoundedCornerShape(10.dp)).padding(18.dp)) {
                    Text(comment.body,fontSize=13.sp,color=Ink,lineHeight=22.sp)
                    Text("“${comment.quote}”",Modifier.padding(top=10.dp),fontFamily=Serif,fontSize=13.sp,color=Muted,maxLines=2)
                    val commentedBook=Library.book(comment.bookId)
                    val relocated = comment.anchor?.locate(commentedBook, comment.end - comment.start)
                    Text("《${commentedBook.title}》 · " + if (store.commentNeedsRelocation(commentedBook, comment))
                        "原文已变更，请重新定位" else "第${(relocated?.chapter ?: comment.chapter)+1}章",
                        Modifier.padding(top=9.dp),fontSize=10.sp,color=Accent)
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
