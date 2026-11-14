package com.itcast.ds_balance_viewer.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 检查更新的配置与下载状态。
 *
 * 只有「上次检查时间」和「进行中的下载任务 id」这类设置项，没有敏感数据，
 * 走普通 SharedPreferences 即可。
 */
object UpdatePref {

    private const val FILE = "update"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_PENDING_ID = "pending_id"
    private const val KEY_PENDING_VERSION = "pending_version"

    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (this::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /** 上次自动检查的时间；自动检查一天最多一次，首次启动（无记录）必然检查 */
    var lastCheckAt: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK, value).apply()

    fun shouldAutoCheck(now: Long = System.currentTimeMillis()): Boolean =
        now - lastCheckAt >= CHECK_INTERVAL_MS

    /** 正在下载的 DownloadManager 任务 id；0 表示没有进行中的下载 */
    var pendingDownloadId: Long
        get() = prefs.getLong(KEY_PENDING_ID, 0L)
        set(value) = prefs.edit().putLong(KEY_PENDING_ID, value).apply()

    /** 进行中下载对应的版本号（仅用于展示与排查） */
    var pendingVersion: String
        get() = prefs.getString(KEY_PENDING_VERSION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PENDING_VERSION, value).apply()
}
