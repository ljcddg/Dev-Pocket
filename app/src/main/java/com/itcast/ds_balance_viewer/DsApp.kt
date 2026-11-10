package com.itcast.ds_balance_viewer

import android.app.Application
import com.itcast.ds_balance_viewer.data.KeyRepository
import com.itcast.ds_balance_viewer.data.ThemePref
import com.itcast.ds_balance_viewer.data.WbRepository

/**
 * 应用入口。在进程启动时完成加密存储与两个数据仓库的初始化，
 * 后续所有 Activity / Fragment 都可以直接使用，无需重复初始化。
 * 外观偏好也要在这里应用，保证冷启动时第一帧就是用户选定的深浅色。
 */
class DsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        KeyRepository.init(this)
        WbRepository.init(this)
        ThemePref.init(this)
        ThemePref.apply()
    }
}
