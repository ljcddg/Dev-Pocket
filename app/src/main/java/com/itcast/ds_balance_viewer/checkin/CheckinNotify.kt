package com.itcast.ds_balance_viewer.checkin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.itcast.ds_balance_viewer.R
import com.itcast.ds_balance_viewer.ui.MainActivity

/**
 * 自动签到的通知。
 *
 * 只用一条低优先级渠道：
 *  - 执行中：前台服务必需的常驻通知（Android 8+ 后台跑服务必须给用户一个可见入口）
 *  - 结果：可关闭，关掉后只有打开 App 才看得到
 *
 * 文案刻意中性，不出现「脚本」「自动点击」这类词——通知会在锁屏上显示。
 */
object CheckinNotify {

    const val CHANNEL_ID = "auto_checkin"
    const val NOTIF_RUNNING = 2001
    const val NOTIF_RESULT = 2002

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.auto_checkin_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.auto_checkin_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    /** Android 13+ 通知要用户授权，没授权时静默跳过，不要打断签到流程 */
    fun isAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun buildRunning(context: Context, text: String): Notification =
        base(context)
            .setContentTitle(context.getString(R.string.auto_checkin_running_title))
            .setContentText(text)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

    fun showResult(context: Context, text: String) {
        if (!isAllowed(context)) return
        val notification = base(context)
            .setContentTitle(context.getString(R.string.auto_checkin_result_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIF_RESULT, notification)
        }
    }

    private fun base(context: Context): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_checkin)
            .setColor(ContextCompat.getColor(context, R.color.ds_primary))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
