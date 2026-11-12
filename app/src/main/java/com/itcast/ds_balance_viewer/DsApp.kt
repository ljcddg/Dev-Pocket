package com.itcast.ds_balance_viewer

import android.app.Application
import com.itcast.ds_balance_viewer.checkin.CheckinScheduler
import com.itcast.ds_balance_viewer.data.AutoCheckinPref
import com.itcast.ds_balance_viewer.data.KeyRepository
import com.itcast.ds_balance_viewer.data.ThemePref
import com.itcast.ds_balance_viewer.data.WbRepository

/**
 * 应用入口。在进程启动时完成加密存储与两个数据仓库的初始化，
 * 后续所有 Activity / Fragment 都可以直接使用，无需重复初始化。
 * 外观偏好也要在这里应用，保证冷启动时第一帧就是用户选定的深浅色。
 *
 * 另外这里会把自动签到的闹钟按当前配置重挂一次：
 * 系统闹钟不跨重启保留，而「用户主动打开了 App」是最可靠的恢复时机。
 */
class DsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        KeyRepository.init(this)
        WbRepository.init(this)
        ThemePref.init(this)
        ThemePref.apply()

        AutoCheckinPref.init(this)
        CheckinScheduler.reschedule(this)
    }
}
