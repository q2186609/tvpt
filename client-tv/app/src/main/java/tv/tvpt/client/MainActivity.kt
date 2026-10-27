package tv.tvpt.client

import android.content.Intent
import android.os.Bundle
import coil.Coil
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tv.tvpt.client.data.PlayRequest
import tv.tvpt.client.data.ImageCache
import tv.tvpt.client.data.SettingsStore
import tv.tvpt.client.ui.AppRoot
import tv.tvpt.client.ui.theme.TvptTheme

class MainActivity : ComponentActivity() {

    /**
     * 外部带进来的搜索词。电视上没有好用的中文输入方式时（遥控器、语音、
     * adb、以及未来接入 Android TV 全局搜索），都通过 extra "q" 直接进搜索页。
     */
    private var pendingQuery by mutableStateOf<String?>(null)

    /**
     * 调试入口：`adb shell am start -n tv.tvpt.client/.MainActivity      *   -e play_url "https://.../dl/<token>/f.mkv" -e play_title "标题"`
     * 直接进播放器。用来在不依赖片源列表的情况下验证起播/解码/拖动。
     */
    private var pendingPlay by mutableStateOf<Pair<String, PlayRequest>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 电视盒子/模拟器经常"系统认为没网、其实局域网通"。Coil 默认会监听网络
        // 状态，判定离线后所有图片请求都走 only-if-cached，直接回 HTTP 504，
        // 界面上就是一排灰块（真机上也踩到过类似盒子）。这里关掉网络监听：
        // 能不能连上，交给请求本身去判断。
        // 带磁盘缓存的 ImageLoader（设置页能看占用、能清空）
        Coil.setImageLoader(ImageCache.buildLoader(applicationContext))
        pendingQuery = intent?.searchQuery()
        pendingPlay = intent?.playRequest()
        // 调试用：-e base_url http://127.0.0.1:8082 可在不改设置页的情况下换后端
        intent?.getStringExtra("base_url")?.trim()?.takeIf { it.isNotEmpty() }?.let { url ->
            val app = applicationContext
            CoroutineScope(Dispatchers.IO).launch { SettingsStore(app).setBaseUrl(url) }
        }
        setContent {
            TvptTheme {
                AppRoot(
                    initialQuery = pendingQuery,
                    onQueryConsumed = { pendingQuery = null },
                    initialPlay = pendingPlay,
                    onPlayConsumed = { pendingPlay = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.searchQuery()?.let { pendingQuery = it }
        intent.playRequest()?.let { pendingPlay = it }
    }

    private fun Intent.searchQuery(): String? =
        getStringExtra("q")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 调试入口：
     * `-e play_url <url> -e play_title <标题> [-e play_tmdb 12345 -e play_type tv
     *  -e play_season 1 -e play_episode 2]`
     *
     * 带上 play_tmdb 后会走真实的播放历史逻辑（续播 + 片库记录），这样不用等
     * 一个能解码的真片源就能验证这些功能。
     */
    private fun Intent.intExtra(name: String): Int =
        getIntExtra(name, 0).takeIf { it != 0 }
            ?: getStringExtra(name)?.toIntOrNull()
            ?: 0

    private fun Intent.playRequest(): Pair<String, PlayRequest>? {
        val url = getStringExtra("play_url")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val title = getStringExtra("play_title")?.trim()?.takeIf { it.isNotEmpty() } ?: "测试片源"
        // --ei 和 --es 都认：手敲 adb 命令时很容易用错其中一个
        val tmdb = intExtra("play_tmdb")
        val req = PlayRequest(
            type = getStringExtra("play_type")?.trim().orEmpty().ifEmpty { "movie" },
            tmdbId = tmdb,
            imdbId = getStringExtra("play_imdb")?.trim().orEmpty(),
            title = title,
            backdrop = getStringExtra("play_backdrop")?.trim().orEmpty(),
            season = intExtra("play_season"),
            episode = intExtra("play_episode"),
        )
        return url to req
    }
}
