package tv.tvpt.client.ui.streams

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import tv.tvpt.client.data.Api
import tv.tvpt.client.data.PlayRequest
import tv.tvpt.client.data.StreamItem
import tv.tvpt.client.ui.components.StreamCard
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import tv.tvpt.client.ui.components.PillButton
import tv.tvpt.client.ui.components.GhostButton
import tv.tvpt.client.ui.components.requestFocusWithRetry
import tv.tvpt.client.ui.theme.TvptTextSecondary

@Composable
fun StreamsScreen(
    baseUrl: String,
    play: PlayRequest,
    subtitle: String,
    onBack: () -> Unit,
    onPlay: (url: String, label: String) -> Unit,
    /** 「稍后观看」成功入队后回调，片库据此先记一条"待下载"。 */
    onQueued: (releaseName: String) -> Unit = {},
) {
    val type = if (play.type == "movie") "movie" else "series"
    val imdbId = play.imdbId
    val season = play.season
    val episode = play.episode
    val title = play.title
    // PlayRequest 里存的是 TMDB 相对路径，Coil 加载相对路径会失败（背景纯黑）
    val backdropUrl = Api.imageUrl(baseUrl, play.backdrop)
    BackHandler { onBack() }

    var streams by remember { mutableStateOf<List<StreamItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableStateOf(0) }
    // 点片源先弹「播放 / 稍后观看」，不再一点就直接进播放器
    var pending by remember { mutableStateOf<StreamItem?>(null) }
    var notice by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val firstCard = remember { androidx.compose.ui.focus.FocusRequester() }
    val retryFocus = remember { androidx.compose.ui.focus.FocusRequester() }

    // 片源到位后把焦点放到第一条，用户按确认就能播；一条都没有时焦点落到
    // 「重新搜索」，否则整屏没有可聚焦节点，遥控器等于失灵
    LaunchedEffect(streams, error, loading) {
        if (loading) return@LaunchedEffect
        if (streams.isNotEmpty()) {
            firstCard.requestFocusWithRetry()
        } else if (error != null) {
            retryFocus.requestFocusWithRetry()
        }
    }

    LaunchedEffect(imdbId, season, episode, attempt) {
        loading = true
        Api.streams(baseUrl, type, imdbId, season, episode).fold(
            onSuccess = {
                streams = it
                if (it.isEmpty()) error = "这个站点暂时没有可用片源"
            },
            onFailure = { error = it.message },
        )
        loading = false
    }

    Box(Modifier.fillMaxSize()) {
        // 背景沿用详情页那张剧照，整页保持连贯，不会出现纯黑
        if (backdropUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(backdropUrl).crossfade(200).size(1600, 900).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xF20A0A0C))
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 36.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column {
                    Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(subtitle, color = TvptTextSecondary, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            loading -> "正在搜索片源…"
                            error == "这个站点暂时没有可用片源" -> "这个站点没有这个片源的资源，换一部试试"
                            error != null -> "出错：$error"
                            else -> "共 ${streams.size} 个片源，按确认开始播放"
                        },
                        color = if (error != null) Color(0xFFFF6B6B) else TvptTextSecondary,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }

            itemsIndexed(streams, key = { i, s -> "$i-${s.url}" }) { _, s ->
                StreamCard(
                    stream = s,
                    modifier = if (s === streams.firstOrNull()) {
                        androidx.compose.ui.Modifier.focusRequester(firstCard)
                    } else {
                        androidx.compose.ui.Modifier
                    },
                    onClick = { pending = s },
                )
            }

            if (error != null && !loading) {
                item {
                    Spacer(Modifier.height(6.dp))
                    GhostButton("重新搜索", focusRequester = retryFocus) {
                        error = null
                        streams = emptyList()
                        attempt++
                    }
                }
            }
        }

        pending?.let { target ->
            StreamActions(
                stream = target,
                notice = notice,
                onNotice = { notice = it },
                onDismiss = { pending = null; notice = "" },
                onPlay = {
                    pending = null
                    // 直链跟着当前服务器地址走（见 Api.sameOrigin 的注释）
                    onPlay(
                        Api.sameOrigin(baseUrl, target.url),
                        target.title.lineSequence().firstOrNull().orEmpty(),
                    )
                },
                onLater = {
                    scope.launch {
                        // 后端立刻返回 202（解析在后台跑并重试），所以这里的
                        // "已加入" 是真话：种子很快就会出现在下载列表里。
                        val release = target.title.lineSequence().firstOrNull().orEmpty()
                        Api.queueDownload(baseUrl, target.url, title).fold(
                            onSuccess = {
                                notice = "已加入下载队列，几十秒内会出现在右侧「下载」里"
                                onQueued(release)
                            },
                            onFailure = { notice = "加入下载失败：${it.message}" },
                        )
                    }
                },
            )
        }
    }
}

/**
 * 片源操作条：立即播放 / 稍后观看（加入下载）。
 *
 * 之前点一条片源就直接进播放器，"先下着待会看"没有入口。这里做成底部弹层，
 * 默认焦点在「立即播放」，向下就到「稍后观看」。
 */
@Composable
private fun StreamActions(
    stream: StreamItem,
    notice: String,
    onNotice: (String) -> Unit,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onLater: () -> Unit,
) {
    val playFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { playFocus.requestFocusWithRetry() }

    Box(Modifier.fillMaxSize().background(Color(0xB3000000))) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xF2101014), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .padding(horizontal = 48.dp, vertical = 26.dp),
        ) {
            Text(
                stream.title.lineSequence().firstOrNull().orEmpty().ifBlank { "片源" },
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    if (stream.resolution.isNotBlank()) append(stream.resolution).append("　·　")
                    append("做种 ${stream.seeders}　·　").append(formatSize(stream.sizeBytes))
                },
                color = TvptTextSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PillButton("▶  立即播放", focusRequester = playFocus, onClick = onPlay)
                GhostButton("↓  稍后观看（加入下载）", onClick = onLater)
                GhostButton("取消", onClick = onDismiss)
            }
            if (notice.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(notice, color = Color(0xFF7BD88F), fontSize = 13.sp)
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "体积未知"
    bytes >= 1073741824L -> String.format("%.1f GB", bytes / 1073741824.0)
    else -> String.format("%.0f MB", bytes / 1048576.0)
}
