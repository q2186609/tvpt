package tv.tvpt.client.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 一次播放请求：详情页点「播放」/「播放第 N 集」时就把这些信息带上了。
 *
 * 之前这条链路只传 imdbId 和标题，导致播放器没法知道"这是哪部片的第几集"，
 * 也就没法做续播和播放历史。
 */
@Serializable
data class PlayRequest(
    val type: String,              // movie | tv
    val tmdbId: Int = 0,
    val imdbId: String = "",
    val title: String,
    val poster: String = "",
    val backdrop: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val episodeName: String = "",
    /**
     * 这条片源在 PT 站上的发布名（种子名）。下载页的条目只有种子名，
     * 靠它才能把"qB 里的这个种子"和"我看的是哪部片、看到第几分钟"对上。
     */
    val releaseName: String = "",
)

/** 一条观看记录：一部片一条，记录最后看的那一集和位置。 */
@Serializable
data class WatchEntry(
    val type: String,
    val tmdbId: Int = 0,
    val imdbId: String = "",
    val title: String,
    val poster: String = "",
    val backdrop: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val episodeName: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0,
    val finished: Boolean = false,
    val releaseName: String = "",
    /** 只加进了下载队列、还没开播（片库里标"待下载"）。 */
    val queued: Boolean = false,
) {
    /** 一部片只有一条记录，换集就覆盖。 */
    val key: String get() = "$type-$tmdbId"

    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** 剩余分钟数，片库卡片上显示"剩 32 分钟"（不足一分钟按 1 分钟算）。 */
    val remainingMinutes: Int
        get() = (((durationMs - positionMs).coerceAtLeast(0L) + 59_999L) / 60_000L).toInt().coerceAtLeast(1)

    fun toPlayRequest(): PlayRequest = PlayRequest(
        type = type,
        tmdbId = tmdbId,
        imdbId = imdbId,
        title = title,
        poster = poster,
        backdrop = backdrop,
        season = season,
        episode = episode,
        episodeName = episodeName,
        releaseName = releaseName,
    )
}

private val Context.historyStore by preferencesDataStore(name = "tvpt_history")

/**
 * 播放历史，纯本地（DataStore 里存一段 JSON）。
 *
 * 不上数据库：一部片一条、最多留 60 条，JSON 足够；也不想为了这点东西引入 Room
 * 的注解处理和迁移。换机器不同步——对"自己家的电视"这个场景够用。
 */
class WatchHistoryStore(private val context: Context) {
    private companion object {
        val KEY = stringPreferencesKey("history_json")
        const val MAX_ENTRIES = 60
        // 站内条目几乎不变，脏数据不需要兼容：解不出来就当空历史，别让首页崩掉
        val json = Json { ignoreUnknownKeys = true }
        val listSerializer = ListSerializer(WatchEntry.serializer())
    }

    /** 最近看的排前面。 */
    val entries: Flow<List<WatchEntry>> = context.historyStore.data.map { prefs ->
        decode(prefs[KEY])
    }

    suspend fun snapshot(): List<WatchEntry> = entries.first()

    /** 某部片看过的位置（续播用）。 */
    suspend fun find(type: String, tmdbId: Int): WatchEntry? =
        snapshot().firstOrNull { it.type == type && it.tmdbId == tmdbId }

    /**
     * 写入/覆盖一条记录。
     *
     * position 的有效范围有讲究：看到 95% 以上、或者只剩两分钟，就直接算看完
     * （片尾字幕谁也不会看完）；反过来如果只看了几十秒，不覆盖原来的位置，
     * 避免"点错了片源又退出"把看了半集的进度抹掉。
     */
    suspend fun record(entry: WatchEntry) {
        context.historyStore.edit { prefs ->
            val current = decode(prefs[KEY]).toMutableList()
            val old = current.firstOrNull { it.key == entry.key }
            var next = entry
            if (entry.queued && old != null) {
                // 「稍后观看」只是排队下载，不该把已经看了半集的进度抹成 0
                next = entry.copy(
                    positionMs = old.positionMs,
                    durationMs = old.durationMs,
                    finished = old.finished,
                )
            }
            if (old != null && entry.durationMs > 0) {
                val watched = entry.positionMs
                val sameEpisode = old.season == entry.season && old.episode == entry.episode
                if (sameEpisode && watched < 60_000L && old.positionMs > watched) {
                    next = entry.copy(positionMs = old.positionMs, durationMs = old.durationMs)
                }
            }
            if (next.durationMs > 0) {
                // 末尾多少算"看完"要跟片长走：固定 2 分钟对 45 分钟的剧集合适，
                // 对几分钟的短片就等于一开播就判定看完（实测 3 分钟的测试片段
                // 看到 2:00 就被标成 finished，详情页的续播按钮因此不出现）。
                val tail = (next.durationMs * 3 / 100).coerceIn(30_000L, 180_000L)
                val nearEnd = next.positionMs >= next.durationMs * 95 / 100 ||
                    next.durationMs - next.positionMs <= tail
                next = next.copy(finished = nearEnd)
            }
            current.removeAll { it.key == next.key }
            current.add(0, next)
            prefs[KEY] = json.encodeToString(
                listSerializer,
                current.sortedByDescending { it.updatedAt }.take(MAX_ENTRIES),
            )
        }
    }

    suspend fun remove(key: String) {
        context.historyStore.edit { prefs ->
            val left = decode(prefs[KEY]).filterNot { it.key == key }
            prefs[KEY] = json.encodeToString(listSerializer, left)
        }
    }

    suspend fun clear() {
        context.historyStore.edit { it.remove(KEY) }
    }

    private fun decode(raw: String?): List<WatchEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
    }
}
