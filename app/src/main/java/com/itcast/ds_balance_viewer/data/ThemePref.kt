package com.itcast.ds_balance_viewer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/**
 * 外观（深浅色）偏好。
 *
 * 三档：跟随系统 / 浅色 / 深色，存 SharedPreferences（这里不是敏感信息，不需要加密）。
 * 修改后立刻调用 [apply] 让 AppCompatDelegate 生效，系统会重建已打开的界面。
 */
object ThemePref {

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    private const val FILE = "ds_theme"
    private const val KEY_MODE = "mode"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    var mode: Int
        get() = prefs?.getInt(KEY_MODE, MODE_SYSTEM) ?: MODE_SYSTEM
        set(value) {
            prefs?.edit()?.putInt(KEY_MODE, value)?.apply()
        }

    /** 把偏好映射为 AppCompat 夜间模式并立刻生效 */
    fun apply(force: Boolean = false) {
        val night = when (mode) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (force || AppCompatDelegate.getDefaultNightMode() != night) {
            AppCompatDelegate.setDefaultNightMode(night)
        }
    }
}
