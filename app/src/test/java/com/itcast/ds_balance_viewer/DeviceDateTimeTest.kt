package com.itcast.ds_balance_viewer

import com.itcast.ds_balance_viewer.net.DeviceDateTime
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDateTimeTest {
    private val instant = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(2026, Calendar.NOVEMBER, 17, 16, 15, 0)
    }.timeInMillis

    @Test fun usesPhoneTimeZoneAcrossMidnight() {
        val shanghai = DeviceDateTime(instant, TimeZone.getTimeZone("Asia/Shanghai"))
        val losAngeles = DeviceDateTime(instant, TimeZone.getTimeZone("America/Los_Angeles"))

        assertEquals("今天是 2026 年 11 月 18 日，星期三。", shanghai.calendarAnswer("今天周几"))
        assertEquals("今天是 2026 年 11 月 17 日，星期二。", losAngeles.calendarAnswer("今天是星期几？"))
        assertEquals("昨天是 2026 年 11 月 17 日，星期二。", shanghai.calendarAnswer("昨天几号"))
        assertTrue(shanghai.systemContext().contains("2026-11-18 00:15:00 星期三 UTC+0800"))
        assertTrue(shanghai.systemContext().contains("Asia/Shanghai"))
    }

    @Test fun leavesCompoundQuestionsToTheModel() {
        val device = DeviceDateTime(instant, TimeZone.getTimeZone("Asia/Shanghai"))
        assertNull(device.calendarAnswer("今天星期几，天气怎么样？"))
        assertNull(device.calendarAnswer("2026 年国庆节是星期几？"))
    }
}
