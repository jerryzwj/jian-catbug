package com.jerryzwj.catbug

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从 GitHub Releases 检查新版本（不依赖任何第三方库）。
 * 发布时打 v* 标签，Action 会把 APK 挂到 Release 上。
 */
object Updater {

    const val REPO = "jerryzwj/jian-catbug"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val WEB = "https://github.com/$REPO/releases/latest"

    data class Info(val version: String, val apkUrl: String, val pageUrl: String, val notes: String)

    private val main = Handler(Looper.getMainLooper())

    fun currentVersion(): String = BuildConfig.VERSION_NAME

    /** 后台线程拉取 latest release，回调在主线程；失败回调 null。 */
    fun checkLatest(onResult: (Info?) -> Unit) {
        Thread {
            val info = runCatching { fetch() }.getOrNull()
            main.post { onResult(info) }
        }.apply { isDaemon = true }.start()
    }

    private fun fetch(): Info? {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "jian-catbug-android")
        }
        try {
            if (conn.responseCode != 200) return null
            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").ifEmpty { return null }
            val notes = json.optString("body")
            val assets = json.optJSONArray("assets")
            var apk = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apk = a.optString("browser_download_url")
                        break
                    }
                }
            }
            val page = json.optString("html_url").ifEmpty { WEB }
            return Info(clean(tag), apk, page, notes)
        } finally {
            conn.disconnect()
        }
    }

    private fun clean(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    /** a 比 b 新返回 true */
    fun isNewer(a: String, b: String): Boolean {
        val pa = a.split(".").map { it.filter { ch -> ch.isDigit() } .toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
