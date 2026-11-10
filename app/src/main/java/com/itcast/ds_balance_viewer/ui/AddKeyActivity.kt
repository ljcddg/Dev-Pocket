package com.itcast.ds_balance_viewer.ui

import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.itcast.ds_balance_viewer.R
import com.itcast.ds_balance_viewer.data.ApiKey
import com.itcast.ds_balance_viewer.data.KeyRepository
import com.itcast.ds_balance_viewer.databinding.ActivityAddKeyBinding
import com.itcast.ds_balance_viewer.net.DeepSeekClient
import com.itcast.ds_balance_viewer.util.AppExecutors
import com.itcast.ds_balance_viewer.util.ErrorText
import com.itcast.ds_balance_viewer.util.SystemBars

/**
 * 添加密钥页。
 *
 * 保存前会先真实请求一次余额接口做校验：只有密钥有效才落盘，
 * 避免用户存进一堆打错的密钥，回头列表里全是红色的"不可用"。
 */
class AddKeyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddKeyBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddKeyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(binding.toolbar, binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnPaste.setOnClickListener { pasteFromClipboard() }
        binding.btnSave.setOnClickListener { validateAndSave() }
    }

    private fun pasteFromClipboard() {
        val manager = getSystemService(ClipboardManager::class.java)
        val clip = manager?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0)?.text?.toString()
        } else {
            null
        }
        if (text.isNullOrBlank()) {
            showError(getString(R.string.clipboard_empty))
            return
        }
        binding.etSecret.setText(text.trim())
        hideError()
    }

    private fun validateAndSave() {
        val secret = binding.etSecret.text?.toString()?.trim().orEmpty()

        // === 临时演示代码（验证完删除）===
        if (secret.startsWith("sk-demo")) {
            val alias = binding.etAlias.text?.toString()?.trim().orEmpty()
            val demo = if (secret.endsWith("2")) {
                ApiKey(alias = alias, secret = secret, lastTotal = "0.00", lastGranted = "0.00",
                    lastToppedUp = "0.00", lastCurrency = "CNY", lastAvailable = false,
                    lastUpdated = System.currentTimeMillis())
            } else {
                ApiKey(alias = alias, secret = secret, lastTotal = "23.94", lastGranted = "0.00",
                    lastToppedUp = "23.94", lastCurrency = "CNY", lastAvailable = true,
                    lastUpdated = System.currentTimeMillis())
            }
            KeyRepository.add(demo)
            finish()
            return
        }
        // === 临时演示代码结束 ===

        // 本地前置校验，减少无谓的网络请求
        if (secret.isEmpty()) {
            showError(getString(R.string.secret_empty))
            return
        }
        if (secret.length < 20) {
            showError(getString(R.string.secret_format_hint))
            return
        }
        if (KeyRepository.findBySecret(secret) != null) {
            showError(getString(R.string.duplicate_secret))
            return
        }

        hideError()
        setLoading(true)

        AppExecutors.io.execute {
            val result = DeepSeekClient.query(secret)
            AppExecutors.main {
                setLoading(false)
                when (result) {
                    is DeepSeekClient.Result.Ok -> {
                        val alias = binding.etAlias.text?.toString()?.trim().orEmpty()
                        val primary = result.balance.primary
                        KeyRepository.add(
                            ApiKey(
                                alias = alias,
                                secret = secret,
                                lastTotal = primary?.total,
                                lastGranted = primary?.granted,
                                lastToppedUp = primary?.toppedUp,
                                lastCurrency = primary?.currency,
                                lastAvailable = result.balance.available,
                                lastUpdated = System.currentTimeMillis()
                            )
                        )
                        Toast.makeText(this, R.string.save_success, Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    }
                    is DeepSeekClient.Result.Error -> {
                        showError(ErrorText.of(this, result))
                    }
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSave.isEnabled = !loading
        binding.btnSave.text = if (loading) {
            getString(R.string.verifying)
        } else {
            getString(R.string.action_test_save)
        }
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.tvError.visibility = View.GONE
    }
}
