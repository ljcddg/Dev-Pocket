package com.itcast.ds_balance_viewer.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 连续签到天数的本地计算。
 *
 * 背景：服务端 checkin-activity-status 返回的 streak_days 实际是「签到总天数」，
 * 不是连续天数。真正连续的依据是 checkin_dates（已签到日期列表），
 * 所以连签在本地数：以今天（或昨天，若今天还没签）为终点，往回逐天核对。
 */
object Streak {

    /**
     * 从已签到日期列表计算连续签到天数。
     *
     * 规则：今天已签则从今天往回数；今天未签时从昨天往回数（连签在昨天断掉才算断）。
     * 例：今天 11-14，dates 里有 11-12~11-13 而没有 11-14 → 连签 2 天。
     */
    fun consecutiveDays(dates: List<String>, now: Long = System.currentTimeMillis()): Int {
        if (dates.isEmpty()) return 0
        val set = dates.toHashSet()
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        if (!set.contains(key(cal))) cal.add(Calendar.DAY_OF_YEAR, -1)
        var count = 0
        while (set.contains(key(cal))) {
            count++
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return count
    }

    /** SimpleDateFormat 非线程安全，每次调用单独创建，避免 IO 线程并发时结果错乱 */
    private fun key(cal: Calendar): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
}
