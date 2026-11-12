package com.itcast.ds_balance_viewer.checkin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.itcast.ds_balance_viewer.data.AutoCheckinPref

/**
 * 闹钟到点后的入口。
 *
 * 只做三件事：确认开关还开着 → 重排下一天的闹钟 → 决定是否启动执行服务。
 *
 * 「先重排再执行」是有意为之：即使执行阶段抛异常、进程被杀，
 * 第二天的闹钟也已经挂在系统里了，不会因为一次失败连累后面所有天。
 */
class CheckinAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        AutoCheckinPref.init(context)
        if (!AutoCheckinPref.enabled) return

        // 主闹钟和补偿闹钟共用这一个 Receiver，重排会把两个都换掉
        CheckinScheduler.reschedule(context)

        // 今天已经签过了（手动签、或上一次补偿闹钟成功），不再打接口
        if (AutoCheckinPref.isTodayDone()) {
            AutoCheckinPref.lastResult = context.getString(com.itcast.ds_balance_viewer.R.string.auto_checkin_skipped)
            return
        }

        CheckinService.start(context)
    }
}
