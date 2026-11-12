package com.itcast.ds_balance_viewer.data

import com.itcast.ds_balance_viewer.net.WorkBuddyClient
import com.itcast.ds_balance_viewer.util.AppExecutors
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * 签到执行核心：卡片上的单个签到、右上角的一键签到，以及后续的定时签到，
 * 共用这一份逻辑。
 *
 * 与 Fragment 解耦的原因：定时任务没有 Activity、也没有 Context，
 * 不能依赖任何 UI 状态。这里只回传 [Fail] 枚举，中文文案由调用方映射到 strings。
 *
 * 调用顺序刻意与旧实现不同，改成「先查后领」：
 *  1. status  → 今日已签直接结束，只花 1 次请求（旧实现固定 3 次）
 *  2. claim   → 未签才调用，且与 status 之间留 0.4~1.8s 间隔
 *  3. summary → 刷新剩余额度，卡片上的数字才是最新的
 *
 * 所有方法都是阻塞的，必须在 [AppExecutors.checkin] 上执行。
 */
object CheckinEngine {

    enum class Trigger { MANUAL, AUTO }

    enum class Fail { EXPIRED, TIMEOUT, NETWORK, PARSE, BUSINESS }

    sealed class Result {
        /** 本次真正领到了积分 */
        data class Claimed(val credit: Int) : Result()

        /** 今日已签（服务端说的，或本地快照判断的） */
        data object AlreadyDone : Result()

        data class Failed(val kind: Fail, val detail: String = "") : Result()
    }

    /** 全局互斥：单卡片签到、一键签到、后续的定时签到，同一时刻只允许一个在跑 */
    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    /**
     * 批量签到。阻塞调用。
     *
     * @param onProgress 每完成一个账号回调一次（已切回主线程），用于更新进度与卡片
     * @return 账号 id → 结果；被互斥锁挡掉、或无待签账号时返回空 Map
     */
    fun runAll(
        trigger: Trigger,
        onProgress: (done: Int, total: Int, account: WbAccount, result: Result) -> Unit = { _, _, _, _ -> }
    ): Map<String, Result> {
        if (!running.compareAndSet(false, true)) return emptyMap()
        val report = LinkedHashMap<String, Result>()
        try {
            val list = WbRepository.all().filter { needsCheckin(it) }
            var networkFailures = 0
            for ((index, account) in list.withIndex()) {
                val result = checkOne(account)
                report[account.id] = result
                AppExecutors.main { onProgress(index + 1, list.size, account, result) }

                // 断网时连跑一串没有意义，也无谓地刷请求次数
                if (result is Result.Failed && result.kind == Fail.NETWORK && ++networkFailures >= 2) break
                if (index < list.lastIndex) Thread.sleep(gapOf(trigger, result))
            }
        } finally {
            running.set(false)
        }
        return report
    }

    /** 单个账号签到（卡片上的按钮）。返回 null 表示当前已有任务在跑 */
    fun runSingle(account: WbAccount): Result? {
        if (!running.compareAndSet(false, true)) return null
        return try {
            checkOne(account)
        } finally {
            running.set(false)
        }
    }

    // ---------------- 内部实现 ----------------

    /**
     * 只有本地快照明确说「今天已签」才跳过。
     * [WbAccount.checkedIn] 可能是昨天留下的，必须配合 [WbAccount.lastCheckinAt]
     * 判断是否同一天，否则跨天后会把账号误判成已签而漏签。
     */
    private fun needsCheckin(account: WbAccount): Boolean {
        if (account.checkedIn != true) return true
        return !sameDay(account.lastCheckinAt, System.currentTimeMillis())
    }

    private fun checkOne(account: WbAccount): Result {
        val ready = WbSession.ensureToken(account) ?: return fail(account, Fail.EXPIRED)

        val status = WorkBuddyClient.checkinStatus(ready.accessToken, ready.uid, ready.domain)
        if (status is WorkBuddyClient.Res.Err && status.error is WorkBuddyClient.ApiError.Unauthorized) {
            return fail(ready, Fail.EXPIRED)
        }
        if (status is WorkBuddyClient.Res.Ok && status.value.checkedIn) {
            // 已签也顺手刷一次额度，卡片上的「剩余额度」才不会停在旧值
            writeBack(refreshBalance(WbSession.merge(ready, status.value)))
            return Result.AlreadyDone
        }

        // 看完状态再点领取，两次写操作之间留出接近真人的手速
        Thread.sleep(400L + Random.nextLong(1_400L))

        return when (val claim = WorkBuddyClient.claim(ready.accessToken, ready.uid, ready.domain)) {
            is WorkBuddyClient.Res.Ok -> {
                val current = if (status is WorkBuddyClient.Res.Ok) {
                    WbSession.merge(ready, status.value)
                } else {
                    ready
                }
                writeBack(refreshBalance(current))
                if (claim.value.alreadyCheckedIn) Result.AlreadyDone
                else Result.Claimed(claim.value.credit)
            }
            is WorkBuddyClient.Res.Err -> fail(ready, mapError(claim.error), claim.error.toString())
        }
    }

    /** 拉一次账户额度并并进快照；失败就保持原值，不影响签到结果 */
    private fun refreshBalance(account: WbAccount): WbAccount =
        when (val credit = WorkBuddyClient.creditSummary(account.accessToken, account.uid, account.domain)) {
            is WorkBuddyClient.Res.Ok -> WbSession.mergeBalance(account, credit.value)
            is WorkBuddyClient.Res.Err -> account
        }

    private fun writeBack(account: WbAccount) {
        WbRepository.update(account)
    }

    /**
     * 失败处理。只有「登录过期」才写 lastResult——这是持续性状态，用户需要看到；
     * 偶发超时/网络错误不覆盖上次的成功记录，避免卡片上一直挂着红字。
     */
    private fun fail(account: WbAccount, kind: Fail, detail: String = ""): Result {
        if (kind == Fail.EXPIRED) {
            WbRepository.update(account.copy(lastResult = "登录已过期，请重新登录"))
        }
        return Result.Failed(kind, detail)
    }

    private fun mapError(error: WorkBuddyClient.ApiError): Fail = when (error) {
        WorkBuddyClient.ApiError.Timeout -> Fail.TIMEOUT
        WorkBuddyClient.ApiError.Network -> Fail.NETWORK
        WorkBuddyClient.ApiError.Unauthorized -> Fail.EXPIRED
        WorkBuddyClient.ApiError.Parse -> Fail.PARSE
        is WorkBuddyClient.ApiError.Business -> Fail.BUSINESS
    }

    /**
     * 账号之间的间隔。失败后拉长到 3~5s，别立刻回头再打同一个域；
     * 自动模式更保守，手动模式给 1~3s 让用户等得不难受。
     */
    private fun gapOf(trigger: Trigger, result: Result): Long {
        val range = when {
            result is Result.Failed -> 3_000L..5_000L
            trigger == Trigger.AUTO -> 3_000L..8_000L
            else -> 1_000L..3_000L
        }
        return Random.nextLong(range.first, range.last + 1)
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        if (a <= 0L) return false
        val c1 = Calendar.getInstance().apply { timeInMillis = a }
        val c2 = Calendar.getInstance().apply { timeInMillis = b }
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
                c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
    }
}
