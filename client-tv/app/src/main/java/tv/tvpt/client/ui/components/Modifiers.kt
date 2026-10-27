package tv.tvpt.client.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import tv.tvpt.client.ui.theme.TvptTextSecondary

/**
 * 电视焦点容器：D-pad 移动 + 确认键触发，焦点态给足信号（放大 + 白描边）。
 *
 * 两个前车之鉴（上一版在这两处翻过车，别改回去）：
 *  - 焦点态不能用 `Modifier.composed {}` 里读 `collectIsFocusedAsState()`：
 *    composed 的 lambda 不会因状态变化自行重组，卡片的放大/描边会永远停在
 *    首帧的值（首帧没焦点 = 描边永不出现）。这里用 `onFocusChanged` + 本地
 *    state，是确定会重组的写法。
 *  - 描边不能用 `drawWithContent` 画在 clip 里面：外半圈被圆角裁掉，深色
 *    海报上几乎看不见。改成叠一层 `matchParentSize().border()`，画在内容
 *    之后（压在图片上），并且完整落在边界内。
 *
 * 放大走 graphicsLayer：只影响绘制、不触发重新布局，横向滚动不掉帧。
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    focusedScale: Float = 1.1f,
    ringWidth: Dp = 3.dp,
    ringColor: Color = Color.White,
    ringInset: Dp = 4.dp,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = tween(durationMillis = 110),
        label = "focusScale",
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 90),
        label = "focusRing",
    )
    val source = remember { MutableInteractionSource() }

    Box(
        modifier
            .zIndex(if (focused) 2f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { state ->
                if (focused != state.isFocused) {
                    focused = state.isFocused
                    onFocusChanged?.invoke(state.isFocused)
                }
            }
            .clickable(interactionSource = source, indication = null, onClick = onClick)
    ) {
        content(focused)
        if (ringAlpha > 0f && ringWidth > 0.dp) {
            Box(
                Modifier
                    .matchParentSize()
                    .padding(ringInset)
                    .border(ringWidth, ringColor.copy(alpha = ringAlpha), shape)
            )
        }
    }
}

/**
 * 胶囊按钮（主操作 / 次操作）。焦点时反白 + 放大 + 描边。
 */
@Composable
fun PillButton(
    label: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    FocusSurface(
        onClick = onClick,
        modifier = modifier.then(
            if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
        ),
        shape = shape,
        focusedScale = 1.05f,
        ringInset = 2.dp,
    ) { focused ->
        val bg by animateColorAsState(
            targetValue = if (focused) Color.White else Color.White.copy(alpha = 0.16f),
            animationSpec = tween(durationMillis = 100),
            label = "pillBg",
        )
        Box(
            Modifier
                .background(bg, shape)
                .padding(
                    horizontal = if (compact) 18.dp else 26.dp,
                    vertical = if (compact) 9.dp else 13.dp,
                ),
        ) {
            Text(
                label,
                color = if (focused) Color.Black else Color.White,
                fontSize = if (compact) 13.sp else 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 次要胶囊：深底白字，焦点时也反白，避免和主按钮抢注意力。 */
@Composable
fun GhostButton(
    label: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    FocusSurface(
        onClick = onClick,
        modifier = modifier.then(
            if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
        ),
        shape = shape,
        focusedScale = 1.05f,
        ringInset = 2.dp,
    ) { focused ->
        val bg by animateColorAsState(
            targetValue = if (focused) Color.White else Color.White.copy(alpha = 0.12f),
            animationSpec = tween(durationMillis = 100),
            label = "ghostBg",
        )
        Box(
            Modifier
                .background(bg, shape)
                .padding(horizontal = 22.dp, vertical = 11.dp),
        ) {
            Text(
                label,
                color = if (focused) Color.Black else TvptTextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * 等界面真的挂上去了再抢焦点，失败就短间隔重试。
 *
 * 直接在 LaunchedEffect 里 `requestFocus()` 经常抓空：列表项（LazyRow/LazyColumn
 * 的 item）是懒加载的，首次请求时子节点可能还没 attach，requestFocus 抛
 * IllegalStateException 被 runCatching 吞掉，结果是页面进去了、屏幕上却没有任何
 * 元素被选中（用户反馈过"不知道现在选的是哪"）。重试到成功或超时为止。
 */
suspend fun FocusRequester.requestFocusWithRetry(
    times: Int = 12,
    stepMs: Long = 80,
): Boolean {
    repeat(times) {
        if (runCatching { requestFocus() }.isSuccess) return true
        kotlinx.coroutines.delay(stepMs)
    }
    return false
}

/** 圆角卡片底：先裁再铺色，图片被子节点裁到圆角内。 */
fun Modifier.cardSurface(shape: Shape, color: Color): Modifier =
    this.clip(shape).background(color)
