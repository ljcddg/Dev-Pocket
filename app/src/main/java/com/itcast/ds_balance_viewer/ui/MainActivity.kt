package com.itcast.ds_balance_viewer.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.itcast.ds_balance_viewer.R
import com.itcast.ds_balance_viewer.databinding.ActivityMainBinding

/**
 * 主界面：底部导航 + 三个 Tab。
 *
 * 「余额」放 DeepSeek 密钥管理，「签到」放 WorkBuddy 账号签到，
 * 「设置」原先挂在余额页工具栏的齿轮里，现在独立成一个 Tab。
 * 用 show/hide 切换 Fragment 而不是 replace，切回来时列表状态和滚动位置都还在。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val tagBalance = "frag_balance"
    private val tagCheckin = "frag_checkin"
    private val tagSettings = "frag_settings"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 底部导航栏避让系统手势条
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { view, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, nav.bottom)
            insets
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_balance -> {
                    showTab(tagBalance) { BalanceFragment() }
                    true
                }
                R.id.tab_checkin -> {
                    showTab(tagCheckin) { CheckinFragment() }
                    true
                }
                R.id.tab_settings -> {
                    showTab(tagSettings) { SettingsFragment() }
                    true
                }
                else -> false
            }
        }

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.tab_balance
        }
    }

    /** 显示指定 tag 的 Fragment，隐藏其余 */
    private fun showTab(tag: String, factory: () -> Fragment) {
        val fm = supportFragmentManager
        val transaction = fm.beginTransaction()
        fm.fragments.forEach { if (it.tag != tag) transaction.hide(it) }

        val existing = fm.findFragmentByTag(tag)
        if (existing == null) {
            transaction.add(R.id.fragmentContainer, factory(), tag)
        } else {
            transaction.show(existing)
        }
        transaction.commitNowAllowingStateLoss()
    }
}
