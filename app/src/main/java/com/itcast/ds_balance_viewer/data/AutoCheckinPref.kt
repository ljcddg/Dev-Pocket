package com.itcast.ds_balance_viewer.data

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 自动签到的配置与运行记录。
 *
 * 用普通 SharedPreferences：这里只有「几点签、要不要浮动」这类设置项，
 * 没有凭据，不需要走 SecureStore。账号令牌仍然只在 SecureStore 里。
 *
 * 这些值全部由 [com.itcast.ds_balance_viewer.checkin.CheckinScheduler] 读取，
 * 所以每次开机 / 时区变化后都能按同一份配置重排闹钟。
 */
object AutoCheckinPref {

    private const val FILE = "auto_checkin"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val KEY_JITTER = "jitter"
    private const val KEY_WEEKDAY_ONLY = "weekday_only"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_NOTIFY = "notify"
    private const val KEY_SEED = "seed"
    private const val KEY_NEXT_AT = "next_at"
    private const val KEY_LAST_RUN = "last_run"
    private const val KEY_LAST_RESULT = "last_result"
    private const val KEY_LAST_SUCCESS_DATE = "last_success_date"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (this::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        // 安装时生成一次随机种子，之后每台设备的浮动序列都不一样
        if (!prefs.contains(KEY_SEED)) {
            prefs.edit().putString(KEY_SEED, UUID.randomUUID().toString()).apply()
        }
    }

    // ---------------- 配置 ----------------

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** 用户设定的签到时刻（小时），默认 08:00 */
    var hour: Int
        get() = prefs.getInt(KEY_HOUR, 8)
        set(value) = prefs.edit().putInt(KEY_HOUR, value.coerceIn(0, 23)).apply()

    var minute: Int
        get() = prefs.getInt(KEY_MINUTE, 0)
        set(value) = prefs.edit().putInt(KEY_MINUTE, value.coerceIn(0, 59)).apply()

    /** 随机浮动的最大延后分钟数：0 / 30 / 60 */
    var jitterMinutes: Int
        get() = prefs.getInt(KEY_JITTER, 30)
        set(value) = prefs.edit().putInt(KEY_JITTER, value.coerceIn(0, 120)).apply()

    /** 只在周一到周五执行，周末跳过 */
    var weekdayOnly: Boolean
        get() = prefs.getBoolean(KEY_WEEKDAY_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_WEEKDAY_ONLY, value).apply()

    /**
     * 开机后是否自动恢复签到闹钟。
     *
     * 这是应用内的开关，只控制 BOOT_COMPLETED 广播的处理；
     * 厂商系统的「自启动管理」是另一层，App 无法自己开关，只能在设置页引导用户去放行。
     * 关掉它不会影响「打开 App 时按配置重排闹钟」——那是用户主动打开应用后的正常行为。
     */
    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    /** 执行完是否发通知（关闭后只有打开 App 才看得到结果） */
    var notifyResult: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY, value).apply()

    /** 每台设备固定的浮动种子，保证同一天算出的偏移可复现、对服务端不可预测 */
    val seed: String
        get() = prefs.getString(KEY_SEED, "") ?: ""

    // ---------------- 运行记录 ----------------

    /** 下一次计划触发时刻，仅用于界面展示 */
    var nextTriggerAt: Long
        get() = prefs.getLong(KEY_NEXT_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_NEXT_AT, value).apply()

    var lastRunAt: Long
        get() = prefs.getLong(KEY_LAST_RUN, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_RUN, value).apply()

    var lastResult: String
        get() = prefs.getString(KEY_LAST_RESULT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_RESULT, value).apply()

    /** 最近一次成功签到的日期（yyyy-MM-dd），用于判断「今天是不是已经签过」 */
    var lastSuccessDate: String
        get() = prefs.getString(KEY_LAST_SUCCESS_DATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_SUCCESS_DATE, value).apply()

    fun isTodayDone(): Boolean = lastSuccessDate == today()

    fun markTodayDone() {
        lastSuccessDate = today()
    }

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
