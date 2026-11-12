package com.itcast.ds_balance_viewer.checkin

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.itcast.ds_balance_viewer.R
import com.itcast.ds_balance_viewer.data.AutoCheckinPref
import com.itcast.ds_balance_viewer.data.CheckinEngine
import com.itcast.ds_balance_viewer.util.AppExecutors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自动签到的执行载体。
 *
 * 为什么用短期前台服务而不是 WorkManager：
 *  - 项目刻意保持零第三方架构组件，WorkManager 会带进 lifecycle / sqlite 一整套
 *  - 前台服务能让系统在 Doze 里认账，且 `shortService` 类型天然限制在 3 分钟内，
 *    正好匹配「几次 HTTP 请求」这种量级
 *
 * 执行时必须走 [AppExecutors.checkin]（单线程），和手动签到共用同一把互斥锁：
 * 用户正好在自动签到跑的时候点一键签到，两边不会同时打接口。
 */
class CheckinService : Service() {

    private val running = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running.compareAndSet(false, true)) {
            // 已经在跑：仍要调用 startForeground 满足系统的 5 秒契约，但不再重复执行
            startForeground(
                CheckinNotify.NOTIF_RUNNING,
                CheckinNotify.buildRunning(this, getString(R.string.auto_checkin_running_text))
            )
            return START_NOT_STICKY
        }

        AutoCheckinPref.init(this)
        CheckinNotify.ensureChannel(this)
        startForeground(
            CheckinNotify.NOTIF_RUNNING,
            CheckinNotify.buildRunning(this, getString(R.string.auto_checkin_running_text))
        )

        AppExecutors.checkin.execute {
            val report = runCatching {
                CheckinEngine.runAll(CheckinEngine.Trigger.AUTO)
            }.getOrDefault(emptyMap())

            val summary = summarize(report)
            val failed = report.values.count { it is CheckinEngine.Result.Failed }
            AutoCheckinPref.lastRunAt = System.currentTimeMillis()
            AutoCheckinPref.lastResult = summary
            // 这一轮没有任何失败才标记「今天已签」，补偿闹钟就不会再打一遍
            if (report.isNotEmpty() && failed == 0) AutoCheckinPref.markTodayDone()

            AppExecutors.main {
                if (AutoCheckinPref.notifyResult) CheckinNotify.showResult(this, summary)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                running.set(false)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        super.onDestroy()
    }

    /** 与手动批量签到共用同一套文案，避免两条路径说话不一致 */
    private fun summarize(report: Map<String, CheckinEngine.Result>): String {
        if (report.isEmpty()) return getString(R.string.auto_checkin_nothing)

        var claimed = 0
        var credit = 0
        var skipped = 0
        var failed = 0
        report.values.forEach { result ->
            when (result) {
                is CheckinEngine.Result.Claimed -> {
                    claimed++
                    credit += result.credit
                }
                CheckinEngine.Result.AlreadyDone -> skipped++
                is CheckinEngine.Result.Failed -> failed++
            }
        }

        val text = StringBuilder()
        if (claimed > 0) {
            text.append(
                if (credit > 0) getString(R.string.wb_checkin_result, claimed, credit)
                else getString(R.string.wb_checkin_result_nocredit, claimed)
            )
        }
        if (skipped > 0) text.append(getString(R.string.wb_checkin_result_skipped, skipped))
        if (failed > 0) text.append(getString(R.string.wb_checkin_result_failed, failed))
        return if (text.isEmpty()) getString(R.string.auto_checkin_nothing) else text.toString()
    }

    companion object {

        /**
         * 从后台（广播接收器）拉起服务。Android 12+ 对后台启动前台服务有严格限制，
         * 但「精确闹钟触发」属于系统豁免场景，正常情况下不会被拦。
         * 万一被拦，把原因写进运行记录，用户在设置页能直接看到。
         */
        fun start(context: Context) {
            val intent = Intent(context, CheckinService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                AutoCheckinPref.lastResult =
                    context.getString(R.string.auto_checkin_blocked)
            }
        }
    }
}
