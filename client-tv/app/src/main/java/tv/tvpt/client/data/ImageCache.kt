package tv.tvpt.client.data

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import java.io.File

/**
 * 图片（海报/剧照）的本地磁盘缓存。
 *
 * Coil 默认只在内存里留一份，退出应用就没了；电视上每次开机都要重新拉几十张
 * 海报，而且每次都走一遍 TMDB 的图片代理。这里给它一个 300MB 的磁盘缓存，
 * 设置页可以看占用、可以清空。
 *
 * 缓存目录放在 cacheDir 下（系统清理空间时允许回收），定长上限避免无限长。
 */
object ImageCache {
    private const val DIR_NAME = "image_cache"
    private const val MAX_BYTES = 300L * 1024 * 1024

    @Volatile
    private var disk: DiskCache? = null

    fun dir(context: Context): File = File(context.cacheDir, DIR_NAME)

    /** 由 MainActivity 调用一次，返回带磁盘缓存的 ImageLoader。 */
    fun buildLoader(context: Context): ImageLoader {
        val cache = DiskCache.Builder()
            .directory(dir(context))
            .maxSizeBytes(MAX_BYTES)
            .build()
        disk = cache
        return ImageLoader.Builder(context)
            // 电视盒子/模拟器经常"系统认为没网、其实局域网通"。Coil 默认监听网络
            // 状态，判定离线后所有请求走 only-if-cached 直接 504，界面就是一排
            // 灰块。能不能连通交给请求本身判断。
            .networkObserverEnabled(false)
            .crossfade(true)
            .diskCache(cache)
            .build()
    }

    /** 当前占用（字节）。遍历目录，几百个文件，几毫秒。 */
    fun sizeBytes(context: Context): Long {
        val root = dir(context)
        if (!root.exists()) return 0
        var total = 0L
        root.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    /** 清空磁盘缓存。先走 Coil 的 API（它会维护自己的 journal），再兜底删目录。 */
    suspend fun clear(context: Context) {
        runCatching { disk?.clear() }
        // 有些设备上 Coil 的 journal 会留下空目录/残留文件，这里再扫一遍
        runCatching {
            val root = dir(context)
            if (root.exists()) {
                root.listFiles()?.forEach { f -> runCatching { f.deleteRecursively() } }
            }
        }
    }

    fun humanSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes / 1048576.0
        if (mb >= 1024) return String.format("%.2f GB", mb / 1024)
        if (mb >= 1) return String.format("%.0f MB", mb)
        return String.format("%.0f KB", bytes / 1024.0)
    }
}
