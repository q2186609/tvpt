package tv.tvpt.client.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.tvpt.client.data.Api
import tv.tvpt.client.data.DownloadItem
import tv.tvpt.client.ui.components.FocusSurface
import tv.tvpt.client.ui.components.GhostButton
import tv.tvpt.client.ui.components.requestFocusWithRetry
import tv.tvpt.client.ui.theme.TvptSurfaceHigh
import tv.tvpt.client.ui.theme.TvptTextSecondary
import tv.tvpt.client.ui.theme.TvptTextTertiary

/**
 * 右侧菜单里的「下载」页：qBittorrent 的实时列表 + 暂停/恢复/删除。
 *
 * 数据全部走后端 /api/tv/downloads（客户端不持有 qB 密码，也不假设电视能
 * 访问 qB 的端口）。每 5 秒刷新一次——一屏 6 条，刷新的成本可以忽略。
 */
@Composable
fun DownloadsScreen(
    baseUrl: String,
    entryRequester: FocusRequester? = null,
    railEntry: FocusRequester = FocusRequester(),
    onPlay: (DownloadItem) -> Unit = {},
) {
    var items by remember { mutableStateOf<List<DownloadItem>>(emptyList()) }
    var online by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reloadTick by remember { mutableStateOf(0) }
    var pendingDelete by remember { mutableStateOf<DownloadItem?>(null) }
    var notice by remember { mutableStateOf("") }

    val firstCard = remember { FocusRequester() }
    // 删除后用来"接住"焦点的那一条（见下面 restoreReq 的说明）
    val restoreReq = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 首次进入只把焦点放到第一条一次。以前是 items.size 一变就重新聚焦，
    // 于是删掉一条（列表少一条）或者轮询时多出一条，焦点都被拽回第一行。
    var didInitialFocus by remember { mutableStateOf(false) }
    // 删除后要聚焦的行号 + 被删掉的 hash。等列表里真的没有这条了再动手，
    // 否则会先聚焦到那条即将消失的行上，行一没了焦点就丢了。
    var pendingFocusIndex by remember { mutableStateOf(-1) }
    var pendingGoneHash by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        Api.downloads(baseUrl).fold(
            onSuccess = {
                items = it.items
                online = it.online
                error = it.error.takeIf { e -> e.isNotBlank() }
            },
            onFailure = {
                // 这里失败多半不是 qB 的问题，而是这台电视根本连不上后端
                // （宿主机 Docker 没起来时最典型），提示要说清楚
                online = false
                error = "连不上服务器：${it.message}"
            },
        )
        loading = false
    }

    LaunchedEffect(baseUrl) { load() }
    // 轮询：速度要动起来；列表为空时也照样轮询，否则刚"稍后观看"的任务不出现
    LaunchedEffect(baseUrl, reloadTick) {
        while (true) {
            delay(5000)
            load()
        }
    }
    // 首次进入把焦点放到第一条，遥控器可以直接操作（只做一次）
    LaunchedEffect(loading, items.size) {
        if (!didInitialFocus && !loading && items.isNotEmpty()) {
            didInitialFocus = true
            firstCard.requestFocusWithRetry()
        }
    }

    // 删除完成后把焦点交给"顶上来的那一条"：删中间的就落到原来位置的下一条，
    // 删最后一条就落到上一条，删空了就回左侧菜单。
    LaunchedEffect(items, pendingGoneHash) {
        val gone = pendingGoneHash ?: return@LaunchedEffect
        // 列表还没刷新（那条还在），等下一轮
        if (items.any { it.hash == gone }) return@LaunchedEffect
        val target = pendingFocusIndex
        val ok = when {
            items.isEmpty() -> railEntry.requestFocusWithRetry()
            target <= 0 -> (entryRequester ?: firstCard).requestFocusWithRetry()
            else -> restoreReq.requestFocusWithRetry()
        }
        // 极端情况（这期间列表又被别处改过，要落焦的行已经不存在了）：
        // 至少别把焦点丢了，退回第一行
        if (!ok && items.isNotEmpty()) {
            (entryRequester ?: firstCard).requestFocusWithRetry()
        }
        pendingGoneHash = null
        pendingFocusIndex = -1
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, end = 48.dp, top = 44.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("下载", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            !online -> error ?: "qBittorrent 未连接"
                            items.isEmpty() -> "qBittorrent · 还没有任务"
                            else -> buildString {
                                append("qBittorrent · ${items.size} 个任务 · 点条目直接播放")
                                val down = items.sumOf { it.dlspeed }
                                val up = items.sumOf { it.upspeed }
                                if (down > 0) append("   ↓ ${speedText(down)}")
                                if (up > 0) append("   ↑ ${speedText(up)}")
                            }
                        },
                        color = TvptTextSecondary,
                        fontSize = 14.sp,
                    )
                }
                GhostButton("刷新") {
                    scope.launch {
                        loading = true
                        load()
                        notice = ""
                    }
                }
            }

            if (notice.isNotBlank()) {
                Text(
                    notice,
                    color = Color(0xFF7BD88F),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 48.dp, bottom = 6.dp),
                )
            }

            when {
                loading -> CenterHint("正在读取 qBittorrent…")
                !online -> CenterHint(error ?: "连不上 qBittorrent")
                items.isEmpty() -> CenterHint("还没有下载任务。在片源列表里选「稍后观看」就会出现在这里。")
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(items, key = { _, it -> it.hash }) { index, item ->
                        DownloadRow(
                            item = item,
                            // 第一行同时挂 entryRequester：否则从导航栏按右键，
                            // 焦点会落到行尾的「暂停」按钮上（一点就把下载停了）。
                            // 删除后要落焦的那一行挂 restoreReq（一个节点上只能挂
                            // 一个 FocusRequester，所以第 0 行优先走 entryRequester）。
                            modifier = when {
                                index == 0 -> Modifier.focusRequester(entryRequester ?: firstCard)
                                index == pendingFocusIndex -> Modifier.focusRequester(restoreReq)
                                else -> Modifier
                            },
                            // 点条目 = 直接播（后端用 infohash 重新解析播放直链，
                            // 没下完的也能边下边播）；暂停/删除都在右侧单独的按钮上。
                            onPlay = { onPlay(item) },
                            onToggle = {
                                scope.launch {
                                    val action = if (item.paused) "resume" else "pause"
                                    Api.downloadAction(baseUrl, item.hash, action).fold(
                                        onSuccess = {
                                            notice = if (action == "pause") {
                                                "已暂停：${item.name.take(30)}"
                                            } else {
                                                "已继续下载"
                                            }
                                            load()
                                        },
                                        onFailure = { notice = "操作失败：${it.message}" },
                                    )
                                }
                            },
                            onDelete = { pendingDelete = item },
                        )
                    }
                }
            }
        }

        pendingDelete?.let { target ->
            DeleteConfirm(
                item = target,
                onCancel = { pendingDelete = null },
                onDelete = {
                    pendingDelete = null
                    // 删完列表少一条：原来位置上的下一条会顶上来；删的是最后一条
                    // 就落到上一条（新列表的最后一条）
                    val idx = items.indexOfFirst { it.hash == target.hash }
                    pendingFocusIndex = if (idx < 0) {
                        0
                    } else {
                        idx.coerceAtMost((items.size - 2).coerceAtLeast(0))
                    }
                    pendingGoneHash = target.hash
                    scope.launch {
                        // 默认策略：种子和文件一起删（设置里没有"保留文件"的选项，
                        // 二次确认只留两个按钮，别让人在电视上做三道选择题）。
                        Api.downloadAction(
                            baseUrl, target.hash, "delete", deleteFiles = true,
                        ).fold(
                            onSuccess = {
                                notice = "已删除种子和文件：${target.name.take(28)}"
                                reloadTick++
                                load()
                            },
                            onFailure = { notice = "删除失败：${it.message}" },
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = TvptTextSecondary, fontSize = 16.sp)
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().height(96.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FocusSurface(
            onClick = onPlay,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            shape = shape,
            focusedScale = 1.01f,
            ringWidth = 2.dp,
            ringInset = 2.dp,
        ) { focused ->
            Row(
                Modifier
                    .fillMaxSize()
                    .background(if (focused) Color(0xFF23232B) else TvptSurfaceHigh, shape),
            ) {
                // 左侧状态条：绿色=正在下，灰色=暂停，紫色=已下完在做种
                Box(
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(
                            when {
                                item.paused -> TvptTextTertiary
                                item.finished -> Color(0xFF7B5CFF)
                                else -> Color(0xFF4FC17A)
                            }
                        ),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        item.name,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.site.isNotBlank()) {
                            Text(
                                item.site,
                                color = Color(0xFFB9A8FF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .background(Color(0x33FFFFFF), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            stateText(item),
                            color = if (item.paused) TvptTextTertiary else TvptTextSecondary,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("·", color = TvptTextTertiary, fontSize = 12.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(byteText(item.size), color = TvptTextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.width(10.dp))
                        Text("·", color = TvptTextTertiary, fontSize = 12.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${item.percent}%",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.weight(1f))
                        if (item.dlspeed > 0) {
                            Text("↓ ${speedText(item.dlspeed)}", color = Color(0xFF4FC17A), fontSize = 12.sp)
                            Spacer(Modifier.width(10.dp))
                        }
                        if (item.upspeed > 0) {
                            Text("↑ ${speedText(item.upspeed)}", color = TvptTextSecondary, fontSize = 12.sp)
                            Spacer(Modifier.width(10.dp))
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0x33FFFFFF)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(item.progress.coerceIn(0.0, 1.0).toFloat())
                                .fillMaxHeight()
                                .background(
                                    if (item.finished) Color(0xFF7B5CFF) else Color.White.copy(alpha = 0.85f)
                                ),
                        )
                    }
                }
            }
        }

        ActionPill(
            label = if (item.paused) "继续" else "暂停",
            onClick = onToggle,
            modifier = Modifier.width(72.dp).fillMaxHeight(),
        )
        ActionPill(
            label = "删除",
            danger = true,
            onClick = onDelete,
            modifier = Modifier.width(72.dp).fillMaxHeight(),
        )
    }
}

@Composable
private fun ActionPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val shape = RoundedCornerShape(12.dp)
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        focusedScale = 1.05f,
        ringWidth = 2.dp,
        ringInset = 2.dp,
    ) { focused ->
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    when {
                        focused -> if (danger) Color(0xFFE2564A) else Color.White
                        else -> TvptSurfaceHigh
                    },
                    shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = when {
                    focused -> if (danger) Color.White else Color.Black
                    danger -> Color(0xFFE29A94)
                    else -> Color.White
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun DeleteConfirm(
    item: DownloadItem,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocus.requestFocusWithRetry() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xC0000000))
            // 覆盖层要把返回键吃掉，不然会漏到下面的列表里
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Back) {
                    onCancel()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(520.dp)
                .background(Color(0xF2101014), RoundedCornerShape(18.dp))
                .padding(28.dp),
        ) {
            Text("删除任务", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                item.name,
                color = TvptTextSecondary,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text("会同时删除已经下载的文件。", color = Color(0xFFE29A94), fontSize = 12.sp)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton("取消", focusRequester = cancelFocus, onClick = onCancel)
                ActionPill(
                    label = "删除",
                    danger = true,
                    onClick = onDelete,
                    modifier = Modifier.width(110.dp).height(44.dp),
                )
            }
        }
    }
}

private fun stateText(item: DownloadItem): String = when {
    item.paused -> "已暂停"
    item.finished -> "已下载完"
    item.state.startsWith("stalled") -> "等待连接"
    item.state.startsWith("meta") -> "解析中"
    else -> "下载中"
}

private fun byteText(bytes: Long): String {
    if (bytes <= 0) return "—"
    val gb = bytes / 1073741824.0
    if (gb >= 1) return String.format("%.1f GB", gb)
    val mb = bytes / 1048576.0
    if (mb >= 1) return String.format("%.0f MB", mb)
    return "${bytes / 1024} KB"
}

private fun speedText(bps: Long): String {
    val kb = bps / 1024.0
    if (kb >= 1024) return String.format("%.1f MB/s", kb / 1024)
    return String.format("%.0f KB/s", kb)
}
