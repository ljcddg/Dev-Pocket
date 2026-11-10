package com.itcast.ds_balance_viewer.net

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * WorkBuddy 接口客户端。
 *
 * 全部接口定义来自桌面端实际调用（已实测通过）。基址 copilot.tencent.com：
 *  1. 创建登录态   POST /v2/plugin/auth/state?platform=mobile   → { state, authUrl }
 *  2. 轮询登录结果 GET  /v2/plugin/auth/token?state=xxx          → 未完成 code=11217，完成返回令牌
 *  3. 刷新令牌     POST /v2/plugin/auth/token/refresh             → 新 accessToken / refreshToken
 *  4. 查询签到状态 POST /v2/billing/meter/checkin-activity-status
 *  5. 领取今日积分 POST /v2/billing/meter/daily-checkin
 *
 * 与 DeepSeekClient 一致：只用 HttpURLConnection + org.json，不引入第三方网络库。
 */
object WorkBuddyClient {

    private const val BASE = "https://copilot.tencent.com"
    private const val UA = "WorkBuddy"
    private const val TIMEOUT_MS = 15_000

    /** 登录态未完成的业务码 */
    private const val CODE_LOGIN_PENDING = 11217
    /** 今日已签到的业务码 */
    private const val CODE_ALREADY_CHECKED = 10001

