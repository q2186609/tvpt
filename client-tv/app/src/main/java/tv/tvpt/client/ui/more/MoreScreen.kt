package tv.tvpt.client.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.tvpt.client.data.Api
import tv.tvpt.client.data.MediaItem
import tv.tvpt.client.ui.components.PosterCard
import tv.tvpt.client.ui.components.requestFocusWithRetry
import tv.tvpt.client.ui.theme.TvptTextSecondary
import tv.tvpt.client.ui.theme.TvptTextTertiary

/**
 * 首页某一行的「更多」：同一个 feed 的分页网格。
 *
 * TMDB 的 discover 一页 20 条，滚到接近底部就自动拉下一页（上限 500 页）。
 * 不做"加载更多"按钮——电视上多一次点击就是多一次找遥控器。
 */
@Composable
fun MoreScreen(
    baseUrl: String,
    lang: String,
    feed: String,
    title: String,
    onBack: () -> Unit,
    onOpen: (MediaItem) -> Unit,
) {
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var totalPages by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 已经请求过的页码：预取和"滚动触底"可能同时想拉同一页，用它去重
    val requested = remember { mutableSetOf<Int>() }
    // 首屏只落一次焦点（换分类时重置）
    var didInitialFocus by remember { mutableStateOf(false) }

    val gridState = rememberLazyGridState()
    val firstCard = remember { FocusRequester() }

    suspend fun loadPage(target: Int) {
        if (target in requested) return
        if (totalPages > 0 && target > totalPages) return
        requested.add(target)
        loading = true
        Api.listPage(baseUrl, feed, lang, target).fold(
            onSuccess = { resp ->
                // 跨页去重：TMDB 分页偶有重复条目，重复 key 会让 LazyGrid 崩
                val seen = items.mapTo(HashSet()) { "${it.type}-${it.tmdbId}" }
                val fresh = resp.items.filter { seen.add("${it.type}-${it.tmdbId}") }
                items = items + fresh
                totalPages = resp.totalPages
                page = maxOf(page, target)
                error = null
            },
            onFailure = { error = it.message },
        )
        loading = false
    }

    /** 预取：保证"当前页之后至少还有 3 页"已经在手上。 */
    suspend fun prefetch(from: Int) {
        var p = from
        var guard = 0
        while (guard < 3 && (totalPages == 0 || p <= totalPages)) {
            if (p !in requested) loadPage(p)
            p++
            guard++
        }
    }

    LaunchedEffect(baseUrl, feed) {
        items = emptyList()
        page = 1
        didInitialFocus = false
        requested.clear()
        loadPage(1)
        prefetch(2)
    }

    // 只在第一次拿到数据时落一次焦点。以前是 items.size 一变就重新聚焦，
    // 于是后面几页预取一到（列表变长），焦点就被拽回第一张海报，往右翻着翻着
    // 就被弹回开头。
    LaunchedEffect(items.isNotEmpty()) {
        if (!didInitialFocus && items.isNotEmpty()) {
            didInitialFocus = true
            firstCard.requestFocusWithRetry()
        }
    }

    // 滚到倒数第 12 个（8 列 = 提前一行半）就继续往后预取 3 页
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf false
            items.isNotEmpty() && last >= items.size - 12
        }
    }
    LaunchedEffect(shouldLoadMore, items.size) {
        if (!shouldLoadMore || loading) return@LaunchedEffect
        if (totalPages > 0 && page >= totalPages) return@LaunchedEffect
        prefetch(page + 1)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, top = 44.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append("已加载 ${items.size} 部")
                        if (totalPages > 1) {
                            // 显示"看到第几页"，不是"预取到第几页"——预取永远领先 3 页
                            val visible = gridState.firstVisibleItemIndex / 20 + 1
                            append("　·　第 $visible / $totalPages 页")
                        }
                        append("　·　按返回键回到首页")
                    },
                    color = TvptTextSecondary,
                    fontSize = 13.sp,
                )
            }
            error?.let {
                Text("加载失败：$it", color = Color(0xFFFF6B6B), fontSize = 13.sp)
            }
        }

        if (items.isEmpty() && loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.height(40.dp),
                    color = Color.White.copy(alpha = 0.9f),
                    strokeWidth = 3.dp,
                )
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                // 8 列：原来 4 列一张海报在电视上太大（一屏才 8 部），
                // 缩到一半正好能在不挪窝的情况下扫完一整页
                columns = GridCells.Fixed(8),
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(items, key = { i, it -> "$i-${it.type}-${it.tmdbId}" }) { index, item ->
                    Box(
                        Modifier.then(
                            if (index == 0) Modifier.focusRequester(firstCard) else Modifier
                        )
                    ) {
                        PosterCard(
                            item = item,
                            imageUrl = Api.imageUrl(baseUrl, item.poster),
                            onClick = { onOpen(item) },
                            cardWidth = null,
                            compact = true,
                        )
                    }
                }
                if (loading || (totalPages > 0 && page < totalPages)) {
                    item(span = { GridItemSpan(8) }) {
                        Box(
                            Modifier.fillMaxWidth().height(56.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(24.dp),
                                    color = TvptTextTertiary,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text("继续往下翻…", color = TvptTextTertiary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
