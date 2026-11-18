package com.itcast.ds_balance_viewer.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itcast.ds_balance_viewer.BuildConfig
import com.itcast.ds_balance_viewer.R
import com.itcast.ds_balance_viewer.data.UpdatePref
import com.itcast.ds_balance_viewer.net.UpdateClient
import com.itcast.ds_balance_viewer.util.AppExecutors
import java.io.File
import java.util.Locale

/**
 * 检查更新与安装的编排层。
 *
 *  - 自动检查：App 冷启动时静默查一次（24 小时内最多一次），有新版才弹窗，
 *    失败完全不出声——更新检查永远不该打断正常使用
 *  - 手动检查：设置页「检查更新」，结果有 Toast 反馈
 *  - 下载：DownloadManager 下载到应用外部私有目录，通知栏可见进度
 *  - 安装：下载完成后由 [DownloadCompleteReceiver] 拉起系统安装界面
 */
object UpdateManager {

    // ---------------- 检查 ----------------

    /** App 启动时调用：后台静默检查，有新版本才弹窗 */
    fun maybeAutoCheck(activity: AppCompatActivity) {
        if (!UpdatePref.shouldAutoCheck()) return
        UpdatePref.lastCheckAt = System.currentTimeMillis()
        AppExecutors.io.execute {
            val res = UpdateClient.latest()
            if (res is UpdateClient.Res.Ok &&
                UpdateClient.isNewer(BuildConfig.VERSION_NAME, res.value.version)
            ) {
                AppExecutors.main {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        showDialog(activity, res.value)
                    }
                }
            }
        }
    }

    /** 设置页手动检查：始终有反馈（Toast / 弹窗） */
    fun checkManually(context: Context) {
        Toast.makeText(context, R.string.update_checking, Toast.LENGTH_SHORT).show()
        AppExecutors.io.execute {
            val res = UpdateClient.latest()
            AppExecutors.main {
                val ctx = context.applicationContext
                when {
                    res is UpdateClient.Res.Ok &&
                            UpdateClient.isNewer(BuildConfig.VERSION_NAME, res.value.version) ->
                        showDialog(context, res.value)
                    res is UpdateClient.Res.Ok ->
                        Toast.makeText(ctx, R.string.update_latest, Toast.LENGTH_SHORT).show()
                    else ->
                        Toast.makeText(ctx, R.string.update_check_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------------- 弹窗 ----------------

    /** 新版本弹窗：立即下载 / 跳过 */
    fun showDialog(context: Context, release: UpdateClient.Release) {
        val notes = release.notes.trim().ifBlank { context.getString(R.string.update_notes_none) }
        val sizePart = if (release.apkSize > 0) {
            "\n\n" + context.getString(
                R.string.update_size,
                String.format(Locale.US, "%.1f MB", release.apkSize / 1024.0 / 1024.0)
            )
        } else {
            ""
        }
        // Release 说明可能很长，弹窗里截断，完整内容去 GitHub 看
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.update_found_title, release.version.removePrefix("v")))
            .setMessage(notes.take(1200) + sizePart)
            .setPositiveButton(R.string.update_download) { _, _ -> download(context, release) }
            .setNegativeButton(R.string.update_skip, null)
            .show()
    }

    // ---------------- 下载与安装 ----------------

    /** 用系统 DownloadManager 下载 apk，进度直接走系统通知，App 无需常驻 */
    fun download(context: Context, release: UpdateClient.Release) {
        val ctx = context.applicationContext
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(release.apkUrl)).apply {
            setMimeType("application/vnd.android.package-archive")
            setTitle(ctx.getString(R.string.update_notif_title, release.version))
            setDescription(release.apkName)
            // 应用外部私有目录：不需要存储权限，FileProvider 也能直接授权给安装器
            setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, release.apkName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }
        UpdatePref.pendingDownloadId = dm.enqueue(request)
        UpdatePref.pendingVersion = release.version
        Toast.makeText(ctx, R.string.update_download_started, Toast.LENGTH_LONG).show()
    }

    /** 下载完成的收尾：校验是我们自己的任务且成功，再拉起安装界面 */
    @Synchronized
    fun handleDownloadComplete(context: Context, id: Long) {
        if (id <= 0L || id != UpdatePref.pendingDownloadId) return

        val ctx = context.applicationContext
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return
            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            when (cursor.getInt(statusIdx)) {
                DownloadManager.STATUS_FAILED -> {
                    if (UpdatePref.pendingDownloadId == id) UpdatePref.pendingDownloadId = 0L
                    return
                }
                DownloadManager.STATUS_SUCCESSFUL -> Unit
                else -> return
            }
            val localUri = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI))
                ?: return
            val path = Uri.parse(localUri).path ?: return
            val file = File(path)
            if (!file.exists() || file.length() <= 0L) return
            if (!isAppUpdate(ctx, file)) {
                if (UpdatePref.pendingDownloadId == id) UpdatePref.pendingDownloadId = 0L
                return
            }

            val apkUri = FileProvider.getUriForFile(
                ctx,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (UpdatePref.pendingDownloadId != id) return
            UpdatePref.pendingDownloadId = 0L
            runCatching { ctx.startActivity(intent) }
        }
    }

    @Suppress("DEPRECATION")
    private fun isAppUpdate(context: Context, file: File): Boolean {
        val packages = context.packageManager
        val flags = android.content.pm.PackageManager.GET_SIGNATURES
        return runCatching {
            val current = packages.getPackageInfo(context.packageName, flags)
            val candidate = packages.getPackageArchiveInfo(file.path, flags) ?: return false
            val installedSigners = current.signatures.orEmpty().toSet()
            candidate.packageName == current.packageName && installedSigners.isNotEmpty() &&
                candidate.signatures.orEmpty().toSet() == installedSigners
        }.getOrDefault(false)
    }
}

/** DownloadManager 下载完成后由系统广播唤起，转交给 [UpdateManager] 处理 */
class DownloadCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id <= 0L || UpdatePref.pendingDownloadId != id) return
        val pending = goAsync()
        com.itcast.ds_balance_viewer.util.AppExecutors.io.execute {
            try {
                UpdateManager.handleDownloadComplete(context.applicationContext, id)
            } finally {
                pending.finish()
            }
        }
    }
}
