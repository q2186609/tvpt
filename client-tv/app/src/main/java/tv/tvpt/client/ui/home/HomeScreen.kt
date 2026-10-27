package tv.tvpt.client.ui.home

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.tvpt.client.data.Api
import tv.tvpt.client.data.MediaItem
import tv.tvpt.client.ui.components.FocusSurface
import tv.tvpt.client.ui.components.PillButton
import tv.tvpt.client.ui.components.PosterCard
import tv.tvpt.client.ui.theme.TvptBackground
import tv.tvpt.client.ui.theme.TvptTextSecondary

/** 首页的一条分类行。feed 名由 tvapi 定义（见 server/tvapi 的 feedFor）。 */
private data class HomeFeed(val title: String, val feed: String)

/**
 * 行顺序就是"打开电视想先看到什么"：最热电影 → 最新电影 → 国产剧 → 美剧 →
 * 动漫 → 最热剧集。哪一行没数据就整行不显示，不留空标题。
 */
private val HOME_FEEDS = listOf(
    HomeFeed("最热电影", "hot_movie"),
    HomeFeed("最新电影", "new_movie"),
    HomeFeed("国产剧", "cn_tv"),
    HomeFeed("美剧", "us_tv"),
    HomeFeed("动漫", "anime"),
    HomeFeed("最新剧集", "new_tv"),
)

/** 焦点进入某一行时，把这一行顶到离屏幕顶部这么远的位置。 */
private val ROW_TOP_INSET = 72.dp

@Composable
fun HomeScreen(
    baseUrl: String,
    lang: String,
    entryRequester: FocusRequester? = null,
    railEntry: FocusRequester = FocusRequester(),
    onOpen: (MediaItem) -> Unit,
    /** 行尾「更多」：进该分类的分页列表。 */
    onMore: (feed: String, title: String) -> Unit = { _, _ -> },
    /** 首页数据落地后回调整屏条目：待机幻灯片拿它们的剧照当素材。 */
    onLoaded: (List<MediaItem>) -> Unit = {},
) {
    var rows by remember { mutableStateOf<List<Pair<HomeFeed, List<MediaItem>>>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    // 首屏失败要自己重试：后端/宿主机一重启（Docker 没起来那种），原来这里
    // 只拉一次，失败就永远停在"加载中…"，只能杀进程重开。
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(baseUrl, lang, attempt) {
        var tries = 0
        while (true) {
            tries++
            loading = true
            error = null
            // 六个榜单并发拉：串行的话首屏要等 6 个 TMDB 往返（国内还要过代理）
            val fetched = coroutineScope {
                HOME_FEEDS.map { f ->
                    async { f to Api.list(baseUrl, f.feed, lang).getOrDefault(emptyList()) }
                }.awaitAll()
            }
            // 跨行去重：美剧/最新剧集这种榜单重叠很高，同一张海报在首页出现两次
            // 显得很敷衍。保留第一次出现的那一行。
            val seen = HashSet<String>()
            rows = fetched.mapNotNull { (feed, items) ->
                val fresh = items.filter { seen.add("${it.type}-${it.tmdbId}") }
                if (fresh.isEmpty()) null else feed to fresh
            }
            loading = false
            if (rows.isNotEmpty()) {
                onLoaded(rows.flatMap { it.second })
                break
            }
            // 最多连着试 8 次（3 秒一次），还不行就把原因摆在屏幕上，等用户按重试
            if (tries >= 8) {
                error = "连不上服务器：检查电视网络、服务器地址，以及宿主机的 Docker / 代理是否在跑"
                break
            }
            error = "连不上服务器，正在重试（$tries/8）…"
            delay(3000)
        }
    }

    val hero = rows.firstOrNull()?.second?.firstOrNull()

    // 焦点进到哪一行，就把那一行滚到固定位置。
    // 不这么做的话 Compose 只会"最小滚动"保证卡片可见，行会贴在屏幕最底部，
    // 卡片的标题被屏幕边缘切掉（实测就是这个表现）。
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val topInsetPx = with(LocalDensity.current) { ROW_TOP_INSET.roundToPx() }
    var focusedRow by remember { mutableStateOf(-1) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 0.dp),
    ) {
        item(key = "hero") {
            Hero(
                hero = hero,
                baseUrl = baseUrl,
                loading = loading,
                entryRequester = entryRequester,
                railEntry = railEntry,
                onFocus = {
                    if (focusedRow != -1) {
                        focusedRow = -1
                        scope.launch { listState.animateScrollToItem(0) }
                    }
                },
                onOpen = onOpen,
            )
        }

        error?.let {
            item(key = "error") {
                Column(Modifier.padding(horizontal = 48.dp, vertical = 8.dp)) {
                    Text(it, color = Color(0xFFFF6B6B), fontSize = 13.sp)
                    if (!loading) {
                        Spacer(Modifier.height(10.dp))
                        // 自动重试跑完还没成，给个能点的入口，别让人只能杀进程
                        PillButton("重新加载", compact = true) { attempt++ }
                    }
                }
            }
        }

        itemsIndexed(
            rows,
            key = { i, pair -> "row-$i-${pair.first.feed}" },
        ) { index, (feed, items) ->
            MediaRow(
                title = feed.title,
                feed = feed.feed,
                items = items,
                baseUrl = baseUrl,
                onMore = onMore,
                onRowFocus = {
                    if (focusedRow != index) {
                        focusedRow = index
                        scope.launch {
                            // Hero 占了第 0 项，行本身是 index + 1。
                            // 连续两次：Compose 自己的"把焦点滚进可见区"会和这里
                            // 抢同一个 MutatorMutex，第一次 scrollToItem 有可能被
                            // 取消（animateScrollToItem 更明显，被取消就直接抛
                            // CancellationException，滚不动）。第二次晚 80ms 落刀，
                            // 这时焦点滚动已经结束，位置才稳。
                            listState.scrollToItem(index + 1, scrollOffset = -topInsetPx)
                            delay(80)
                            listState.scrollToItem(index + 1, scrollOffset = -topInsetPx)
                        }
                    }
                },
                onOpen = onOpen,
            )
        }

        // 尾部留白：最后一行也要能被顶到 ROW_TOP_INSET，否则滚到底就不动了，
        // 最后一行的卡片标题照样被切掉。
        item(key = "tail") { Spacer(Modifier.height(120.dp)) }
    }
}

