package com.itcast.ds_balance_viewer.net

import com.itcast.ds_balance_viewer.data.BalanceItem
import com.itcast.ds_balance_viewer.data.BalanceResult
import com.itcast.ds_balance_viewer.data.Provider
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale

/**
 * 三家商家的余额查询统一入口。
 *
 * 与旧版 DeepSeekClient 的关系：那一份只管 DeepSeek，这里把 Kimi 和智谱接进来，
 * 三家共用一个 HTTP 出口和一套错误码，调用方不用关心是哪家。
 *
 * 仍然只用 HttpURLConnection + org.json，不引入 OkHttp / Retrofit。
 * 所有方法阻塞，必须在 IO 线程调用。
 */
object BalanceClient {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val UA = "DSBalanceViewer/1.0 (Android)"

    private const val DEEPSEEK_URL = "https://api.deepseek.com/user/balance"
    private const val KIMI_URL = "https://api.moonshot.cn/v1/users/me/balance"
    private const val GLM_BALANCE_URL = "https://open.bigmodel.cn/api/paas/v4/users/me/balance"

    /** 智谱控制台财务页实际调用的报表接口，官方文档未列，作为主路径失败时的回退 */
    private const val GLM_REPORT_URL =
        "https://open.bigmodel.cn/api/biz/account/query-customer-account-report"

    sealed class Result {
        data class Ok(val balance: BalanceResult) : Result()
        data class Error(val code: ErrorCode, val detail: String? = null) : Result()
    }

    enum class ErrorCode {
        TIMEOUT, NETWORK, UNAUTHORIZED, FORBIDDEN, RATE_LIMITED, SERVER, PARSE, UNKNOWN
    }

    fun query(provider: Provider, secret: String): Result = when (provider) {
        Provider.DEEPSEEK -> queryDeepSeek(secret)
        Provider.KIMI -> queryKimi(secret)
        Provider.GLM -> queryGlm(secret)
    }

    // ---------------- DeepSeek ----------------

