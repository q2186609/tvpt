package tv.tvpt.client.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "tvpt_settings")

/** 只存最关键的设置：服务器地址、语言、画质偏好。 */
class SettingsStore(private val context: Context) {
    companion object {
        val BASE_URL = stringPreferencesKey("base_url")
        val LANG = stringPreferencesKey("lang")
        val MAX_QUALITY = stringPreferencesKey("max_quality")
    }

    val baseUrl: Flow<String> = context.dataStore.data
        .map { it[BASE_URL] ?: Api.DEFAULT_BASE_URL }

    val lang: Flow<String> = context.dataStore.data
        .map { it[LANG] ?: "zh-CN" }

    suspend fun setBaseUrl(value: String) {
        context.dataStore.edit { it[BASE_URL] = normalizeBaseUrl(value) }
    }

    /**
     * 电视上手动敲地址很容易漏掉 scheme（"192.168.1.10:8081"），OkHttp 会直接
     * 抛 IllegalArgumentException，用户只看到一句英文异常。这里补默认 http。
     */
    private fun normalizeBaseUrl(raw: String): String {
        val v = raw.trim().trimEnd('/')
        if (v.isEmpty()) return Api.DEFAULT_BASE_URL
        return if (v.startsWith("http://") || v.startsWith("https://")) v else "http://$v"
    }

    suspend fun setLang(value: String) {
        context.dataStore.edit { it[LANG] = value }
    }
}
