package com.itcast.ds_balance_viewer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * WorkBuddy 账号仓库：内存缓存 + 加密持久化。
 *
 * 结构与 KeyRepository 一致：账号数量极少，整体序列化写盘即可。
 * 令牌字段（accessToken / refreshToken）走 SecureStore 加密，uid / 别名 / 签到快照明文。
 */
object WbRepository {

    private const val STORE_KEY = "wb_accounts_json"

    private val cache = mutableListOf<WbAccount>()
    private var loaded = false

    fun init(context: Context) {
        SecureStore.init(context)
        if (loaded) return
        load()
        loaded = true
    }

    private fun load() {
        cache.clear()
        val raw = SecureStore.getString(STORE_KEY) ?: return
        if (raw.isBlank()) return
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val access = SecureStore.decrypt(obj.optString("accessToken")) ?: continue
                val refresh = obj.optString("refreshToken").takeIf { it.isNotBlank() }
                    ?.let { SecureStore.decrypt(it) }
                cache.add(
                    WbAccount(
                        id = obj.optString("id"),
                        alias = obj.optString("alias"),
                        uid = obj.optString("uid"),
                        accessToken = access,
                        refreshToken = refresh,
                        domain = obj.optString("domain").ifBlank { null },
                        accessExpireAt = obj.optLong("accessExpireAt", 0L),
                        refreshExpireAt = obj.optLong("refreshExpireAt", 0L),
                        active = if (obj.has("active")) obj.getBoolean("active") else null,
                        checkedIn = if (obj.has("checkedIn")) obj.getBoolean("checkedIn") else null,
                        streakDays = obj.optInt("streakDays", 0),
                        dailyCredit = obj.optInt("dailyCredit", 0),
                        totalCredits = obj.optInt("totalCredits", -1),
                        todayCredit = obj.optInt("todayCredit", 0),
                        isStreakDay = obj.optBoolean("isStreakDay", false),
                        nextStreakDay = obj.optInt("nextStreakDay", 0),
                        streakBonusDays = obj.optInt("streakBonusDays", 7),
                        streakBonusCredit = obj.optInt("streakBonusCredit", 1000),
                        checkinDates = obj.optJSONArray("checkinDates")?.let { arr ->
                            (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
                        } ?: emptyList(),
                        activityName = obj.optString("activityName").ifBlank { null },
                        activityEndTime = obj.optString("activityEndTime").ifBlank { null },
                        lastCheckinAt = obj.optLong("lastCheckinAt", 0L),
                        lastResult = obj.optString("lastResult").ifBlank { null },
                        creditLeft = obj.optLong("creditLeft", -1L),
                        creditTotal = obj.optLong("creditTotal", -1L),
                        creditUsed = obj.optLong("creditUsed", -1L),
                        isPaidUser = obj.optBoolean("isPaidUser", false),
                        planName = obj.optString("planName").ifBlank { null }
                    )
                )
            }
        }
    }

    fun all(): List<WbAccount> = cache.toList()

    fun find(id: String): WbAccount? = cache.firstOrNull { it.id == id }

    fun findByUid(uid: String): WbAccount? = cache.firstOrNull { it.uid == uid }

    fun count(): Int = cache.size

    fun add(account: WbAccount) {
        cache.add(account)
        persist()
    }

    fun update(account: WbAccount) {
        val index = cache.indexOfFirst { it.id == account.id }
        if (index >= 0) {
            cache[index] = account
            persist()
        }
    }

    fun remove(id: String) {
        if (cache.removeAll { it.id == id }) persist()
    }

    private fun persist() {
        val array = JSONArray()
        cache.forEach { a ->
            array.put(JSONObject().apply {
                put("id", a.id)
                put("alias", a.alias)
                put("uid", a.uid)
                put("accessToken", SecureStore.encrypt(a.accessToken))
                a.refreshToken?.let { put("refreshToken", SecureStore.encrypt(it)) }
                put("domain", a.domain ?: "")
                put("accessExpireAt", a.accessExpireAt)
                put("refreshExpireAt", a.refreshExpireAt)
                a.active?.let { put("active", it) }
                a.checkedIn?.let { put("checkedIn", it) }
                put("streakDays", a.streakDays)
                put("dailyCredit", a.dailyCredit)
                put("totalCredits", a.totalCredits)
                put("todayCredit", a.todayCredit)
                put("isStreakDay", a.isStreakDay)
                put("nextStreakDay", a.nextStreakDay)
                put("streakBonusDays", a.streakBonusDays)
                put("streakBonusCredit", a.streakBonusCredit)
                put("checkinDates", JSONArray(a.checkinDates))
                put("activityName", a.activityName ?: "")
                put("activityEndTime", a.activityEndTime ?: "")
                put("lastCheckinAt", a.lastCheckinAt)
                put("lastResult", a.lastResult ?: "")
                put("creditLeft", a.creditLeft)
                put("creditTotal", a.creditTotal)
                put("creditUsed", a.creditUsed)
                put("isPaidUser", a.isPaidUser)
                put("planName", a.planName ?: "")
            })
        }
        SecureStore.putString(STORE_KEY, array.toString())
    }
}
