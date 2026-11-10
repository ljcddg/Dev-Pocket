package com.itcast.ds_balance_viewer.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.itcast.ds_balance_viewer.BuildConfig
import com.itcast.ds_balance_viewer.R
import com.itcast.ds_balance_viewer.data.KeyRepository
import com.itcast.ds_balance_viewer.data.ThemePref
import com.itcast.ds_balance_viewer.databinding.ActivitySettingsBinding
import com.itcast.ds_balance_viewer.util.SystemBars

/** 设置页：外观（跟随系统 / 浅色 / 深色）、自动刷新开关、密钥数量、关于与安全说明 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** 初始化时同步 RadioGroup 会回调，这里挡掉避免误存 */
    private var bindingTheme = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(binding.toolbar, binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupTheme()

        binding.switchAutoRefresh.isChecked = KeyRepository.autoRefresh
        binding.switchAutoRefresh.setOnCheckedChangeListener { _, checked ->
            KeyRepository.autoRefresh = checked
        }

        binding.tvVersion.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
    }

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
            // 立即生效：AppCompatDelegate 会重建当前界面，重建后 RadioGroup 按新偏好重新勾选
            ThemePref.apply()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.tvKeyCount.text = getString(R.string.settings_key_count, KeyRepository.count())
    }
}
