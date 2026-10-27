package tv.tvpt.client.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * material-icons-core 里没有 Download 图标（那套只有二十来个基础图标），
 * 引 material-icons-extended 又要多几 MB，这里直接按 Material 的 24dp
 * 路径画一个：向下箭头 + 底部横线。
 */
val DownloadIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Download",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser()
                .parsePathString("M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z")
                .toNodes(),
            fill = SolidColor(Color.White),
        )
    }.build()
}
