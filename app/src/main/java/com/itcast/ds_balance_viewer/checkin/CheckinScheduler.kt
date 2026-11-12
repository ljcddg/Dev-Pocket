package com.itcast.ds_balance_viewer.checkin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.itcast.ds_balance_viewer.data.AutoCheckinPref
import java.util.Calendar
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 自动签到的调度层：算出「今天到底几点执行」，然后交给系统闹钟。
 *
 * 关键设计：**随机浮动在调度时算好，不在执行时 sleep**。
 * Android 后台进程活不过一分钟，`Thread.sleep(30 分钟)` 一定会被 Doze 或
 * JobScheduler 掐掉。所以这里把 `T + δ` 一次算完，直接把精确闹钟定在那个时刻，
 * 效果与「到点后随机等一会儿」完全等价，但没有保活成本。
 *
 * δ 的算法：
 *  - 三角分布而不是均匀分布：人的签到时刻是「有峰值的」，平坦随机本身就是机器特征
 *  - 区间 [-12, +jitter]，众数 0，整体偏后
 *  - 周末再往后推 25~45 分钟
 *  - 秒级抖动 0~90s，避免每天落在同一秒
 *  - 同一天的结果由 `种子 + 日期` 决定，可复现、对服务端不可预测
 */
object CheckinScheduler {

    const val ACTION_RUN = "com.itcast.ds_balance_viewer.action.AUTO_CHECKIN"

    private const val REQ_MAIN = 1001
    private const val REQ_BACKUP = 1002

    /** 主闹钟没成功（Doze、断网、进程被杀）时的补偿闹钟，晚 90 分钟 */
    private const val BACKUP_DELAY_MS = 90 * 60 * 1000L

    /** 最早不早于设定时间前 12 分钟 */
    private const val LEAD_MINUTES = -12
    private const val LATE_LIMIT_HOUR = 23
    private const val LATE_LIMIT_MINUTE = 45

    // ---------------- 对外入口 ----------------

    /** 按当前配置重排闹钟：关闭时不排，开启时排主闹钟 + 补偿闹钟 */
    fun reschedule(context: Context) {
        val ctx = context.applicationContext
        AutoCheckinPref.init(ctx)
        cancel(ctx)
        if (!AutoCheckinPref.enabled) {
            AutoCheckinPref.nextTriggerAt = 0L
            return
        }
        val at = nextTriggerAt(System.currentTimeMillis())
        setAlarm(ctx, at, REQ_MAIN)
        setAlarm(ctx, at + BACKUP_DELAY_MS, REQ_BACKUP)
        AutoCheckinPref.nextTriggerAt = at
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, REQ_MAIN))
        am.cancel(pendingIntent(context, REQ_BACKUP))
    }

    /**
     * 下一次触发时刻（毫秒）。今天的时间窗已经过去就顺延到明天；
     * 开了「仅工作日」时跳过周六日。调用前需确保 [AutoCheckinPref.init] 已执行。
     */
    fun nextTriggerAt(now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        for (i in 0..7) {
            val candidate = triggerFor(cal.timeInMillis)
            val usable = candidate > now + 60_000L &&
                    !(AutoCheckinPref.weekdayOnly && isWeekend(candidate))
            if (usable) return candidate
            // 换到第二天再算，用正午做基准避免月末/时区边界问题
            cal.add(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 12)
        }
        // 兜底：理论上走不到，给明天同一时刻
        return triggerFor(now) + 24 * 60 * 60 * 1000L
    }

    // ---------------- 权限与系统设置入口 ----------------

    /** Android 12+ 起精确闹钟要单独授权，14 起新装默认关闭 */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 电池优化白名单：不申请的话，深度休眠会让闹钟延后甚至不响 */
    fun openBatterySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    // ---------------- 时间计算 ----------------

    /** 某一天的实际触发时刻：设定时间 + 浮动偏移 + 秒级抖动 */
    private fun triggerFor(dayMillis: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = dayMillis
            set(Calendar.HOUR_OF_DAY, AutoCheckinPref.hour)
            set(Calendar.MINUTE, AutoCheckinPref.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dateKey = cal.get(Calendar.YEAR) * 10000 +
                (cal.get(Calendar.MONTH) + 1) * 100 +
                cal.get(Calendar.DAY_OF_MONTH)

        // 同一天同一个种子永远算出同一个偏移：可复现，但外部无法反推
        val rnd = Random(AutoCheckinPref.seed.hashCode().toLong() * 1_000_003L + dateKey)
        val jitterMax = AutoCheckinPref.jitterMinutes.coerceAtLeast(0)

        var offset = triangular(LEAD_MINUTES, jitterMax, 0, rnd.nextDouble()).toInt()
        if (isWeekend(cal.timeInMillis)) offset += 25 + rnd.nextInt(21)
        val seconds = rnd.nextInt(91)

        val earliest = Calendar.getInstance().apply {
            timeInMillis = cal.timeInMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 10)
            set(Calendar.SECOND, 0)
        }
        val latest = Calendar.getInstance().apply {
            timeInMillis = cal.timeInMillis
            set(Calendar.HOUR_OF_DAY, LATE_LIMIT_HOUR)
            set(Calendar.MINUTE, LATE_LIMIT_MINUTE)
            set(Calendar.SECOND, 0)
        }

        cal.add(Calendar.MINUTE, offset)
        cal.add(Calendar.SECOND, seconds)
        return cal.timeInMillis.coerceIn(earliest.timeInMillis, latest.timeInMillis)
    }

    /** 三角分布：有峰值，比 uniform 更像人的作息 */
    private fun triangular(min: Int, max: Int, mode: Int, r: Double): Double {
        if (max <= min) return min.toDouble()
        val m = mode.coerceIn(min, max)
        val c = (m - min).toDouble() / (max - min)
        return if (r < c) {
            min + sqrt(r * (max - min) * (m - min))
        } else {
            max - sqrt((1 - r) * (max - min) * (max - m))
        }
    }

    private fun isWeekend(millis: Long): Boolean {
        val dow = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK)
        return dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
    }

    // ---------------- AlarmManager ----------------

    private fun setAlarm(context: Context, at: Long, requestCode: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, requestCode)
        val exact = canScheduleExact(context)
        try {
            if (exact) {
                // 允许在 Doze 下唤醒；一天一两次，远低于系统 9 分钟限频
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                // 没拿到精确闹钟权限时降级：仍会唤醒，但可能被系统推迟几分钟到几十分钟
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun pendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, CheckinAlarmReceiver::class.java).apply {
            action = ACTION_RUN
            putExtra(EXTRA_BACKUP, requestCode == REQ_BACKUP)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun packageUri(context: Context): Uri = Uri.parse("package:${context.packageName}")

    const val EXTRA_BACKUP = "backup_alarm"
}
