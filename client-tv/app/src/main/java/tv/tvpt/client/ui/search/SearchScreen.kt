package tv.tvpt.client.ui.search

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import tv.tvpt.client.data.Api
import tv.tvpt.client.data.MediaItem
import tv.tvpt.client.ui.components.FocusSurface
import tv.tvpt.client.ui.components.PosterCard
import tv.tvpt.client.ui.theme.TvptPrimary
import tv.tvpt.client.ui.theme.TvptSurfaceHigh
import tv.tvpt.client.ui.theme.TvptTextSecondary
import tv.tvpt.client.ui.theme.TvptTextTertiary

/**
 * 搜索页：左边一块自绘虚拟键盘，右边分页展示结果。
 *
 * 为什么不用输入框：电视上没有中文输入法，遥控器点进 TextField 只会把系统
 * IME 顶出来（还抢 D-pad），D-pad 用户实际上没法打字。这里改成焦点键盘：
 * 每按一个字母就把查询词追加一位并发起一次搜索（去抖 260ms），TMDB 对中文片
 * 名的拼音（xianni / xian ni）本来就是可搜的，所以"输入即搜"直接可用。
 *
 * 结果区是横向网格：行数固定 4 行、列数随结果增长；遥控器一直往右按就是
 * 一页一页往后翻。翻页不靠"加载更多"按钮，靠近末尾会自动提前拉取后 3 页。
 */
@Composable
fun SearchScreen(
    baseUrl: String,
    lang: String,
    initialQuery: String? = null,
    onQueryConsumed: () -> Unit = {},
    entryRequester: FocusRequester? = null,
    railEntry: FocusRequester = FocusRequester(),
    onOpen: (MediaItem) -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery.orEmpty()) }
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var totalPages by remember { mutableStateOf(0) }
    var nextPage by remember { mutableStateOf(1) }
    var status by remember { mutableStateOf("拼音全拼或首字母都行：kq = 空枪、xianni = 仙逆") }
    var loading by remember { mutableStateOf(false) }
    // 每次改查询词自增：旧的请求回来时对不上就丢掉，避免"打完 dumpling 显示
    // 的是 du 的结果"
    var session by remember { mutableStateOf(0) }

    val gridState = rememberLazyGridState()
    val firstCard = remember { FocusRequester() }

    suspend fun fetchPage(page: Int, gen: Int): Boolean {
        if (totalPages > 0 && page > totalPages) return false
        val res = Api.searchPage(baseUrl, query.trim(), lang, page)
        if (gen != session) return false
        return res.fold(
            onSuccess = { resp ->
                totalPages = resp.totalPages
                val seen = items.mapTo(HashSet()) { "${it.type}-${it.tmdbId}" }
                val fresh = resp.items.filter { seen.add("${it.type}-${it.tmdbId}") }
                if (fresh.isNotEmpty()) items = items + fresh
                nextPage = maxOf(nextPage, page + 1)
                true
            },
            onFailure = { err ->
                if (page == 1) {
                    items = emptyList()
                    status = "搜索失败：${err.message}"
                }
                false
            },
        )
    }

    // 输入即搜：去抖后重置结果，先拉第 1 页，再把后 3 页预取进来
    LaunchedEffect(query, baseUrl, lang) {
        val gen = ++session
        val q = query.trim()
        if (q.isEmpty()) {
            items = emptyList()
            totalPages = 0
            nextPage = 1
            status = "拼音全拼或首字母都行：kq = 空枪、xianni = 仙逆"
            loading = false
            return@LaunchedEffect
        }
        delay(260)
        if (gen != session) return@LaunchedEffect
        loading = true
        items = emptyList()
        totalPages = 0
        nextPage = 1
        status = "搜索中…"
        var page = 1
        while (page <= 4 && (totalPages == 0 || page <= totalPages)) {
            if (!fetchPage(page, gen)) break
            page++
        }
        if (gen != session) return@LaunchedEffect
        loading = false
        status = when {
            items.isEmpty() -> "没有找到「$q」"
            totalPages > 1 -> "共 ${totalPages * 20} 个结果 · ${totalPages} 页 · 往后按右键继续翻"
            else -> "共 ${items.size} 个结果"
        }
        // 第一次出结果时把焦点放到第一张海报上？不——用户还在打字，
        // 焦点留在键盘上更顺手，按右键自己过去。
    }

    // 滑到接近末尾 → 再往后预取 3 页（保持"永远有货"）
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            items.isNotEmpty() && last >= items.size - 6
        }
    }
    LaunchedEffect(nearEnd, items.size, nextPage) {
        if (!nearEnd || loading) return@LaunchedEffect
        val gen = session
        var page = nextPage
        var guard = 0
        while (guard < 3 && gen == session && (totalPages == 0 || page <= totalPages)) {
            if (!fetchPage(page, gen)) break
            page++
            guard++
        }
    }

    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) onQueryConsumed()
    }

    Row(
        Modifier
            .fillMaxSize()
            .padding(start = 48.dp, end = 32.dp, top = 30.dp)
            // 插了 USB 键盘、或者遥控器自带的语音转文字往输入框里塞字符时，
            // 这里一并接住：字母/数字直接追加（电视上没有软键盘可用）。
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val ch = ev.nativeKeyEvent.unicodeChar.toChar()
                if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == ' ') {
                    if (query.length < 40) query += ch.lowercaseChar()
                    true
                } else {
                    false
                }
            }
    ) {
        // ---------- 左：虚拟键盘 ----------
        Column(Modifier.width(372.dp)) {
            Text("搜索", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(12.dp))
            QueryBox(query)
            Spacer(Modifier.height(12.dp))
            VirtualKeyboard(
                entryRequester = entryRequester,
                railEntry = railEntry,
                onKey = { ch ->
                    if (query.length < 40) query += ch
                },
                onBackspace = { query = query.dropLast(1) },
                onClear = { query = "" },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                status,
                color = if (status.startsWith("搜索失败")) Color(0xFFFF6B6B) else TvptTextSecondary,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text("左/右键：键盘与结果之间切换　确认：输入　kq=空枪", color = TvptTextTertiary, fontSize = 11.sp)
        }

        Spacer(Modifier.width(28.dp))

        // ---------- 右：结果分页 ----------
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (query.isBlank()) "搜索结果" else "「${query}」的结果",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (totalPages > 1) {
                    Text(
                        "第 ${currentPage(gridState, items.size)} / $totalPages 页",
                        color = TvptTextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (loading) "搜索中…" else "左边打字，结果会出现在这里",
                        color = TvptTextTertiary,
                        fontSize = 14.sp,
                    )
                }
            } else {
                LazyHorizontalGrid(
                    state = gridState,
                    rows = GridCells.Fixed(2),
                    contentPadding = PaddingValues(bottom = 28.dp, end = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(items, key = { i, it -> "$i-${it.type}-${it.tmdbId}" }) { index, item ->
                        PosterCard(
                            item = item,
                            imageUrl = Api.imageUrl(baseUrl, item.poster),
                            onClick = { onOpen(item) },
                            modifier = if (index == 0) Modifier.focusRequester(firstCard) else Modifier,
                            cardWidth = 122,
                        )
                    }
                }
            }
        }
    }
}

