package com.itcast.ds_balance_viewer.net

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

internal class DeviceDateTime(
    nowMillis: Long = System.currentTimeMillis(),
    private val zone: TimeZone = TimeZone.getDefault()
) {
    private val calendar = Calendar.getInstance(zone, Locale.CHINA).apply {
        timeInMillis = nowMillis
    }

    fun systemContext(): String {
        val timestamp = format(calendar, "yyyy-MM-dd HH:mm:ss EEEE 'UTC'Z")
        return "发起请求时，用户设备的本地时间是 $timestamp，时区为 ${zone.id}。" +
            "解释「今天」「昨天」「明天」等相对日期时，以这组时间和时区为准。检索资料中的日期只用于判断资料本身的时间。"
    }

    fun calendarAnswer(question: String): String? {
        val match = calendarQuestion.matchEntire(question.trim()) ?: return null
        val day = match.groupValues[1]
        val offset = when (day) {
            "前天" -> -2
            "昨天" -> -1
            "明天" -> 1
            "后天" -> 2
            else -> 0
        }
        val date = (calendar.clone() as Calendar).apply { add(Calendar.DATE, offset) }
        val label = day.ifEmpty { "今天" }
        return "${label}是 ${format(date, "yyyy 年 M 月 d 日")}，${format(date, "EEEE")}。"
    }

    private fun format(value: Calendar, pattern: String): String =
        SimpleDateFormat(pattern, Locale.CHINA).apply { timeZone = zone }.format(value.time)

    private companion object {
        // 仅直接回答完整的日历问句，带有其他上下文的问题继续交给模型。
        val calendarQuestion = Regex(
            """(今天|昨天|明天|前天|后天|现在)?\s*(?:是\s*)?(?:星期几|礼拜几|周几|几月几[日号]|几号|什么日期)\s*(?:呀|啊|呢|吗)?\s*[？?。！!]*"""
        )
    }
}
