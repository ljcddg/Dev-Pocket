package com.itcast.ds_balance_viewer.net

import com.itcast.ds_balance_viewer.data.BalanceItem
import com.itcast.ds_balance_viewer.data.BalanceResult
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * DeepSeek 余额接口客户端。
 *
 * 只使用 Android 内置的 HttpURLConnection 与 org.json，不引入 OkHttp / Retrofit，
 * 好处是零额外依赖、APK 体积小；代价是需要自己处理超时与错误分类，见下方 when 分支。
 *
 * 注意：本类所有方法都是阻塞的，必须在子线程调用（Repository/Activity 用线程池包装）。
 */
object DeepSeekClient {

    private const val ENDPOINT = "https://api.deepseek.com/user/balance"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    /** 查询结果：成功携带余额数据，失败携带可映射成本地化文案的错误码 */
    sealed class Result {
        data class Ok(val balance: BalanceResult) : Result()
        data class Error(val code: ErrorCode, val detail: String? = null) : Result()
    }

    enum class ErrorCode {
        TIMEOUT,        // 连接或读取超时
        NETWORK,        // DNS 失败、无网络、SSL 异常
        UNAUTHORIZED,   // 401 密钥无效
        FORBIDDEN,      // 403 无权限
        RATE_LIMITED,   // 429 限流
        SERVER,         // 5xx 服务端异常
        PARSE,          // 响应不是预期 JSON
        UNKNOWN
    }

    fun query(secret: String): Result {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $secret")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "DSBalanceViewer/1.0 (Android)")
                useCaches = false
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }

            when {
                status in 200..299 -> parse(body)
                status == 401 -> Result.Error(ErrorCode.UNAUTHORIZED, body)
                status == 403 -> Result.Error(ErrorCode.FORBIDDEN, body)
                status == 429 -> Result.Error(ErrorCode.RATE_LIMITED, body)
                status >= 500 -> Result.Error(ErrorCode.SERVER, body)
                else -> Result.Error(ErrorCode.UNKNOWN, "HTTP $status $body")
            }
        } catch (e: SocketTimeoutException) {
            Result.Error(ErrorCode.TIMEOUT, e.message)
        } catch (e: UnknownHostException) {
            Result.Error(ErrorCode.NETWORK, e.message)
        } catch (e: IOException) {
            Result.Error(ErrorCode.NETWORK, e.message)
        } catch (e: Exception) {
            // SSLHandshakeException 等运行时异常兜底，避免崩溃
            Result.Error(ErrorCode.NETWORK, e.message)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 解析余额响应。接口示例：
     * {"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00",
     *  "granted_balance":"10.00","topped_up_balance":"100.00"}]}
     */
    private fun parse(body: String?): Result {
        if (body.isNullOrBlank()) return Result.Error(ErrorCode.PARSE, "响应为空")
        return runCatching {
            val root = JSONObject(body)
            val available = root.optBoolean("is_available", false)
            val array = root.optJSONArray("balance_infos")
            val items = mutableListOf<BalanceItem>()
            if (array != null) {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    items.add(
                        BalanceItem(
                            currency = obj.optString("currency", "?"),
                            total = obj.optString("total_balance", "0"),
                            granted = obj.optString("granted_balance", "0"),
                            toppedUp = obj.optString("topped_up_balance", "0")
                        )
                    )
                }
            }
            Result.Ok(BalanceResult(available, items, body))
        }.getOrElse { Result.Error(ErrorCode.PARSE, body) }
    }
}
