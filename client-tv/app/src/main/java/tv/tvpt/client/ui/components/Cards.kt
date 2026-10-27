package tv.tvpt.client.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import tv.tvpt.client.data.EpisodeItem
import tv.tvpt.client.data.MediaItem
import tv.tvpt.client.data.StreamItem
import tv.tvpt.client.ui.theme.TvptRing
import tv.tvpt.client.ui.theme.TvptSurfaceFocused
import tv.tvpt.client.ui.theme.TvptSurfaceHigh
import tv.tvpt.client.ui.theme.TvptTextSecondary

@Composable
private fun imageModel(url: String, widthPx: Int, heightPx: Int): ImageRequest {
    return ImageRequest.Builder(LocalContext.current)
        .data(url)
        .crossfade(160)
        .size(widthPx, heightPx)
        // 图片加载失败原来完全静默（卡片就一直是个灰块），排查时无从下手。
        // 打一条日志：失败原因（DNS/明文/404）能直接从 logcat 看到。
        .listener(
            onError = { _, result ->
                android.util.Log.w("tvpt-img", "load failed: $url -> ${result.throwable}")
            },
        )
        .build()
}

/**
 * 海报卡片：2:3 竖版。焦点态 = 放大 1.12 + 3dp 白描边 + 标题提亮，
 * 三米外也能一眼看出选中的是哪一张。
 */
@Composable
fun PosterCard(
    item: MediaItem,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Int? = 168,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    /** 小尺寸网格（更多页 8 列）用：字体和内边距一起收一档。 */
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(if (compact) 9.dp else 12.dp)
    val imageShape = RoundedCornerShape(if (compact) 6.dp else 8.dp)
    FocusSurface(
        onClick = onClick,
        modifier = if (cardWidth != null) modifier.width(cardWidth.dp) else modifier.fillMaxWidth(),
        shape = shape,
        focusedScale = if (compact) 1.08f else 1.12f,
        ringWidth = 0.dp,
        onFocusChanged = onFocusChanged,
    ) { focused ->
        Column(Modifier.padding(if (compact) 4.dp else 6.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(imageShape)
                    .background(TvptSurfaceHigh),
            ) {
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageModel(imageUrl, if (compact) 240 else 480, if (compact) 360 else 720),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("无海报", color = TvptTextSecondary, fontSize = 12.sp)
                    }
                }
                if (focused) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .border(if (compact) 2.dp else 3.dp, Color.White, imageShape)
                    )
                }
            }
            Spacer(Modifier.height(if (compact) 4.dp else 7.dp))
            Text(
                item.title,
                color = if (focused) Color.White else TvptTextSecondary,
                fontSize = if (compact) 11.sp else 13.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 剧集卡片：16:9 剧照 + 集号角标。
 *
 * TMDB 没有分集名时后端会用「第 N 集」兜底，这时卡片里的角标和下方的
 * 标题就是同一个字符串——上一版两处都画，屏幕上出现两行一样的「第 1 集」。
 * 现在只在有真实分集名时画下方标题，角标按需隐藏。
 */
@Composable
fun EpisodeCard(
    ep: EpisodeItem,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Int = 178,
) {
    val shape = RoundedCornerShape(12.dp)
    val imageShape = RoundedCornerShape(8.dp)
    val numberLabel = "第 ${ep.episode} 集"
    val title = ep.name.trim()
    val hasRealTitle = title.isNotEmpty() && title != numberLabel

    FocusSurface(
        onClick = onClick,
        modifier = modifier.width(cardWidth.dp),
        shape = shape,
        focusedScale = 1.1f,
        ringWidth = 0.dp,
    ) { focused ->
        Column(Modifier.padding(6.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(imageShape)
                    .background(TvptSurfaceHigh),
            ) {
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageModel(imageUrl, 480, 270),
                        contentDescription = numberLabel,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))
                        )
                )
                Text(
                    numberLabel,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                )
                if (focused) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .border(3.dp, Color.White, imageShape)
                    )
                }
            }
            if (hasRealTitle) {
                Spacer(Modifier.height(6.dp))
                Text(
                    title,
                    color = if (focused) Color.White else TvptTextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 片源卡片，版式对着 Stremio 的 stream 卡片做：左侧来源与分辨率，
 * 中间标题，底部做种数与体积，右侧播放三角。
 */
@Composable
fun StreamCard(
    stream: StreamItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        focusedScale = 1.012f,
        ringInset = 3.dp,
    ) { focused ->
        val bg by animateColorAsState(
            targetValue = if (focused) TvptSurfaceFocused else TvptSurfaceHigh.copy(alpha = 0.94f),
            animationSpec = tween(100),
            label = "streamBg",
        )
        Row(
            Modifier
                .background(bg, shape)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(112.dp)) {
                Text(
                    stream.resolution.ifBlank { "片源" }.uppercase(),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(stream.source.ifBlank { null }).joinToString(" · "),
                    color = TvptTextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stream.title.lineSequence().firstOrNull().orEmpty(),
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        "做种 ${stream.seeders}",
                        color = if (stream.seeders > 0) Color(0xFF7BD88F) else TvptTextSecondary,
                        fontSize = 12.sp,
                    )
                    Text(formatSize(stream.sizeBytes), color = TvptTextSecondary, fontSize = 12.sp)
                    if (stream.seasonPack) {
                        Text("整季", color = TvptTextSecondary, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            PlayGlyph(focused)
        }
    }
}

@Composable
private fun PlayGlyph(focused: Boolean) {
    Box(
        Modifier
            .width(42.dp)
            .height(42.dp)
            .background(
                if (focused) Color.White else Color.White.copy(alpha = 0.10f),
                RoundedCornerShape(21.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "▶",
            color = if (focused) Color.Black else TvptRing,
            fontSize = 13.sp,
        )
    }
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    val gb = bytes / 1_073_741_824.0
    return if (gb >= 1) String.format("%.1f GB", gb) else String.format("%.0f MB", bytes / 1_048_576.0)
}
