package com.itcast.ds_balance_viewer.checkin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.itcast.ds_balance_viewer.data.AutoCheckinPref

/**
 * 开机、应用更新、改时间、换时区时把闹钟重新挂上。
 *
 * 系统闹钟不会跨重启保留，这四类广播是唯一的补救机会：
 *  - BOOT_COMPLETED：关机重启
 *  - MY_PACKAGE_REPLACED：覆盖安装后所有闹钟被清空
 *  - TIME_SET / TIMEZONE_CHANGED：用户手动改时间或跨时区，原来的绝对时刻已经错了
 *
 * 开机这一类受用户开关控制（[AutoCheckinPref.autoStartOnBoot]）：
 * 关掉之后重启设备不会自动恢复，需要手动打开一次 App。
 * 另外三类不受开关影响——那不是「开机自启」，是把已经失效的闹钟纠正回来。
 */
class CheckinBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        AutoCheckinPref.init(context)
        if (!AutoCheckinPref.enabled) return

        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                if (!AutoCheckinPref.autoStartOnBoot) {
                    // 明确把「下次执行」清空，界面上一眼能看出没恢复
                    AutoCheckinPref.nextTriggerAt = 0L
                    AutoCheckinPref.lastResult =
                        context.getString(com.itcast.ds_balance_viewer.R.string.auto_checkin_boot_disabled)
                    return
                }
                CheckinScheduler.reschedule(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> CheckinScheduler.reschedule(context)
        }
    }
}
