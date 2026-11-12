package com.itcast.ds_balance_viewer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itcast.ds_balance_viewer.R
import com.itcast.ds_balance_viewer.data.ApiKey
import com.itcast.ds_balance_viewer.data.BalanceResult
import com.itcast.ds_balance_viewer.data.KeyRepository
import com.itcast.ds_balance_viewer.databinding.ActivityDetailBinding
import com.itcast.ds_balance_viewer.net.BalanceClient
import com.itcast.ds_balance_viewer.util.AppExecutors
import com.itcast.ds_balance_viewer.util.ErrorText
import com.itcast.ds_balance_viewer.util.Formatting
import com.itcast.ds_balance_viewer.util.SystemBars

/**
 * 单个密钥的余额详情页。
 *
 * 进入时展示仓库里的余额快照（秒开），点"立即刷新"才发真实请求；
 * 刷新成功后会额外展示接口原始 JSON，方便排查异常。
 */
class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KEY_ID = "extra_key_id"
    }

    private lateinit var binding: ActivityDetailBinding
    private var key: ApiKey? = null

    /** 本次会话中刷新得到的原始响应，未刷新过则为 null */
    private var rawJson: String? = null
    private var rawVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(binding.toolbar, binding.root)

        val keyId = intent.getStringExtra(EXTRA_KEY_ID)
        val found = keyId?.let { KeyRepository.find(it) }
        if (found == null) {
            // 密钥可能已被删除，直接退出，避免展示脏数据
            finish()
            return
        }
        key = found

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_detail)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_copy_secret -> {
                    copySecret()
                    true
                }
                R.id.action_delete -> {
                    confirmDelete()
                    true
                }
                else -> false
            }
        }

        binding.btnRefresh.setOnClickListener { refresh() }
        binding.tvAlias.setOnClickListener { showRenameDialog() }
        binding.tvRawToggle.setOnClickListener { toggleRaw() }

        render()
        renderRaw()
    }

    override fun onResume() {
        super.onResume()
        // 从首页改名后返回，需要同步最新数据
        key?.let { current -> KeyRepository.find(current.id)?.let { key = it } }
        render()
    }

    private fun render() {
        val current = key ?: return
        binding.tvAlias.text = current.displayName()
        binding.tvMask.text = Formatting.mask(current.secret)

        binding.tvTotal.text = if (current.lastTotal != null) {
            Formatting.money(current.lastTotal, current.lastCurrency)
        } else {
            "—"
        }
        binding.tvGranted.text = if (current.lastGranted != null) {
            Formatting.money(current.lastGranted, current.lastCurrency)
        } else {
            "—"
        }
        binding.tvToppedUp.text = if (current.lastToppedUp != null) {
            Formatting.money(current.lastToppedUp, current.lastCurrency)
        } else {
            "—"
        }

        binding.tvUpdated.text = if (current.lastUpdated > 0L) {
            Formatting.time(current.lastUpdated)
        } else {
            getString(R.string.never_updated)
        }

        // 状态行：商家 · 币种 · 可用状态
        val currency = current.lastCurrency ?: getString(R.string.currency_unknown)
        val statusText = when (current.lastAvailable) {
            true -> getString(R.string.status_available)
            false -> getString(R.string.status_unavailable)
            null -> getString(R.string.status_unknown)
        }
        binding.tvStatus.text = "${current.provider.shortName} · $currency · $statusText"
    }

    private fun refresh() {
        val current = key ?: return
        binding.btnRefresh.isEnabled = false
        binding.btnRefresh.text = getString(R.string.status_querying)

        AppExecutors.io.execute {
            val result = BalanceClient.query(current.provider, current.secret)
            AppExecutors.main {
                binding.btnRefresh.isEnabled = true
                binding.btnRefresh.setText(R.string.action_refresh_now)

                when (result) {
                    is BalanceClient.Result.Ok -> {
                        val balance = result.balance
                        val primary = balance.primary
                        val updated = current.copy(
                            lastTotal = primary?.total,
                            lastGranted = primary?.granted,
                            lastToppedUp = primary?.toppedUp,
                            lastCurrency = primary?.currency,
                            lastAvailable = balance.available,
                            lastUpdated = System.currentTimeMillis()
                        )
                        KeyRepository.update(updated)
                        key = updated
                        rawJson = balance.rawJson
                        render()
                        renderRaw()
                    }
                    is BalanceClient.Result.Error -> {
                        Toast.makeText(this, ErrorText.of(this, result), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun toggleRaw() {
        rawVisible = !rawVisible
        renderRaw()
    }

    private fun renderRaw() {
        val raw = rawJson
        binding.tvRawJson.visibility = if (rawVisible) View.VISIBLE else View.GONE
        binding.tvRawJson.text = raw ?: "点「立即刷新」后这里会显示接口原始返回。"
    }

    private fun showRenameDialog() {
        val current = key ?: return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(current.alias)
            setSelection(current.alias.length)
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_rename)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val updated = current.copy(alias = input.text.toString().trim())
                KeyRepository.update(updated)
                key = updated
                render()
            }
            .show()
    }

    private fun copySecret() {
        val current = key ?: return
        val manager = getSystemService(ClipboardManager::class.java) ?: return
        manager.setPrimaryClip(ClipData.newPlainText("DeepSeek API Key", current.secret))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete() {
        val current = key ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_msg, current.displayName()))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                KeyRepository.remove(current.id)
                finish()
            }
            .show()
    }
}