    data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresIn: Long,
        val refreshExpiresIn: Long,
        val domain: String?
    )

    data class LoginState(val state: String, val authUrl: String)

    /**
     * 签到状态与积分快照。
     *
     * 服务端字段（data 内）：
     *  - today_checked_in      今日是否已签
     *  - streak_days           连续签到天数
     *  - total_credits         累计积分（账户积分总额，App 主页展示的就是这个）
     *  - daily_credit          每日签到固定积分
     *  - today_credit          今日实际到账积分
     *  - is_streak_day         今天是否正好是连签奖励日
     *  - next_streak_day       下一个连签奖励是第几天（默认 7）
     *  - streak_bonus_days     连签奖励周期（默认 7 天）
     *  - streak_bonus_credit   一次连签奖励的积分数（默认 1000）
     *  - checkin_dates         已签到的日期列表，用于画签到日历
     *  - activity_name/start_time/end_time  活动名与起止时间
     *
     * totalCredits 用 -1 表示服务端没返回该字段（与"真的是 0 积分"区分开）。
     */
    data class CheckinStatus(
        val active: Boolean,
        val checkedIn: Boolean,
        val streakDays: Int,
        val dailyCredit: Int,
        val todayCredit: Int,
        val totalCredits: Int = -1,
        val isStreakDay: Boolean = false,
        val nextStreakDay: Int = 0,
        val streakBonusDays: Int = 7,
        val streakBonusCredit: Int = 1000,
        val checkinDates: List<String> = emptyList(),
        val activityName: String? = null,
        val startTime: String? = null,
        val endTime: String? = null
    )

    sealed class PollResult {
        /** 用户在页面上还没完成登录 */
        data object Pending : PollResult()
        data class Done(val tokens: Tokens) : PollResult()
        data class Failed(val message: String) : PollResult()
    }

    sealed class ApiError {
        data object Timeout : ApiError()
        data object Network : ApiError()
        data object Unauthorized : ApiError()
        data class Business(val code: Int, val message: String) : ApiError()
        data object Parse : ApiError()
    }

    /** 通用返回：成功带载荷，失败带错误 */
    sealed class Res<out T> {
        data class Ok<T>(val value: T) : Res<T>()
        data class Err(val error: ApiError) : Res<Nothing>()
    }

    // ---------------- 登录 ----------------

    /** 创建登录态，返回需要打开的登录页地址 */
    fun createLoginState(): Res<LoginState> {
        val (http, raw) = request(
            "$BASE/v2/plugin/auth/state?platform=mobile",
            method = "POST",
            body = "{}",
            headers = mapOf("Content-Type" to "application/json", "User-Agent" to UA)
        )
        val json = parse(raw) ?: return Res.Err(if (http < 0) networkError(http) else ApiError.Parse)
        if (json.optInt("code", -1) != 0) {
            return Res.Err(ApiError.Business(json.optInt("code"), json.optString("msg")))
        }
        val data = json.optJSONObject("data") ?: return Res.Err(ApiError.Parse)
        val state = data.optString("state")
        val authUrl = data.optString("authUrl")
        if (state.isBlank() || authUrl.isBlank()) return Res.Err(ApiError.Parse)
        return Res.Ok(LoginState(state, authUrl))
    }

    /** 轮询登录结果。Pending 表示用户在网页上还没登录完 */
    fun pollToken(state: String): PollResult {
        val (http, raw) = request(
            "$BASE/v2/plugin/auth/token?state=$state",
            method = "GET",
            headers = mapOf("User-Agent" to UA)
        )
        if (http < 0) return PollResult.Failed(networkError(http).toString())
        val json = parse(raw) ?: return PollResult.Failed("响应解析失败")
        val code = json.optInt("code", -1)
        if (code == CODE_LOGIN_PENDING) return PollResult.Pending
        if (code != 0) return PollResult.Failed(json.optString("msg").ifBlank { "code=$code" })
        val holder = findObjectWith(json, "accessToken")
            ?: return PollResult.Failed("返回中缺少令牌")
        return PollResult.Done(
            Tokens(
                accessToken = holder.optString("accessToken"),
                refreshToken = holder.optString("refreshToken").ifBlank { null },
                expiresIn = holder.optLong("expiresIn", 2_592_000L),
                refreshExpiresIn = holder.optLong("refreshExpiresIn", 5_183_999L),
                domain = holder.optString("domain").ifBlank { null }
            )
        )
    }

    /** 用 refreshToken 换新令牌。实测旧 refreshToken 仍有效（不轮换），故可与电脑端共存 */
    fun refresh(refreshToken: String): Res<Tokens> {
        val (http, raw) = request(
            "$BASE/v2/plugin/auth/token/refresh",
            method = "POST",
            body = "{}",
            headers = mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to UA,
                "X-Refresh-Token" to refreshToken,
                "X-Auth-Refresh-Source" to "plugin"
            )
        )
        if (http == 401 || http == 403) return Res.Err(ApiError.Unauthorized)
        val json = parse(raw) ?: return Res.Err(if (http < 0) networkError(http) else ApiError.Parse)
        if (json.optInt("code", -1) != 0) {
            val code = json.optInt("code")
            val err = if (code == 12153 || http == 401) ApiError.Unauthorized
            else ApiError.Business(code, json.optString("msg"))
            return Res.Err(err)
        }
        val holder = findObjectWith(json, "accessToken") ?: return Res.Err(ApiError.Parse)
        return Res.Ok(
            Tokens(
                accessToken = holder.optString("accessToken"),
                refreshToken = holder.optString("refreshToken").ifBlank { refreshToken },
                expiresIn = holder.optLong("expiresIn", 2_592_000L),
                refreshExpiresIn = holder.optLong("refreshExpiresIn", 5_183_999L),
                domain = holder.optString("domain").ifBlank { null }
            )
        )
    }

    // ---------------- 签到 ----------------

    fun checkinStatus(accessToken: String, uid: String, domain: String?): Res<CheckinStatus> {
        val (http, raw) = request(
            "$BASE/v2/billing/meter/checkin-activity-status",
            method = "POST",
            body = "{}",
            headers = apiHeaders(accessToken, uid, domain)
        )
        if (http == 401 || http == 403) return Res.Err(ApiError.Unauthorized)
        val json = parse(raw) ?: return Res.Err(if (http < 0) networkError(http) else ApiError.Parse)
        if (json.optInt("code", -1) != 0) {
            return Res.Err(ApiError.Business(json.optInt("code"), json.optString("msg")))
        }
        val data = json.optJSONObject("data") ?: return Res.Err(ApiError.Parse)
        return Res.Ok(
            CheckinStatus(
                active = data.optBoolean("active", true),
                checkedIn = data.optBoolean("today_checked_in", false),
                streakDays = data.optInt("streak_days", 0),
                dailyCredit = data.optInt("daily_credit", 0),
                todayCredit = data.optInt("today_credit", data.optInt("daily_credit", 0)),
                totalCredits = if (data.has("total_credits")) data.optInt("total_credits", -1) else -1,
                isStreakDay = data.optBoolean("is_streak_day", false),
                nextStreakDay = data.optInt("next_streak_day", 0),
                streakBonusDays = data.optInt("streak_bonus_days", 7),
                streakBonusCredit = data.optInt("streak_bonus_credit", 1000),
                checkinDates = parseDates(data.opt("checkin_dates")),
                activityName = data.optString("activity_name").ifBlank { null },
                startTime = data.optString("start_time").ifBlank { null },
                endTime = data.optString("end_time").ifBlank { null }
            )
        )
    }

    /**
     * 解析签到日期数组。服务端可能给 ["2026-11-09", ...]，
     * 也可能给 [{date:"2026-11-09"}...]，两种都兼容，统一成 "yyyy-MM-dd" 字符串。
     */
    private fun parseDates(value: Any?): List<String> {
        val array = value as? org.json.JSONArray ?: return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val item = array.opt(i)
            val raw = when (item) {
                is String -> item
                is JSONObject -> item.optString("date").ifBlank {
                    item.optString("day").ifBlank { item.optString("checkin_date") }
                }
                else -> ""
            }
            if (raw.isNotBlank()) out.add(normalizeDate(raw))
        }
        return out
    }

    /** 把 2026-11-09 / 2026/11/09 / 带时间的 ISO 串统一成 2026-11-09 */
    private fun normalizeDate(raw: String): String {
        val head = raw.substringBefore('T').substringBefore(' ').replace('/', '-')
        return head
    }

    /** 领取今日积分。hasClaimed 表示今天已签（幂等，不算失败） */
    data class ClaimResult(val credit: Int, val alreadyCheckedIn: Boolean, val message: String?)

    fun claim(accessToken: String, uid: String, domain: String?): Res<ClaimResult> {
        val (http, raw) = request(
            "$BASE/v2/billing/meter/daily-checkin",
            method = "POST",
            body = "{}",
            headers = apiHeaders(accessToken, uid, domain)
        )
        if (http == 401 || http == 403) return Res.Err(ApiError.Unauthorized)
        val json = parse(raw)
        if (json == null) return Res.Err(if (http < 0) networkError(http) else ApiError.Parse)

        val code = json.optInt("code", -1)
        val msg = json.optString("msg")
        if (code != 0) {
            val already = code == CODE_ALREADY_CHECKED || msg.contains("已签到") || msg.contains("已经签到")
            if (already) return Res.Ok(ClaimResult(0, alreadyCheckedIn = true, message = msg))
            if (code == 401 || code == 403) return Res.Err(ApiError.Unauthorized)
            return Res.Err(ApiError.Business(code, msg))
        }
        val data = json.optJSONObject("data")
        // 服务端有的版本用 credit，有的用 credit_granted（实发值），两个都试
        val credit = data?.optInt("credit", 0)
            ?.takeIf { it > 0 }
            ?: data?.optInt("credit_granted", 0) ?: 0
        return Res.Ok(ClaimResult(credit, alreadyCheckedIn = false, message = msg.ifBlank { null }))
    }

    // ---------------- 余额（账户剩余额度） ----------------

    /**
     * 一个积分资源包。账号可能同时持有多个包（免费包、付费包、活动赠送包），
     * 账户余额就是它们 CycleRemainCapacity 的和。
     */
    data class CreditPackage(
        val code: String,
        val total: Long,
        val remain: Long,
        val used: Long
    )

    /**
     * 账户剩余额度快照 —— 这才是「余额」，与签到累计积分是两回事。
     *
     * 注意路径与签到不同族：
     *   余额  POST /billing/meter/get-user-resource-summary     ← 不带 /v2
     *   签到  POST /v2/billing/meter/checkin-activity-status    ← 带 /v2
     * 桌面端 fetchResourceSummary 拼的是 `${endpoint}/billing/meter/...`，
     * 所以主路径不带 /v2；若该网关版本不存在则回退带 /v2 的写法。
     *
     * 服务端字段：Packages[]{PackageCode, CycleTotalCapacity, CycleRemainCapacity, CycleUsedCapacity}
     *            IsPaidUser / SubscriptionPackageCode / SubscriptionPackageName
     */
    data class CreditSummary(
        val left: Long,
        val total: Long,
        val used: Long,
        val isPaidUser: Boolean,
        val planName: String?,
        val packages: List<CreditPackage>
    )

    fun creditSummary(accessToken: String, uid: String, domain: String?): Res<CreditSummary> {
        val first = request(
            "$BASE/billing/meter/get-user-resource-summary",
            method = "POST",
            body = "{}",
            headers = apiHeaders(accessToken, uid, domain) + ("Accept-Language" to "zh-CN")
        )
        var http = first.first
        var raw = first.second
        // 该网关对不存在的路由返回 404；换回桌面端另一种写法再试一次
        if (http == 404) {
            val retry = request(
                "$BASE/v2/billing/meter/get-user-resource-summary",
                method = "POST",
                body = "{}",
                headers = apiHeaders(accessToken, uid, domain) + ("Accept-Language" to "zh-CN")
            )
            http = retry.first
            raw = retry.second
        }
        if (http == 401 || http == 403) return Res.Err(ApiError.Unauthorized)
        val json = parse(raw) ?: return Res.Err(if (http < 0) networkError(http) else ApiError.Parse)
        val code = json.optInt("code", -1)
        if (code != 0) return Res.Err(ApiError.Business(code, json.optString("msg")))

        val holder = findObjectWith(json, "Packages")
        val packages = ArrayList<CreditPackage>()
        holder?.optJSONArray("Packages")?.let { array ->
            for (i in 0 until array.length()) {
                val p = array.optJSONObject(i) ?: continue
                packages.add(
                    CreditPackage(
                        code = p.optString("PackageCode"),
                        total = num(p, "CycleTotalCapacity"),
                        remain = num(p, "CycleRemainCapacity"),
                        used = num(p, "CycleUsedCapacity")
                    )
                )
            }
        }

        // 汇总里没带明细时，再拉免费包 / 付费包两份明细分页补齐
        if (packages.isEmpty()) {
            packages.addAll(fetchPackageAccounts("/billing/meter/get-user-resource-free-packages", accessToken, uid, domain))
            packages.addAll(fetchPackageAccounts("/billing/meter/get-user-resource-paid-packages", accessToken, uid, domain))
        }

        var total = 0L
        var left = 0L
        var used = 0L
        for (p in packages) {
            if (p.total > 0) total += p.total
            if (p.remain > 0) left += p.remain
            if (p.used > 0) used += p.used
        }

        return Res.Ok(
            CreditSummary(
                left = left,
                total = total,
                used = used,
                isPaidUser = holder?.optBoolean("IsPaidUser", false) ?: false,
                planName = holder?.optString("SubscriptionPackageName")?.ifBlank { null },
                packages = packages
            )
        )
    }

    /** 资源包明细分页。字段与汇总里的 Packages 一致，解析失败就当没有 */
    private fun fetchPackageAccounts(
        path: String,
        accessToken: String,
        uid: String,
        domain: String?
    ): List<CreditPackage> {
        val body = """{"PageNumber":1,"PageSize":50,"PackageCodes":[],"Status":[0,3]}"""
        val (http, raw) = request(
            "$BASE$path",
            method = "POST",
            body = body,
            headers = apiHeaders(accessToken, uid, domain) + ("Accept-Language" to "zh-CN")
        )
        if (http !in 200..299) return emptyList()
        val json = parse(raw) ?: return emptyList()
        val data = json.optJSONObject("data") ?: return emptyList()
        val array = data.optJSONArray("Accounts") ?: return emptyList()
        val out = ArrayList<CreditPackage>(array.length())
        for (i in 0 until array.length()) {
            val p = array.optJSONObject(i) ?: continue
            out.add(
                CreditPackage(
                    code = p.optString("PackageCode"),
                    total = num(p, "CycleTotalCapacity"),
                    remain = num(p, "CycleRemainCapacity"),
                    used = num(p, "CycleUsedCapacity")
                )
            )
        }
        return out
    }

    /** 取数值字段，兼容数字与字符串两种写法 */
    private fun num(obj: JSONObject, key: String): Long = when (val v = obj.opt(key)) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: v.toDoubleOrNull()?.toLong() ?: 0L
        else -> 0L
    }

    // ---------------- 诊断 ----------------

    /** 余额接口的原始响应，用于核对字段（不含令牌，可安全展示） */
    fun rawCreditSummary(accessToken: String, uid: String, domain: String?): String {
        val first = request(
            "$BASE/billing/meter/get-user-resource-summary",
            method = "POST",
            body = "{}",
            headers = apiHeaders(accessToken, uid, domain) + ("Accept-Language" to "zh-CN")
        )
        var http = first.first
        var raw = first.second
        if (http == 404) {
            val retry = request(
                "$BASE/v2/billing/meter/get-user-resource-summary",
                method = "POST",
                body = "{}",
                headers = apiHeaders(accessToken, uid, domain) + ("Accept-Language" to "zh-CN")
            )
            http = retry.first
            raw = retry.second
        }
        return "HTTP $http\n\n$raw"
    }

    /**
     * 返回签到接口的原始响应（HTTP 码 + 响应体），用于核对服务端到底返回了哪些字段。
     *
     * 不包含任何令牌，可安全展示给用户。桌面端日志里只打印了部分字段，
     * 而 total_credits 这类字段是否存在会因账号而异，这里直接看原文最准。
     */
    fun rawCheckinStatus(accessToken: String, uid: String, domain: String?): String {
        val (http, raw) = request(
            "$BASE/v2/billing/meter/checkin-activity-status",
            method = "POST",
            body = "{}",
            headers = apiHeaders(accessToken, uid, domain)
        )
        return "HTTP $http\n\n$raw"
    }

    // ---------------- 内部实现 ----------------

    private fun apiHeaders(token: String, uid: String, domain: String?): Map<String, String> {
        val h = mutableMapOf(
            "Accept" to "application/json",
            "Authorization" to "Bearer $token",
            "Content-Type" to "application/json",
            "User-Agent" to UA,
            "X-User-Id" to uid
        )
        if (!domain.isNullOrBlank()) h["X-Domain"] = domain
        return h
    }

    private fun networkError(code: Int): ApiError =
        if (code == -2) ApiError.Timeout else ApiError.Network

    private fun parse(raw: String): JSONObject? = runCatching { JSONObject(raw) }.getOrNull()

    /** 在任意嵌套层寻找含指定字段的对象：服务端有时把令牌包在 data.data 里 */
    private fun findObjectWith(obj: JSONObject, key: String): JSONObject? {
        if (obj.has(key)) return obj
        val keys = obj.keys()
        while (keys.hasNext()) {
            val v = obj.opt(keys.next())
            if (v is JSONObject) {
                findObjectWith(v, key)?.let { return it }
            }
        }
        return null
    }

    /** 返回 (HTTP 状态码, 响应体)；-2 超时、-1 网络错误 */
    private fun request(
        url: String,
        method: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", UA)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            code to text
        } catch (e: SocketTimeoutException) {
            -2 to (e.message ?: "timeout")
        } catch (e: UnknownHostException) {
            -1 to (e.message ?: "unknown host")
        } catch (e: Exception) {
            -1 to (e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }
}