    /**
     * 响应示例：
     * {"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00",
     *  "granted_balance":"10.00","topped_up_balance":"100.00"}]}
     */
    private fun queryDeepSeek(secret: String): Result {
        val (status, body) = request(DEEPSEEK_URL, secret)
        if (status !in 200..299) return errorOf(status, body)

        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return Result.Error(ErrorCode.PARSE, body)
        val available = root.optBoolean("is_available", false)
        val array = root.optJSONArray("balance_infos")
        val items = mutableListOf<BalanceItem>()
        if (array != null) {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                items.add(
                    BalanceItem(
                        currency = obj.optString("currency", "CNY"),
                        total = obj.optString("total_balance", "0"),
                        granted = obj.optString("granted_balance", "0"),
                        toppedUp = obj.optString("topped_up_balance", "0")
                    )
                )
            }
        }
        return Result.Ok(BalanceResult(available, items, body))
    }

    // ---------------- Kimi（月之暗面） ----------------

    /**
     * 响应示例：
     * {"code":0,"data":{"available_balance":49.58,"voucher_balance":46.58,"cash_balance":3.00},
     *  "scode":"0x0","status":true}
     * 金额单位是人民币元，数值型；code 非 0 表示业务失败。
     */
    private fun queryKimi(secret: String): Result {
        val (status, body) = request(KIMI_URL, secret)
        if (status !in 200..299) return errorOf(status, body)

        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return Result.Error(ErrorCode.PARSE, body)
        val code = root.optInt("code", -1)
        if (code != 0) {
            val message = root.optJSONObject("error")?.optString("message")
            return Result.Error(ErrorCode.UNKNOWN, message?.ifBlank { null } ?: body)
        }
        val data = root.optJSONObject("data") ?: return Result.Error(ErrorCode.PARSE, body)

        val available = data.optDouble("available_balance", 0.0)
        val item = BalanceItem(
            currency = "CNY",
            total = money(available),
            granted = money(data.optDouble("voucher_balance", 0.0)),
            toppedUp = money(data.optDouble("cash_balance", 0.0))
        )
        return Result.Ok(BalanceResult(available > 0, listOf(item), body))
    }

    // ---------------- 智谱 GLM ----------------

    /**
     * 两条路径：
     *  1. v4 余额接口（带 Bearer）：{"balanceInfo":{"balance":..,"giftBalance":..,"voucherBalance":..}}
     *  2. 财务报表接口（裸 key）：{"code":200,"data":{"balance":..,"rechargeAmount":..,"giveAmount":..}}
     *
     * 第 2 条是从控制台 JS 里挖出来的旧接口，字段语义和 1 不同，但实测可用。
     * 两条都失败时返回主路径的错误，信息更有参考价值。
     */
    private fun queryGlm(secret: String): Result {
        val primary = request(GLM_BALANCE_URL, secret)
        if (primary.first in 200..299) {
            parseGlmV4(primary.second)?.let { return it }
        }
        val fallback = request(GLM_REPORT_URL, secret, bearer = false)
        if (fallback.first in 200..299) {
            parseGlmReport(fallback.second)?.let { return it }
        }
        return errorOf(primary.first, primary.second)
    }

    private fun parseGlmV4(body: String): Result? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val info = root.optJSONObject("balanceInfo") ?: root.optJSONObject("data") ?: return null
        if (!info.has("balance") && !info.has("giftBalance")) return null

        // balance 一般就是账户总余额；赠金/代金券是从总额里拆出来的部分，
        // 所以用「总额不叠加」的算法，避免把同一笔钱算两遍
        val cash = info.optDouble("balance", 0.0)
        val gift = info.optDouble("giftBalance", 0.0)
        val voucher = info.optDouble("voucherBalance", 0.0)
        val bonus = gift + voucher
        val toppedUp = if (bonus > 0 && cash >= bonus) cash - bonus else cash

        return Result.Ok(
            BalanceResult(
                available = cash > 0,
                items = listOf(
                    BalanceItem("CNY", money(cash), money(bonus), money(toppedUp))
                ),
                rawJson = body
            )
        )
    }

    private fun parseGlmReport(body: String): Result? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (root.optInt("code", 200) != 200) return null
        val data = root.optJSONObject("data") ?: return null
        if (!data.has("balance")) return null

        val balance = data.optDouble("balance", 0.0)
        return Result.Ok(
            BalanceResult(
                available = balance > 0,
                items = listOf(
                    BalanceItem(
                        currency = "CNY",
                        total = money(balance),
                        granted = money(data.optDouble("giveAmount", 0.0)),
                        toppedUp = money(data.optDouble("rechargeAmount", 0.0))
                    )
                ),
                rawJson = body
            )
        )
    }

    // ---------------- 内部实现 ----------------

    /** 金额统一保留两位小数，接口给的是 Double，直接拼字符串会带一串尾数 */
    private fun money(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun errorOf(status: Int, body: String): Result.Error = when {
        status == -2 -> Result.Error(ErrorCode.TIMEOUT, body)
        status == -1 -> Result.Error(ErrorCode.NETWORK, body)
        status == 401 -> Result.Error(ErrorCode.UNAUTHORIZED, body)
        status == 403 -> Result.Error(ErrorCode.FORBIDDEN, body)
        status == 429 -> Result.Error(ErrorCode.RATE_LIMITED, body)
        status >= 500 -> Result.Error(ErrorCode.SERVER, body)
        else -> Result.Error(ErrorCode.UNKNOWN, "HTTP $status $body")
    }

    /**
     * 返回 (HTTP 状态码, 响应体)；-2 超时、-1 网络错误，与 WorkBuddyClient 的约定一致。
     * [bearer] 为 false 时用裸 key 作 Authorization（智谱报表接口认这种写法）。
     */
    private fun request(url: String, secret: String, bearer: Boolean = true): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", if (bearer) "Bearer $secret" else secret)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", UA)
                useCaches = false
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText) ?: ""
            status to text
        } catch (e: SocketTimeoutException) {
            -2 to (e.message ?: "timeout")
        } catch (e: UnknownHostException) {
            -1 to (e.message ?: "unknown host")
        } catch (e: IOException) {
            -1 to (e.message ?: "io error")
        } catch (e: Exception) {
            -1 to (e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }
}