/** 当前看到第几页（按第一个可见条目算，一页 20 条）。 */
private fun currentPage(state: androidx.compose.foundation.lazy.grid.LazyGridState, size: Int): Int {
    val first = state.firstVisibleItemIndex
    return if (size <= 0) 1 else (first / 20) + 1
}

/** 查询词展示框：只是个"显示屏"，不接收焦点、不弹 IME。 */
@Composable
private fun QueryBox(query: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(TvptSurfaceHigh, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (query.isBlank()) "首字母 / 拼音 / 英文名" else query.uppercase(),
            color = if (query.isBlank()) TvptTextTertiary else Color.White,
            fontSize = 20.sp,
            fontWeight = if (query.isBlank()) FontWeight.Normal else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 闪烁光标用静态竖条代替：动画在这里没有信息量，反而分散注意力
        if (query.isNotBlank()) {
            Box(Modifier.width(2.dp).height(22.dp).background(TvptPrimary))
        }
    }
}

/**
 * 6 列的字母键盘：A-Z + 删除 + 清空。
 *
 * 每按一个键就发一次搜索（去抖在调用方），所以不再需要"搜索"按钮。
 */
@Composable
private fun VirtualKeyboard(
    entryRequester: FocusRequester?,
    railEntry: FocusRequester,
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
) {
    val rows = listOf(
        "ABCDEF",
        "GHIJKL",
        "MNOPQR",
        "STUVWX",
        "YZ",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { colIndex, ch ->
                    val first = rowIndex == 0 && colIndex == 0
                    KeyCap(
                        label = ch.toString(),
                        modifier = Modifier
                            .then(
                                if (first && entryRequester != null) Modifier.focusRequester(entryRequester)
                                else Modifier
                            )
                            // 最左一列往左回到导航栏：两列之间没有 y 轴重叠的节点时
                            // Compose 的 2D 焦点搜索找不到落点，焦点会卡在键盘里
                            .then(if (colIndex == 0) Modifier.focusProperties { left = railEntry } else Modifier),
                        onClick = { onKey(ch.toString()) },
                    )
                }
                // 最后一行补两个功能键
                if (rowIndex == rows.lastIndex) {
                    KeyCap("删除", wide = 1.6f, onClick = onBackspace)
                    KeyCap("清空", wide = 1.6f, onClick = onClear)
                }
            }
        }
    }
}

@Composable
private fun KeyCap(
    label: String,
    modifier: Modifier = Modifier,
    wide: Float = 1f,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(9.dp)
    FocusSurface(
        onClick = onClick,
        modifier = modifier.width((48 * wide).dp).height(44.dp),
        shape = shape,
        focusedScale = 1.08f,
        ringWidth = 2.dp,
        ringInset = 1.dp,
    ) { focused ->
        Box(
            Modifier
                .fillMaxSize()
                .background(if (focused) Color.White else Color(0xFF202027), shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (focused) Color.Black else Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
