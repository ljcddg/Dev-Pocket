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
import kotlin.random.Random

/**
 * 自动签到的调度层：算出「今天到底几点开始」，然后交给系统闹钟。
 *
 * 设计（v1.8 起，随机浮动逻辑重构）：
 *  - **闹钟只负责「几点开始」**：定在用户设定时刻 + 0~90 秒的每日抖动。
 *    抖动由 `种子 + 日期` 决定：同一天内可复现（重排闹钟不会漂移），对服务端不可预测。
 *  - **随机浮动在执行阶段完成**：闹钟响后 [CheckinService] 串行签到，
 *    每个账号开始前独立随机等待几秒（档位见设置「随机浮动」）。
 *    这比旧版「调度时一次性把整批偏移几分钟」更接近真人：真人是一个一个来、
 *    每个账号之间的间隔都不一样，而不是所有账号挤在同一时刻附近。
 *  - shortService 有 3 分钟上限，执行层自带预算控制（见 CheckinEngine）。
 *  - 主闹钟失败（Doze、断网、进程被杀）时，90 分钟后还有一个补偿闹钟。
 */
object CheckinScheduler {

    const val ACTION_RUN = "com.itcast.ds_balance_viewer.action.AUTO_CHECKIN"

    private const val REQ_MAIN = 1001
    private const val REQ_BACKUP = 1002

    /** 主闹钟没成功（Doze、断网、进程被杀）时的补偿闹钟，晚 90 分钟 */
    private const val BACKUP_DELAY_MS = 90 * 60 * 1000L

    /** 每日秒级抖动上限：避免每天落在同一秒，又不至于把签到拖离设定时刻太远 */
    private const val JITTER_SECONDS = 90

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
     * 下一次触发时刻（毫秒）。今天的时间窗已经过去就顺延到明天。
     * 调用前需确保 [AutoCheckinPref.init] 已执行。
     */
    fun nextTriggerAt(now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        for (i in 0..7) {
            val candidate = triggerFor(cal.timeInMillis)
            if (candidate > now + 60_000L) return candidate
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

    /** 某一天的开始时刻：设定时间 + 每日 0~90 秒抖动（种子+日期，当天稳定） */
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

        val rnd = Random(AutoCheckinPref.seed.hashCode().toLong() * 1_000_003L + dateKey)
        cal.add(Calendar.SECOND, rnd.nextInt(JITTER_SECONDS + 1))
        return cal.timeInMillis
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
