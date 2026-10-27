package tv.tvpt.client.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 所有请求都打到 tvpt 后端的 /api/tv 下，客户端不直连 TMDB、也不碰 PT 站。
 * 服务器地址可在设置页修改，默认走已签发证书的域名。
 */
object Api {
    /**
     * 默认服务器地址。值来自构建时注入：仓库里是占位符，本机在
     * client-tv/local.properties 写 tvpt.baseUrl=https://你的域名 覆盖
     * （见 app/build.gradle.kts 的 defaultConfig）。
     */
    val DEFAULT_BASE_URL: String = tv.tvpt.client.BuildConfig.DEFAULT_BASE_URL

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun health(baseUrl: String): Result<String> = raw(baseUrl, "/api/tv/health")

    suspend fun trending(baseUrl: String, type: String, lang: String): Result<List<MediaItem>> =
        getJson(baseUrl, "/api/tv/trending?type=$type&lang=$lang", SearchResponse.serializer())
            .map { it.items }

    /**
     * 首页分类行。feed 是服务端定义好的榜单名（hot_movie / new_movie / cn_tv /
     * us_tv / anime / hot_tv ...），排序和过滤规则都在服务端，改规则不用发版。
     */
    suspend fun list(baseUrl: String, feed: String, lang: String): Result<List<MediaItem>> =
        getJson(baseUrl, "/api/tv/list?feed=$feed&lang=$lang", SearchResponse.serializer())
            .map { it.items }

    /** 分类榜单的分页版本（首页"更多"页用）。 */
    suspend fun listPage(
        baseUrl: String,
        feed: String,
        lang: String,
        page: Int,
    ): Result<SearchResponse> =
        getJson(baseUrl, "/api/tv/list?feed=$feed&lang=$lang&page=$page", SearchResponse.serializer())

    /** qBittorrent 的下载列表（后端代理，客户端不直接连 qB）。 */
    suspend fun downloads(baseUrl: String): Result<DownloadListResponse> =
        getJson(baseUrl, "/api/tv/downloads", DownloadListResponse.serializer())

    /** 暂停 / 恢复 / 删除（action=pause|resume|delete，删除可带 files=true）。 */
    suspend fun downloadAction(
        baseUrl: String,
        hash: String,
        action: String,
        deleteFiles: Boolean = false,
    ): Result<String> =
        post(baseUrl, "/api/tv/downloads/action?hash=$hash&action=$action&files=$deleteFiles")

    /**
     * 稍后观看：把某条片源丢进下载队列（不打开播放器）。
     *
     * 后端立刻返回 202，真正的解析（Prowlarr 拉 .torrent）在后台跑并重试，
     * 所以这里不会再出现等 20 秒然后 504 的情况。
     */
    suspend fun queueDownload(
        baseUrl: String,
        streamUrl: String,
        title: String,
    ): Result<String> =
        post(baseUrl, "/api/tv/download?url=${urlEncode(streamUrl)}&title=${urlEncode(title)}")

    /**
     * 下载页直接播：qB 列表里只有 infohash，后端用 magnet 重新解析一次
     * （种子已在 qB 里，毫秒级），返回 seedstrem 的播放直链。
     */
    suspend fun playTorrent(
        baseUrl: String,
        hash: String,
        name: String,
        series: Boolean = false,
        season: Int = 0,
        episode: Int = 0,
        cid: String = "",
    ): Result<String> {
        val q = StringBuilder("/api/tv/play?hash=$hash&name=${urlEncode(name)}")
        if (series) q.append("&series=1&season=$season&episode=$episode")
        if (cid.isNotBlank()) q.append("&cid=${urlEncode(cid)}")
        return getJson(baseUrl, q.toString(), PlayUrlResponse.serializer())
            .mapCatching { resp ->
                if (resp.url.isBlank()) error("后端没有返回播放地址") else resp.url
            }
    }

    suspend fun search(baseUrl: String, query: String, lang: String): Result<List<MediaItem>> =
        getJson(baseUrl, "/api/tv/search?q=${urlEncode(query)}&lang=$lang", SearchResponse.serializer())
            .map { it.items }

    /** 搜索分页版本：搜索页右侧的分页网格用。 */
    suspend fun searchPage(
        baseUrl: String,
        query: String,
        lang: String,
        page: Int,
    ): Result<SearchResponse> =
        getJson(
            baseUrl,
            "/api/tv/search?q=${urlEncode(query)}&lang=$lang&page=$page",
            SearchResponse.serializer(),
        )

    suspend fun detail(baseUrl: String, type: String, tmdbId: Int, lang: String): Result<DetailResponse> =
        getJson(baseUrl, "/api/tv/detail?type=$type&id=$tmdbId&lang=$lang", DetailResponse.serializer())

    suspend fun season(baseUrl: String, tvId: Int, season: Int, lang: String): Result<SeasonResponse> =
        getJson(baseUrl, "/api/tv/season?tvId=$tvId&season=$season&lang=$lang", SeasonResponse.serializer())

    suspend fun streams(
        baseUrl: String,
        type: String,      // movie | series
        imdbId: String,
        season: Int = 0,
        episode: Int = 0,
    ): Result<List<StreamItem>> {
        val q = StringBuilder("/api/tv/streams?type=$type&imdbId=$imdbId")
        if (type == "series") q.append("&season=$season&episode=$episode")
        return getJson(baseUrl, q.toString(), StreamResponse.serializer()).map { it.streams }
    }

    suspend fun raw(baseUrl: String, path: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(join(baseUrl, path)).get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}: ${body.take(200)}")
                body
            }
        }
    }

    private suspend fun post(baseUrl: String, path: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(join(baseUrl, path)).post(
                    okhttp3.RequestBody.create(null, ByteArray(0))
                ).build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("HTTP ${resp.code}: ${body.take(200)}")
                    body
                }
            }
        }

    private suspend fun <T> getJson(
        baseUrl: String,
        path: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(join(baseUrl, path)).get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}: ${body.take(200)}")
                json.decodeFromString(serializer, body)
            }
        }
    }

    /** 后端给的是相对路径（/api/tv/img/...），这里拼成完整地址。 */
    fun imageUrl(baseUrl: String, path: String): String? {
        if (path.isEmpty()) return null
        return if (path.startsWith("http")) path else join(baseUrl, path)
    }

    /**
     * 把片源直链改成和当前服务器地址同一个源。
     *
     * 片源列表里的 URL 由 seedstrem 拼出来（后缀是它自己的 external_url，公网
     * 域名）。电视和服务器在同一个局域网、或者调试时走 127.0.0.1 端口转发时，
     * 那台机器根本没有这个域名 —— 表现是"列表能刷出来，一点播放就报
     * unable to resolve host"。列表是从这台服务器拿到的，播放当然也该走这台。
     */
    fun sameOrigin(baseUrl: String, url: String): String {
        val base = baseUrl.trimEnd('/')
        val idx = url.indexOf("://").let { if (it < 0) return url else it }
        val slash = url.indexOf('/', idx + 3).let { if (it < 0) return url else it }
        val rest = url.substring(slash)
        return base + rest
    }

    private fun join(baseUrl: String, path: String): String =
        baseUrl.trimEnd('/') + if (path.startsWith("/")) path else "/$path"

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
}