/**
 * 顶部大图 + 渐变遮罩 + 标题，和 Stremio 首页的 hero 一个结构。
 *
 * 高度按 1080p 电视（540dp 高）留的：212dp 的 hero 之后正好放下一整行
 * 海报（含标题），首屏不会把海报裁掉半张。
 */
@Composable
private fun Hero(
    hero: MediaItem?,
    baseUrl: String,
    loading: Boolean,
    entryRequester: FocusRequester?,
    railEntry: FocusRequester,
    onFocus: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    /** 行尾「更多」：进该分类的分页列表。 */
    onMore: (feed: String, title: String) -> Unit = { _, _ -> },
    /** 首页数据落地后回调整屏条目：待机幻灯片拿它们的剧照当素材。 */
    onLoaded: (List<MediaItem>) -> Unit = {},
) {
    val ctx = LocalContext.current

    Box(
        Modifier
            .fillMaxWidth()
            .height(212.dp)
            .background(
                Brush.verticalGradient(listOf(Color(0xFF1A1A22), TvptBackground))
            )
    ) {
        val backdrop = Api.imageUrl(baseUrl, hero?.backdrop.orEmpty())
        if (backdrop != null) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(backdrop).crossfade(200).size(1280, 720).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 左侧压暗，保证白字在任何海报上都读得清
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xF20A0A0C), Color(0x8C0A0A0C), Color.Transparent),
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0x660A0A0C), TvptBackground),
                    )
                )
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 48.dp, end = 48.dp, bottom = 22.dp),
        ) {
            Text(
                hero?.title ?: if (loading) "加载中…" else "tvpt",
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                buildString {
                    hero?.year?.takeIf { it > 0 }?.let { append("$it   ") }
                    hero?.rating?.takeIf { it > 0 }?.let { append("★ ${"%.1f".format(it)}   ") }
                    append(if (hero?.type == "tv") "剧集" else "电影")
                },
                color = TvptTextSecondary,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                hero?.overview?.replace("\n", " ")?.take(110)
                    ?: "直连你自己的 PT 站，点开就能播",
                color = Color(0xFFD8D8DE),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.55f),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(
                    label = "▶  查看详情",
                    onClick = { hero?.let(onOpen) },
                    focusRequester = entryRequester,
                    modifier = Modifier
                        .focusProperties { left = railEntry }
                        .onFocusChanged { if (it.isFocused) onFocus() },
                )
            }
        }
    }
}

@Composable
private fun MediaRow(
    title: String,
    feed: String,
    items: List<MediaItem>,
    baseUrl: String,
    onRowFocus: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    /** 行尾「更多」：进该分类的分页列表。 */
    onMore: (feed: String, title: String) -> Unit = { _, _ -> },
    /** 首页数据落地后回调整屏条目：待机幻灯片拿它们的剧照当素材。 */
    onLoaded: (List<MediaItem>) -> Unit = {},
) {
    if (items.isEmpty()) return
    val listState = rememberLazyListState()
    // 注意：不能在这一层用 onFocusChanged { it.hasFocus } 做"整行获得焦点"的
    // 判断——Column 本身不是焦点目标，实测拿不到回调。挂到卡片自己的
    // FocusSurface 上才可靠。
    Column(Modifier.padding(top = 18.dp)) {
        Text(
            title,
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 44.dp, bottom = 4.dp),
        )
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 38.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(items, key = { i, item -> "$i-${item.type}-${item.tmdbId}" }) { _, item ->
                PosterCard(
                    item = item,
                    imageUrl = Api.imageUrl(baseUrl, item.poster),
                    onClick = { onOpen(item) },
                    cardWidth = 124,
                    onFocusChanged = { focused ->
                        if (focused) onRowFocus()
                    },
                )
            }
            // 行尾「更多」：同一分类的分页列表。放在最后而不是行标题右侧，
            // 是因为遥控器向右划到底顺手就是它，不用回头找标题栏。
            item(key = "more") {
                MoreCard { onMore(feed, title) }
            }
        }
    }
}

/** 行尾的「更多」卡片：和海报同宽同高，避免行尾出现一个矮一截的异类。 */
@Composable
private fun MoreCard(onClick: () -> Unit) {
    val imageShape = RoundedCornerShape(8.dp)
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.width(124.dp),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.12f,
        ringWidth = 0.dp,
    ) { focused ->
        Column(Modifier.padding(6.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(imageShape)
                    .background(if (focused) Color(0xFF2C2C36) else Color(0xFF1E1E24)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "»",
                        color = if (focused) Color.White else TvptTextSecondary,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "更多",
                        color = if (focused) Color.White else TvptTextSecondary,
                        fontSize = 13.sp,
                    )
                }
                if (focused) {
                    Box(Modifier.matchParentSize().border(3.dp, Color.White, imageShape))
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                "查看全部",
                color = if (focused) Color.White else TvptTextSecondary,
                fontSize = 13.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}
