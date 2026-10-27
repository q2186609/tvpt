package tv.tvpt.client.ui.wallpaper

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 幻灯片里的一张：剧照 + 片名（+ 年份/评分）。 */
data class WallpaperFrame(
    val imageUrl: String,
    val title: String,
    val subtitle: String = "",
)

private const val SLIDE_MS = 9_000L

/**
 * 待机幻灯片（动态壁纸）。
 *
 * 电视放着不动时不该一直停在一个静态页面上：这里把刚拉到的剧照一张张铺满
 * 屏幕，配一点极慢的推镜 + 交叉淡入，左上角是时钟、右下角是片名。任何按键
 * 都会立刻退回原来的界面（按键在 AppRoot 那层就被吃掉了，不会漏给下面的
 * 焦点控件）。
 */
@Composable
fun BackdropWallpaper(
    frames: List<WallpaperFrame>,
    modifier: Modifier = Modifier,
) {
    if (frames.isEmpty()) return

    var index by remember { mutableStateOf(0) }
    LaunchedEffect(frames.size) {
        while (true) {
            delay(SLIDE_MS)
            index = (index + 1) % frames.size
        }
    }

    val frame = frames[index % frames.size]
    // 每张图 9 秒内从 1.0 缓慢推到 1.06：静态图也有"呼吸"，又不会晃眼
    val zoom by animateFloatAsState(
        targetValue = 1.06f,
        animationSpec = tween(durationMillis = SLIDE_MS.toInt(), easing = LinearEasing),
        label = "wallpaperZoom",
    )

    Box(modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(targetState = index, animationSpec = tween(1200), label = "wallpaperFade") { i ->
            val f = frames[i % frames.size]
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(f.imageUrl)
                    .crossfade(false)
                    .size(1920, 1080)
                    .build(),
                contentDescription = f.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                    },
            )
        }
        // 压暗：字幕/时钟要压得住，画面本身也别太亮（晚上看眼睛疼）
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xB3000000),
                            Color(0x33000000),
                            Color(0x33000000),
                            Color(0xCC000000),
                        )
                    )
                )
        )

        Clock(Modifier.align(Alignment.TopStart).padding(start = 56.dp, top = 44.dp))

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 56.dp, end = 56.dp, bottom = 48.dp),
        ) {
            Text(
                frame.title,
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (frame.subtitle.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(frame.subtitle, color = Color(0xCCFFFFFF), fontSize = 15.sp)
            }
        }

        // 右下角：幻灯片进度点，最多画 8 个，多了就画成 N/M
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = 52.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val total = frames.size
            val shown = minOf(total, 8)
            repeat(shown) { i ->
                Box(
                    Modifier
                        .width(if (i == index % shown) 18.dp else 6.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            if (i == index % shown) Color.White else Color(0x66FFFFFF)
                        )
                )
            }
            if (total > shown) {
                Spacer(Modifier.width(6.dp))
                Text("${index + 1}/$total", color = Color(0x99FFFFFF), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Clock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            // 每 10 秒刷一次就够：分钟跳变最晚晚 10 秒，肉眼看不出
            delay(10_000)
        }
    }
    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)) }
    val date = remember(now) { SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date(now)) }

    Column(modifier) {
        Text(
            time,
            color = Color.White,
            fontSize = 54.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(date, color = Color(0xB3FFFFFF), fontSize = 15.sp)
    }
}
