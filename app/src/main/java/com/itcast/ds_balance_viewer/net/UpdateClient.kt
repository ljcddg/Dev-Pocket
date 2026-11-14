package com.itcast.ds_balance_viewer.net

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * GitHub Releases 更新检查客户端。
 *
 * 仓库：ljcddg/Dev-Pocket，Release 资产命名 DevPocket-vX.Y.apk。
 * Releases API 公开无需鉴权；与项目其他客户端一致，只用
 * HttpURLConnection + org.json，不引第三方网络库。
 */
object UpdateClient {

    private const val RELEASE_API = "https://api.github.com/repos/ljcddg/Dev-Pocket/releases/latest"
    private const val TIMEOUT_MS = 12_000

    data class Release(
        /** 版本 tag，如 "v1.8" */
        val version: String,
        /** Release 描述（markdown 原文，弹窗里直接展示） */
        val notes: String,
        /** apk 资产的下载地址 */
        val apkUrl: String,
        /** apk 文件名，如 "DevPocket-v1.8.apk" */
        val apkName: String,
        /** apk 字节数；<=0 表示接口没给 */
        val apkSize: Long
    )

    sealed class Res {
        data class Ok(val value: Release) : Res()
        data class Err(val message: String) : Res()
    }

    /** 拉取最新 Release。GitHub 在部分网络下可能连不上，失败方决定是否提示 */
    fun latest(): Res {
        val (http, raw) = request()
        if (http !in 200..299) return Res.Err("HTTP $http")
        val json = runCatching { JSONObject(raw) }.getOrElse { return Res.Err("parse") }

        val tag = json.optString("tag_name")
        if (tag.isBlank()) return Res.Err("no tag")
        val assets = json.optJSONArray("assets") ?: return Res.Err("no assets")
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                return Res.Ok(
                    Release(
                        version = tag,
                        notes = json.optString("body"),
                        apkUrl = asset.optString("browser_download_url"),
                        apkName = name,
                        apkSize = asset.optLong("size", 0L)
                    )
                )
            }
        }
        return Res.Err("no apk asset")
    }

    /**
     * 语义化版本比较：远端 tag 比当前 VERSION_NAME 大才提示更新。
     * 兼容 "v1.8" / "1.8" / "1.8.3" / "v1.10-beta"（非数字段忽略）。
     */
    fun isNewer(current: String, remote: String): Boolean {
        val c = nums(current)
        val r = nums(remote)
        val n = maxOf(c.size, r.size)
        for (i in 0 until n) {
            val a = c.getOrElse(i) { 0 }
            val b = r.getOrElse(i) { 0 }
            if (b != a) return b > a
        }
        return false
    }

    private fun nums(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '_')
            .mapNotNull { seg -> seg.filter(Char::isDigit).toIntOrNull() }
            .ifEmpty { listOf(0) }

    /** 返回 (HTTP 状态码, 响应体)；-2 超时、-1 网络错误 */
    private fun request(): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "DevPocket-Android")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            code to text
        } catch (e: SocketTimeoutException) {
            -2 to (e.message ?: "timeout")
        } catch (e: Exception) {
            -1 to (e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }
}
