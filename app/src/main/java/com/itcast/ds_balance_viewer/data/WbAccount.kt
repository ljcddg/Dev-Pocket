package com.itcast.ds_balance_viewer.data

import java.util.UUID

/**
 * 一个 WorkBuddy 账号。
 *
 * accessToken / refreshToken 都由 SecureStore 加密后落盘；其余字段是展示用的快照。
 * accessExpireAt 由 JWT 的 exp 或接口返回的 expiresIn 推算，用于提前刷新。
 */
data class WbAccount(
    val id: String = UUID.randomUUID().toString(),
    val alias: String = "",
    val uid: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val domain: String? = null,
    val accessExpireAt: Long = 0L,
    val refreshExpireAt: Long = 0L,
    /** 签到活动是否开启；null 表示尚未查询 */
    val active: Boolean? = null,
    /** 今日是否已签到；null 表示尚未查询 */
    val checkedIn: Boolean? = null,
    val streakDays: Int = 0,
    val dailyCredit: Int = 0,
    /** 累计积分（-1 表示还没查到） */
    val totalCredits: Int = -1,
    /** 今日到账积分 */
    val todayCredit: Int = 0,
    /** 今天是否正好是连签奖励日 */
    val isStreakDay: Boolean = false,
    /** 下一个连签奖励是第几天 */
    val nextStreakDay: Int = 0,
    /** 连签奖励周期（天） */
    val streakBonusDays: Int = 7,
    /** 一次连签奖励的积分 */
    val streakBonusCredit: Int = 1000,
    /** 已签到的日期（yyyy-MM-dd），用于画日历 */
    val checkinDates: List<String> = emptyList(),
    /** 活动名称与结束时间 */
    val activityName: String? = null,
    val activityEndTime: String? = null,
    val lastCheckinAt: Long = 0L,
    val lastResult: String? = null,
    /** 账户剩余可用额度（本周期）—— -1 表示还没查到 */
    val creditLeft: Long = -1,
    /** 本周期额度总量 —— -1 表示还没查到 */
    val creditTotal: Long = -1,
    /** 本周期已用额度 —— -1 表示还没查到 */
    val creditUsed: Long = -1,
    /** 是否付费用户 */
    val isPaidUser: Boolean = false,
    /** 套餐名（如「专业版月付」） */
    val planName: String? = null
) {
    fun displayName(): String = alias.ifBlank { "WorkBuddy 账号" }

    /** 令牌是否已过期（留 5 分钟余量） */
    fun tokenExpired(now: Long = System.currentTimeMillis()): Boolean =
        accessExpireAt > 0 && now > accessExpireAt - 5 * 60_000L

    /** refreshToken 是否仍可用来续期 */
    fun refreshUsable(now: Long = System.currentTimeMillis()): Boolean =
        !refreshToken.isNullOrBlank() && (refreshExpireAt == 0L || now < refreshExpireAt)
}
