package com.itcast.ds_balance_viewer.ui

import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.itcast.ds_balance_viewer.R
import com.itcast.ds_balance_viewer.data.ApiKey
import com.itcast.ds_balance_viewer.data.KeyRepository
import com.itcast.ds_balance_viewer.data.Provider
import com.itcast.ds_balance_viewer.databinding.ActivityAddKeyBinding
import com.itcast.ds_balance_viewer.net.BalanceClient
import com.itcast.ds_balance_viewer.util.AppExecutors
import com.itcast.ds_balance_viewer.util.ErrorText
import com.itcast.ds_balance_viewer.util.SystemBars

/**
 * 添加密钥页。
 *
 * 先选商家，再填密钥：商家决定用哪家的余额接口，三家都要在保存时真实验一次，
 * 只有通了才落盘——避免用户存进一堆打错的密钥，回头列表里全是红色「不可用」。
 *
 * 商家没有做「自动识别」：Kimi 和 DeepSeek 的密钥都是 sk- 开头，长得一样，
 * 靠前缀猜必然出错，不如让用户明确选一次，代价只有一次点击。
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

        // 默认选中 DeepSeek，与之前只有 DeepSeek 时的习惯保持一致
        binding.chipProvider.check(R.id.chipDeepSeek)
        binding.chipProvider.setOnCheckedStateChangeListener { _, _ ->
            updateSecretHint(selectedProvider())
        }
        updateSecretHint(Provider.DEEPSEEK)
    }

    private fun selectedProvider(): Provider = when (binding.chipProvider.checkedChipId) {
        R.id.chipKimi -> Provider.KIMI
        R.id.chipGlm -> Provider.GLM
        else -> Provider.DEEPSEEK
    }

    private fun updateSecretHint(provider: Provider) {
        binding.secretLayout.hint = getString(R.string.label_secret_hint, provider.keyHint)
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
        val provider = selectedProvider()

        // 本地前置校验，减少无谓的网络请求
        if (secret.isEmpty()) {
            showError(getString(R.string.secret_empty))
            return
        }
        if (secret.length < 16) {
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
            val result = BalanceClient.query(provider, secret)
            AppExecutors.main {
                setLoading(false)
                when (result) {
                    is BalanceClient.Result.Ok -> {
                        val alias = binding.etAlias.text?.toString()?.trim().orEmpty()
                        val primary = result.balance.primary
                        KeyRepository.add(
                            ApiKey(
                                alias = alias,
                                secret = secret,
                                provider = provider,
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
                    is BalanceClient.Result.Error -> {
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
