package com.itcast.ds_balance_viewer.data

import com.itcast.ds_balance_viewer.net.WorkBuddyClient

/**
 * WorkBuddy 会话维护：保证拿到的账号有一个当前可用的 accessToken。
 *
 * accessToken 有效期 30 天，快到期（<5 分钟）或接口返回 401 时，先用 refreshToken 续期；
 * refreshToken 有效期 60 天，实测不轮换，所以电脑端与手机端可共用同一套凭据。
 *
 * 这个函数是阻塞的，必须在 IO 线程调用（AppExecutors.io）。
 */
object WbSession {

    /** 续期成功返回新账号（已写回仓库）；无法续期返回 null，调用方应提示重新登录 */
    fun ensureToken(account: WbAccount): WbAccount? {
        if (!account.tokenExpired()) return account
        if (!account.refreshUsable()) return null

        return when (val res = WorkBuddyClient.refresh(account.refreshToken!!)) {
            is WorkBuddyClient.Res.Ok -> {
                val now = System.currentTimeMillis()
                val updated = account.copy(
                    accessToken = res.value.accessToken,
                    refreshToken = res.value.refreshToken ?: account.refreshToken,
                    domain = res.value.domain ?: account.domain,
                    accessExpireAt = now + res.value.expiresIn * 1000L,
                    refreshExpireAt = now + res.value.refreshExpiresIn * 1000L
                )
                WbRepository.update(updated)
                updated
            }
            is WorkBuddyClient.Res.Err -> null
        }
    }

    /** 把接口状态合并进账号快照（只覆盖有值的字段） */
    fun merge(account: WbAccount, status: WorkBuddyClient.CheckinStatus): WbAccount =
        account.copy(
            active = status.active,
            checkedIn = status.checkedIn,
            streakDays = status.streakDays,
            dailyCredit = status.dailyCredit,
            todayCredit = status.todayCredit,
            // 服务端未返回 total_credits 时保留上一次已知值，避免把积分显示成占位符
            totalCredits = if (status.totalCredits >= 0) status.totalCredits else account.totalCredits,
            isStreakDay = status.isStreakDay,
            nextStreakDay = status.nextStreakDay,
            streakBonusDays = status.streakBonusDays,
            streakBonusCredit = status.streakBonusCredit,
            checkinDates = status.checkinDates.ifEmpty { account.checkinDates },
            activityName = status.activityName ?: account.activityName,
            activityEndTime = status.endTime ?: account.activityEndTime,
            lastCheckinAt = if (status.checkedIn) System.currentTimeMillis()
            else account.lastCheckinAt
        )

    /**
     * 把账户余额合并进账号快照。
     *
     * 与 [merge] 分开：余额来自另一个接口（get-user-resource-summary），
     * 它可能成功而签到状态失败，反之亦然，两者互不覆盖。
     */
    fun mergeBalance(account: WbAccount, summary: WorkBuddyClient.CreditSummary): WbAccount =
        account.copy(
            creditLeft = summary.left,
            creditTotal = summary.total,
            creditUsed = summary.used,
            isPaidUser = summary.isPaidUser,
            planName = summary.planName ?: account.planName
        )
}
