package tv.tvpt.client.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import tv.tvpt.client.data.Api
import tv.tvpt.client.data.DetailResponse
import tv.tvpt.client.data.EpisodeItem
import tv.tvpt.client.data.PlayRequest
import tv.tvpt.client.data.WatchEntry
import tv.tvpt.client.ui.components.EpisodeCard
import tv.tvpt.client.ui.components.FocusSurface
import tv.tvpt.client.ui.components.PillButton
import tv.tvpt.client.ui.components.requestFocusWithRetry
import tv.tvpt.client.ui.theme.TvptBackground
import tv.tvpt.client.ui.theme.TvptSurfaceHigh
import tv.tvpt.client.ui.theme.TvptTextSecondary

@Composable
fun DetailScreen(
    baseUrl: String,
    lang: String,
    type: String,
    tmdbId: Int,
    onBack: () -> Unit,
    /** 播放历史里这部片的记录（看过才有），用来把主按钮变成"继续播放"。 */
    resume: WatchEntry? = null,
    onPlay: (PlayRequest) -> Unit,
) {
    BackHandler { onBack() }

    var detail by remember { mutableStateOf<DetailResponse?>(null) }
    var episodes by remember { mutableStateOf<List<EpisodeItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    var seasonNo by remember { mutableStateOf(1) }
    var seasonError by remember { mutableStateOf<String?>(null) }
    var seasonLoading by remember { mutableStateOf(false) }
    var seasonAttempt by remember { mutableStateOf(0) }

    LaunchedEffect(type, tmdbId, lang, seasonNo, seasonAttempt) {
        // 详情与剧集并行，省掉一次串行往返
        launch {
            Api.detail(baseUrl, type, tmdbId, lang).fold(
                onSuccess = { d ->
                    detail = d
                    error = null
                    val real = d.seasons.firstOrNull()?.season
                    if (d.type == "tv" && real != null && real != seasonNo) seasonNo = real
                },
                onFailure = { error = it.message },
            )
        }
        if (type == "tv") {
            seasonLoading = true
            seasonError = null
            Api.season(baseUrl, tmdbId, seasonNo, lang).fold(
                onSuccess = { episodes = it.episodes; seasonError = null },
                onFailure = { seasonError = it.message ?: "未知错误" },
            )
            seasonLoading = false
        }
    }

    val playButton = remember { FocusRequester() }
    val firstEpisode = remember { FocusRequester() }
    // 进页面就把焦点落在主按钮上（电影=播放，剧集=播放第 N 集）。
    // 一定要重试：首帧 detail 还没到，按钮根本不存在，直接 requestFocus 会抛
    // IllegalStateException 被吞掉，屏幕上就是"没有任何选中态"。
    LaunchedEffect(detail) {
        if (detail != null) playButton.requestFocusWithRetry()
    }

    val d = detail
    val backdrop = Api.imageUrl(baseUrl, d?.backdrop.orEmpty())
    val ctx = LocalContext.current

    Box(Modifier.fillMaxSize().background(TvptBackground)) {
        // 整页大图打底（Stremio 的详情页就是铺满做法），上面压两层渐变保证文字可读
        if (backdrop != null) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(backdrop).crossfade(200).size(1600, 900).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 左右两层压暗：只看垂直渐变的话，剧照中间那块亮招牌会顶到简介文字
            // 后面（繁花这张背景里正好有块红的"出租"灯牌），白字读起来发飘。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xB30A0A0C), Color(0x800A0A0C), Color(0xF20A0A0C)),
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xE60A0A0C), Color(0x730A0A0C), Color(0x260A0A0C)),
                        )
                    )
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 28.dp),
        ) {
            // 左边海报、右边信息（Stremio 详情页的排法）。电影只有一个「播放」
            // 按钮，这样排版右半边不会整片空着。
            Row {
                val poster = Api.imageUrl(baseUrl, d?.poster.orEmpty())
                if (poster != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx).data(poster)
                            .crossfade(180).size(360, 540).build(),
                        contentDescription = d?.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(126.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(TvptSurfaceHigh),
                    )
                    Spacer(Modifier.width(22.dp))
                }

                Column(Modifier.width(600.dp)) {
                    Text(
                        d?.title ?: "加载中…",
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        buildString {
                            d?.year?.takeIf { it > 0 }?.let { append("$it   ") }
                            d?.let { if (it.runtime > 0) append("${it.runtime} 分钟   ") }
                            d?.let { if (it.rating > 0) append("★ ${"%.1f".format(it.rating)}   ") }
                            d?.genres?.take(3)?.let { append(it.joinToString(" / ")) }
                        },
                        color = TvptTextSecondary,
                        fontSize = 15.sp,
                    )

                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("加载失败：$it", color = Color(0xFFFF6B6B), fontSize = 14.sp)
                    }

                    d?.overview?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            it.replace("\n", " "),
                            color = Color(0xFFD2D2D8),
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            maxLines = if (d.type == "movie") 5 else 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    if (d != null) {
                        if (d.type == "movie") {
                            val resumeMs = resume?.takeIf { !it.finished }?.positionMs ?: 0L
                            PillButton(
                                if (resumeMs > 60_000L) {
                                    "▶  继续播放 · 剩 ${resume!!.remainingMinutes} 分钟"
                                } else {
                                    "▶  播放"
                                },
                                focusRequester = playButton,
                            ) {
                                if (d.imdbId.isBlank()) {
                                    error = "这部片子在 TMDB 没有 IMDb 映射，暂时搜不到片源"
                                } else {
                                    onPlay(
                                        PlayRequest(
                                            type = "movie",
                                            tmdbId = d.tmdbId,
                                            imdbId = d.imdbId,
                                            title = d.title,
                                            poster = d.poster,
                                            backdrop = d.backdrop,
                                        )
                                    )
                                }
                            }
                        } else {
                            // 剧集也给一个主按钮：不用先在下排选集里找第 1 集，
                            // 遥控器上少按几下；顺带把右半边的空白填掉。
                            // 看过这一季就变成"继续播放第 N 集"（一集只能看一集，
                            // 这是电视上最常用的入口）。
                            val firstEp = episodes.firstOrNull()?.episode ?: 1
                            val firstEpName = episodes.firstOrNull()?.name?.trim().orEmpty()
                            val resumeEp = resume
                                ?.takeIf { !it.finished && it.season == seasonNo && it.episode > 0 }
                            PillButton(
                                if (resumeEp != null) {
                                    "▶  继续播放第 ${resumeEp.episode} 集 · 剩 ${resumeEp.remainingMinutes} 分钟"
                                } else {
                                    "▶  播放第 $firstEp 集"
                                },
                                focusRequester = playButton,
                            ) {
                                if (d.imdbId.isBlank()) {
                                    error = "这部片子在 TMDB 没有 IMDb 映射，暂时搜不到片源"
                                } else {
                                    val ep = resumeEp?.episode ?: firstEp
                                    onPlay(
                                        PlayRequest(
                                            type = "tv",
                                            tmdbId = d.tmdbId,
                                            imdbId = d.imdbId,
                                            title = d.title,
                                            poster = d.poster,
                                            backdrop = d.backdrop,
                                            season = d.seasons.firstOrNull()?.season ?: 1,
                                            episode = ep,
                                            episodeName = if (ep == firstEp) firstEpName else resumeEp?.episodeName.orEmpty(),
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (d != null && d.type == "tv") {
                // 多季的剧要有季切换，否则永远停在第一季
                val seasons = d.seasons.filter { it.season > 0 }
                if (seasons.size > 1) {
                    Spacer(Modifier.height(16.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(seasons, key = { i, s -> "$i-${s.season}" }) { _, s ->
                            SeasonChip(
                                label = "第 ${s.season} 季",
                                selected = s.season == seasonNo,
                            ) { seasonNo = s.season }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            episodes.isNotEmpty() -> "选集 · 共 ${episodes.size} 集"
                            seasonLoading -> "正在加载剧集…"
                            seasonError != null -> "剧集加载失败：$seasonError"
                            else -> "暂无剧集信息"
                        },
                        color = if (seasonError != null && episodes.isEmpty()) {
                            Color(0xFFFF6B6B)
                        } else {
                            Color.White
                        },
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (seasonError != null && episodes.isEmpty()) {
                        Spacer(Modifier.width(14.dp))
                        PillButton("重试", compact = true) { seasonAttempt++ }
                    }
                }
                Spacer(Modifier.height(6.dp))
                LazyRow(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(episodes, key = { i, ep -> "$i-${ep.episode}" }) { _, ep ->
                        EpisodeCard(
                            ep = ep,
                            imageUrl = Api.imageUrl(baseUrl, ep.still),
                            modifier = if (ep === episodes.firstOrNull()) {
                                Modifier.focusRequester(firstEpisode)
                            } else {
                                Modifier
                            },
                            onClick = {
                                if (d.imdbId.isBlank()) {
                                    error = "这部片子在 TMDB 没有 IMDb 映射，暂时搜不到片源"
                                } else {
                                    onPlay(
                                        PlayRequest(
                                            type = "tv",
                                            tmdbId = d.tmdbId,
                                            imdbId = d.imdbId,
                                            title = d.title,
                                            poster = d.poster,
                                            backdrop = d.backdrop,
                                            season = d.seasons.firstOrNull()?.season ?: 1,
                                            episode = ep.episode,
                                            episodeName = ep.name,
                                        )
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 季切换小胶囊：当前季用白底黑字，其余深底灰字。 */
@Composable
private fun SeasonChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    FocusSurface(
        onClick = onClick,
        shape = shape,
        focusedScale = 1.04f,
        ringInset = 2.dp,
    ) { focused ->
        Box(
            Modifier
                .background(
                    when {
                        focused -> Color.White
                        selected -> Color.White.copy(alpha = 0.22f)
                        else -> Color.White.copy(alpha = 0.08f)
                    },
                    shape,
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                label,
                color = if (focused || selected) Color.White else TvptTextSecondary,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
