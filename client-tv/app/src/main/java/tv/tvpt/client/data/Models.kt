package tv.tvpt.client.data

import kotlinx.serialization.Serializable

@Serializable
data class MediaItem(
    val type: String,          // movie | tv
    val tmdbId: Int,
    val title: String,
    val originalTitle: String = "",
    val year: Int = 0,
    val poster: String = "",
    val backdrop: String = "",
    val rating: Double = 0.0,
    val overview: String = "",
)

@Serializable
data class SearchResponse(
    val page: Int = 1,
    val hasMore: Boolean = false,
    /** 榜单/搜索的总页数（TMDB 最多 500 页），"更多"页用来越界判断。 */
    val totalPages: Int = 0,
    val items: List<MediaItem> = emptyList(),
)

@Serializable
data class SeasonBrief(val season: Int, val episodes: Int)

@Serializable
data class DetailResponse(
    val type: String,
    val tmdbId: Int,
    val imdbId: String = "",
    val title: String,
    val originalTitle: String = "",
    val year: Int = 0,
    val runtime: Int = 0,
    val rating: Double = 0.0,
    val genres: List<String> = emptyList(),
    val overview: String = "",
    val poster: String = "",
    val backdrop: String = "",
    val seasons: List<SeasonBrief> = emptyList(),
)

@Serializable
data class EpisodeItem(
    val episode: Int,
    val name: String,
    val airDate: String = "",
    val still: String = "",
    val overview: String = "",
    val rating: Double = 0.0,
    val runtime: Int = 0,
)

@Serializable
data class SeasonResponse(
    val season: Int = 1,
    val episodes: List<EpisodeItem> = emptyList(),
)

@Serializable
data class StreamItem(
    val name: String = "",
    val title: String = "",
    val url: String,
    val seeders: Int = 0,
    val sizeBytes: Long = 0,
    val resolution: String = "",
    val source: String = "",
    val seasonPack: Boolean = false,
)

@Serializable
data class StreamResponse(
    val streams: List<StreamItem> = emptyList(),
    val filtered: Boolean = true,
)

/**
 * qBittorrent 里的一颗种子。字段名跟后端 /api/tv/downloads 对齐。
 * 站点（site）由后端从 tracker 域名解析出来（各站点的短名）。
 */
@Serializable
data class DownloadItem(
    val hash: String,
    val name: String,
    val size: Long = 0,
    val progress: Double = 0.0,
    val dlspeed: Long = 0,
    val upspeed: Long = 0,
    /** qB 的原始状态（downloading / pausedDL / stalledUP / ...）。 */
    val state: String = "",
    val site: String = "",
    val ratio: Double = 0.0,
    val eta: Long = 0,
    val seedingSeconds: Long = 0,
    val addedOn: Long = 0,
) {
    /** 暂停中（下载暂停或已停止做种）。 */
    val paused: Boolean
        get() = state.startsWith("paused") || state.startsWith("stopped")

    val finished: Boolean
        get() = progress >= 0.999

    val percent: Int
        get() = (progress * 100).toInt().coerceIn(0, 100)
}

@Serializable
data class DownloadListResponse(
    val items: List<DownloadItem> = emptyList(),
    /** qB 是否可达；不可达时 items 为空，客户端据此给提示。 */
    val online: Boolean = true,
    val error: String = "",
)

/** /api/tv/play 的响应：seedstrem 播放直链（已经被改写成和请求同源）。 */
@Serializable
data class PlayUrlResponse(val url: String = "")
