package com.itcast.ds_balance_viewer.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itcast.ds_balance_viewer.BuildConfig
import com.itcast.ds_balance_viewer.R
import com.itcast.ds_balance_viewer.checkin.CheckinScheduler
import com.itcast.ds_balance_viewer.checkin.CheckinService
import com.itcast.ds_balance_viewer.data.AutoCheckinPref
import com.itcast.ds_balance_viewer.data.CheckinEngine
import com.itcast.ds_balance_viewer.data.KeyRepository
import com.itcast.ds_balance_viewer.data.ThemePref
import com.itcast.ds_balance_viewer.data.WbRepository
import com.itcast.ds_balance_viewer.databinding.FragmentSettingsBinding
import com.itcast.ds_balance_viewer.util.Formatting
import com.itcast.ds_balance_viewer.util.SystemBars
import java.util.Locale

/**
 * 「设置」Tab：外观、自动签到、启动刷新、密钥数量、关于与安全说明。
 *
 * 自动签到这一块只做「配置 + 立即试运行」，真正的调度在 [CheckinScheduler]，
 * 执行在 [CheckinService]——设置页关掉就什么都不该留，所以这里不持有任何任务状态。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    /** 初始化时同步 RadioGroup 会回调，这里挡掉避免误存 */
    private var bindingTheme = false

    private val notifyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 拒绝也不影响签到本身，只是看不到结果通知；这里不打扰用户
        if (_binding != null) renderSchedule()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        SystemBars.apply(binding.toolbar, binding.root)

        AutoCheckinPref.init(requireContext())

        setupTheme()
        setupAutoCheckin()
        setupBalance()

        binding.tvVersion.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null) return
        binding.tvKeyCount.text = getString(R.string.settings_key_count, KeyRepository.count())
        // 从系统设置页返回后，精确闹钟/电池优化的状态可能变了，重新渲染一次
        renderSchedule()
        renderRefreshInterval()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------------- 主题 ----------------

    private fun setupTheme() {
        bindingTheme = true
        when (ThemePref.mode) {
            ThemePref.MODE_LIGHT -> binding.radioLight.isChecked = true
            ThemePref.MODE_DARK -> binding.radioDark.isChecked = true
            else -> binding.radioSystem.isChecked = true
        }
        bindingTheme = false

        binding.radioTheme.setOnCheckedChangeListener { _, checkedId ->
            if (bindingTheme) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.radioLight -> ThemePref.MODE_LIGHT
                R.id.radioDark -> ThemePref.MODE_DARK
                else -> ThemePref.MODE_SYSTEM
            }
            if (mode == ThemePref.mode) return@setOnCheckedChangeListener
            ThemePref.mode = mode
            // 立即生效：AppCompatDelegate 会重建当前界面，重建后按新偏好重新勾选
            ThemePref.apply()
        }
    }

    // ---------------- 余额刷新 ----------------

    private fun setupBalance() {
        binding.switchAutoRefresh.isChecked = KeyRepository.autoRefresh
        binding.switchAutoRefresh.setOnCheckedChangeListener { _, checked ->
            KeyRepository.autoRefresh = checked
        }
        renderRefreshInterval()
        binding.rowRefreshInterval.setOnClickListener { pickRefreshInterval() }
    }

    private fun renderRefreshInterval() {
        val minutes = KeyRepository.refreshIntervalMinutes
        binding.tvRefreshInterval.text = when (minutes) {
            0 -> getString(R.string.refresh_interval_off)
            1 -> getString(R.string.refresh_interval_1)
            5 -> getString(R.string.refresh_interval_5)
            30 -> getString(R.string.refresh_interval_30)
            60 -> getString(R.string.refresh_interval_60)
            else -> getString(R.string.refresh_interval_label, minutes)
        }
    }

    private fun pickRefreshInterval() {
        val values = intArrayOf(0, 1, 5, 30, 60)
        val labels = arrayOf(
            getString(R.string.refresh_interval_off),
            getString(R.string.refresh_interval_1),
            getString(R.string.refresh_interval_5),
            getString(R.string.refresh_interval_30),
            getString(R.string.refresh_interval_60),
            getString(R.string.refresh_interval_custom)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_refresh_interval)
            .setItems(labels) { _, which ->
                if (which < values.size) {
                    KeyRepository.refreshIntervalMinutes = values[which]
                    renderRefreshInterval()
                } else {
                    askCustomInterval()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 「自定义…」：允许 1~1440 分钟，超出范围直接夹紧，不弹二次错误 */
    private fun askCustomInterval() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(KeyRepository.refreshIntervalMinutes.coerceAtLeast(1).toString())
            setSelection(text.length)
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.refresh_interval_custom)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val minutes = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                KeyRepository.refreshIntervalMinutes = minutes.coerceIn(1, 24 * 60)
                renderRefreshInterval()
            }
            .show()
    }

    // ---------------- 自动签到 ----------------

    private fun setupAutoCheckin() {
        // 先把开关状态摆好，再挂监听，避免初始化触发的回调把值又写一遍
        binding.switchAutoCheckin.isChecked = AutoCheckinPref.enabled
        binding.switchWeekdayOnly.isChecked = AutoCheckinPref.weekdayOnly
        binding.switchBootStart.isChecked = AutoCheckinPref.autoStartOnBoot
        binding.switchNotifyResult.isChecked = AutoCheckinPref.notifyResult

        renderTime()
        renderJitter()

        binding.switchAutoCheckin.setOnCheckedChangeListener { _, checked ->
            AutoCheckinPref.enabled = checked
            if (checked) {
                requestNotifyPermission()
                CheckinScheduler.reschedule(requireContext())
            } else {
                CheckinScheduler.cancel(requireContext())
                AutoCheckinPref.nextTriggerAt = 0L
            }
            renderSchedule()
        }

        binding.switchWeekdayOnly.setOnCheckedChangeListener { _, checked ->
            AutoCheckinPref.weekdayOnly = checked
            rescheduleIfEnabled()
        }

        binding.switchBootStart.setOnCheckedChangeListener { _, checked ->
            AutoCheckinPref.autoStartOnBoot = checked
        }

        binding.switchNotifyResult.setOnCheckedChangeListener { _, checked ->
            AutoCheckinPref.notifyResult = checked
        }

        binding.rowTime.setOnClickListener { pickTime() }
        binding.rowJitter.setOnClickListener { pickJitter() }

        binding.btnRunNow.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            if (WbRepository.count() == 0) {
                Toast.makeText(ctx, R.string.auto_checkin_need_account, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (CheckinEngine.isRunning()) {
                Toast.makeText(ctx, R.string.wb_checkin_running, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            CheckinService.start(ctx)
            Toast.makeText(ctx, R.string.auto_checkin_run_now_started, Toast.LENGTH_SHORT).show()
        }

        renderSchedule()
    }

    private fun pickTime() {
        TimePickerDialog(
            requireContext(),
            { _, hour, minute ->
                AutoCheckinPref.hour = hour
                AutoCheckinPref.minute = minute
                renderTime()
                rescheduleIfEnabled()
            },
            AutoCheckinPref.hour,
            AutoCheckinPref.minute,
            true
        ).show()
    }

    private fun pickJitter() {
        val labels = arrayOf(
            getString(R.string.auto_checkin_jitter_off),
            getString(R.string.auto_checkin_jitter_30),
            getString(R.string.auto_checkin_jitter_60)
        )
        val values = intArrayOf(0, 30, 60)
        val checked = values.indexOfFirst { it == AutoCheckinPref.jitterMinutes }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.auto_checkin_jitter_label)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                AutoCheckinPref.jitterMinutes = values[which]
                renderJitter()
                rescheduleIfEnabled()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun rescheduleIfEnabled() {
        if (!AutoCheckinPref.enabled) return
        CheckinScheduler.reschedule(requireContext())
        renderSchedule()
    }

    private fun renderTime() {
        binding.tvCheckinTime.text = String.format(
            Locale.US,
            "%02d:%02d",
            AutoCheckinPref.hour,
            AutoCheckinPref.minute
        )
    }

    private fun renderJitter() {
        binding.tvJitter.text = when (AutoCheckinPref.jitterMinutes) {
            0 -> getString(R.string.auto_checkin_jitter_off)
            60 -> getString(R.string.auto_checkin_jitter_60)
            else -> getString(R.string.auto_checkin_jitter_30)
        }
    }

    private fun renderSchedule() {
        val next = AutoCheckinPref.nextTriggerAt
        binding.tvNextRun.text = if (!AutoCheckinPref.enabled || next <= 0L) {
            getString(R.string.auto_checkin_never)
        } else {
            getString(R.string.auto_checkin_next, Formatting.time(next))
        }

        val last = AutoCheckinPref.lastRunAt
        val result = AutoCheckinPref.lastResult
        binding.tvLastRun.text = when {
            last <= 0L -> ""
            result.isBlank() -> getString(R.string.auto_checkin_last, Formatting.time(last))
            else -> getString(R.string.auto_checkin_last, Formatting.time(last)) + " · " + result
        }

        renderPermissions()
    }

    private fun renderPermissions() {
        val exact = CheckinScheduler.canScheduleExact(requireContext())
        val battery = isIgnoringBatteryOptimizations()

        binding.tvPermStatus.text = when {
            !exact -> getString(R.string.auto_checkin_perm_exact)
            !battery -> getString(R.string.auto_checkin_perm_battery)
            else -> getString(R.string.auto_checkin_perm_ok)
        }
        binding.tvPermStatus.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            if (!exact) CheckinScheduler.openExactAlarmSettings(ctx)
            else CheckinScheduler.openBatterySettings(ctx)
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(requireContext().packageName)
    }

    private fun requestNotifyPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
