package tv.tvpt.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 取自 Stremio TV 的实际配色：近乎纯黑的底、层叠的深灰卡片、紫色作唯一强调色。
// 刻意压低中间调，让焦点白边成为画面里最亮的东西（电视上远看也一眼能认出）。
val TvptBackground = Color(0xFF0A0A0C)
val TvptSurface = Color(0xFF141418)
val TvptSurfaceHigh = Color(0xFF1E1E24)
val TvptSurfaceFocused = Color(0xFF2C2C36)
val TvptPrimary = Color(0xFF7B5CFF)
val TvptOnPrimary = Color(0xFFFFFFFF)
val TvptTextPrimary = Color(0xFFFFFFFF)
val TvptTextSecondary = Color(0xFF9E9EA8)
val TvptTextTertiary = Color(0xFF6E6E78)
val TvptRing = Color(0xFFFFFFFF)

private val TvptColors = darkColorScheme(
    primary = TvptPrimary,
    onPrimary = TvptOnPrimary,
    background = TvptBackground,
    onBackground = TvptTextPrimary,
    surface = TvptSurface,
    onSurface = TvptTextPrimary,
    surfaceVariant = TvptSurfaceHigh,
    onSurfaceVariant = TvptTextSecondary,
)

@Composable
fun TvptTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = TvptColors, content = content)
}
