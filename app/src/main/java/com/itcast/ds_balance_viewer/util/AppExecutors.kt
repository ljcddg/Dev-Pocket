package com.itcast.ds_balance_viewer.util

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 全局线程池：网络请求都丢到这里，结果用 main{} 切回主线程更新 UI */
object AppExecutors {

    /** 固定 4 个线程，够并发刷新几个密钥又不至于把手机线程占满 */
    val io: ExecutorService = Executors.newFixedThreadPool(4)

    /**
     * 签到专用单线程池。批量签到必须串行、且账号之间要 sleep，
     * 丢进 [io] 会长期占住 4 个线程里的一个，把余额刷新饿死。
     */
    val checkin: ExecutorService = Executors.newSingleThreadExecutor()

    private val mainHandler = Handler(Looper.getMainLooper())

    fun main(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}

/**
 * 展示层格式化工具：密钥脱敏、金额千分位、时间戳。
 * 与 Python 版脚本保持一致的脱敏规则，保证两端看到的掩码形式一样。
 */
object Formatting {

    /** sk-dbc...bfaa 形式，保留足够区分度又不会泄露完整密钥 */
    fun mask(secret: String): String = when {
        secret.isEmpty() -> "(空)"
        secret.length <= 8 -> secret.take(2) + "***"
        secret.length <= 16 -> "${secret.take(5)}...${secret.takeLast(4)}"
        else -> "${secret.take(6)}...${secret.takeLast(4)}"
    }

    /** 金额格式化：CNY 用 ¥，USD 用 $，其余显示币种代码 */
    fun money(value: String?, currency: String?): String {
        val symbol = when (currency) {
            "CNY" -> "¥"
            "USD" -> "$"
            null, "" -> "¥"
            else -> "$currency "
        }
        val number = value?.toDoubleOrNull()?.let {
            String.format(Locale.US, "%,.2f", it)
        } ?: (value ?: "-")
        return "$symbol$number"
    }

    /** 积分千分位：1250 → 1,250；-1 表示还没查到，显示占位符 */
    fun credit(value: Int): String =
        if (value < 0) "—" else String.format(Locale.US, "%,d", value)

    /** 积分格式化（余额是长整型，服务端容量值可能超过 Int 范围） */
    fun credit(value: Long): String =
        if (value < 0) "—" else String.format(Locale.US, "%,d", value)

    /** 简洁时间：10-13 14:07；同一天只显示时分 */
    fun time(millis: Long): String {
        if (millis <= 0L) return ""
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }

    /** 相对时间：刚刚 / 5 分钟前 / 2 小时前 / 具体日期 */
    fun relativeTime(millis: Long): String {
        if (millis <= 0L) return ""
        val diff = System.currentTimeMillis() - millis
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour
        return when {
            diff < minute -> "刚刚"
            diff < hour -> "${diff / minute} 分钟前"
            diff < day -> "${diff / hour} 小时前"
            diff < 7 * day -> "${diff / day} 天前"
            else -> time(millis)
        }
    }
}
