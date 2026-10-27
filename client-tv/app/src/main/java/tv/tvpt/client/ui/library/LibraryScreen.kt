package tv.tvpt.client.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import tv.tvpt.client.data.Api
import tv.tvpt.client.data.WatchEntry
import tv.tvpt.client.ui.components.FocusSurface
import tv.tvpt.client.ui.components.GhostButton
import tv.tvpt.client.ui.theme.TvptSurfaceHigh
import tv.tvpt.client.ui.theme.TvptTextSecondary

/**
 * 片库 = 继续观看。一条记录一张 16:9 卡片：剧照打底、压暗、底部标题和进度。
 *
 * 只做"最近看过的"，不做收藏/评分那套——电视上的使用路径就是"接着上次看"。
 */
@Composable
fun LibraryScreen(
    entries: List<WatchEntry>,
    baseUrl: String,
    onOpen: (WatchEntry) -> Unit,
    onClear: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 48.dp, end = 48.dp, top = 44.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("片库", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (entries.isEmpty()) {
                        "看过的片子会出现在这里，接着上次的位置播"
                    } else {
                        "继续观看 · ${entries.size} 部"
                    },
                    color = TvptTextSecondary,
                    fontSize = 14.sp,
                )
            }
            if (entries.isNotEmpty()) {
                GhostButton("清空记录", onClick = onClear)
            }
        }
        Spacer(Modifier.height(20.dp))

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有观看记录。随便点开一部片子看两分钟，这里就有了。",
                    color = TvptTextSecondary,
                    fontSize = 15.sp,
                )
            }
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(entries, key = { it.key }) { entry ->
                HistoryCard(
                    entry = entry,
                    imageUrl = Api.imageUrl(baseUrl, entry.backdrop.ifBlank { entry.poster }),
                    onClick = { onOpen(entry) },
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(
    entry: WatchEntry,
    imageUrl: String?,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val imageShape = RoundedCornerShape(10.dp)
    FocusSurface(
        onClick = onClick,
        // 网格里必须显式撑满整格：不写的话 FocusSurface 的 Box 是 wrap-content，
        // 卡片会缩成内容宽度，右边留一大片空
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        focusedScale = 1.06f,
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
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(imageUrl).crossfade(160).size(640, 360).build(),
                        contentDescription = entry.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // 底部压暗，标题和进度条才看得清（剧照本身常带标题字，压不实
                // 会和卡片上的标题叠在一起）
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0x40000000),
                                    Color(0x00000000),
                                    Color(0xB3000000),
                                    Color(0xF20A0A0C),
                                ),
                            )
                        )
                )
                if (entry.queued && entry.positionMs <= 0) {
                    // 「稍后观看」加进下载的片子，片库里也要能看见
                    Text(
                        "已加入下载",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                            .background(Color(0xCC7B5CFF), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        entry.title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            if (entry.season > 0 && entry.episode > 0) {
                                append("第 ${entry.season} 季 第 ${entry.episode} 集   ")
                            }
                            when {
                                entry.queued && entry.positionMs <= 0 -> append("已加入下载 · 待播放")
                                entry.finished -> append("已看完")
                                else -> append("剩 ${entry.remainingMinutes} 分钟")
                            }
                        },
                        color = Color(0xCCFFFFFF),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (focused) {
                    // 和其他卡片一致：3dp 白描边（画在内容之上，不会被圆角裁掉）
                    Box(
                        Modifier
                            .fillMaxSize()
                            .border(3.dp, Color.White, imageShape)
                    )
                }
            }

            Spacer(Modifier.height(7.dp))
            // 进度条：看完的显示满格灰色，没看完的是白色
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0x33FFFFFF), RoundedCornerShape(2.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(entry.progress.coerceAtLeast(0.02f))
                        .height(3.dp)
                        .background(
                            if (entry.finished) Color(0x66FFFFFF) else Color.White,
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
    }
}
