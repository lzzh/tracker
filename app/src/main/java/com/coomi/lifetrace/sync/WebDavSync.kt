package com.coomi.lifetrace.sync

import android.content.Context
import android.content.SharedPreferences
import com.coomi.lifetrace.data.TrackPoint
import com.coomi.lifetrace.data.GeoJsonIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** WebDAV 自动同步：把导出的 GeoJSON 上传到用户配置的 WebDAV 目录 */
class WebDavSync(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("lifetrace", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun baseUrl(): String = prefs.getString("webdav_url", "") ?: ""
    private fun username(): String = prefs.getString("webdav_user", "") ?: ""
    private fun password(): String = prefs.getString("webdav_pass", "") ?: ""
    fun isConfigured(): Boolean = baseUrl().isNotBlank()

    /** 上传一个文件到 WebDAV（PUT）。成功返回 true。 */
    suspend fun upload(file: File, remoteName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = (if (baseUrl().endsWith("/")) baseUrl() else baseUrl() + "/") + remoteName
            val auth = if (username().isNotBlank())
                Credentials.basic(username(), password()) else null
            val builder = Request.Builder()
                .url(url)
                .put(file.readBytes().toRequestBody("application/json".toMediaType()))
            if (auth != null) builder.header("Authorization", auth)
            client.newCall(builder.build()).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 同步指定时间段的轨迹 */
    suspend fun syncRange(points: List<TrackPoint>, label: String): Boolean {
        if (!isConfigured()) return false
        val geojson = GeoJsonIO.exportGeoJson(points)
        val dir = File(context.cacheDir, "sync")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "$label-${System.currentTimeMillis()}.geojson")
        f.writeText(geojson)
        return upload(f, "$label-${System.currentTimeMillis()}.geojson")
    }
}
