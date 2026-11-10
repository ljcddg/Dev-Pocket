package com.itcast.ds_balance_viewer.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itcast.ds_balance_viewer.R
import com.itcast.ds_balance_viewer.data.ApiKey
import com.itcast.ds_balance_viewer.data.KeyRepository
import com.itcast.ds_balance_viewer.databinding.FragmentBalanceBinding
import com.itcast.ds_balance_viewer.net.DeepSeekClient
import com.itcast.ds_balance_viewer.util.AppExecutors
import com.itcast.ds_balance_viewer.util.ErrorText
import com.itcast.ds_balance_viewer.util.SystemBars
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「余额」Tab：DeepSeek 密钥列表。
 *
 * 交互沿用原首页：点卡片进详情、长按弹菜单、工具栏刷新全部、工具栏 ＋ 添加。
 */
class BalanceFragment : Fragment() {

    private var _binding: FragmentBalanceBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: KeyAdapter
    private var lastRefreshAt = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBalanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        SystemBars.apply(binding.toolbar, binding.root)

        adapter = KeyAdapter(
            onClick = { key -> openDetail(key) },
            onLongClick = { key -> showItemMenu(key) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_add -> {
                    startActivity(Intent(requireContext(), AddKeyActivity::class.java))
                    true
                }
                R.id.action_refresh -> {
                    refreshAll(silent = false)
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(requireContext(), SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null) return
        reloadList()
        val shouldAutoRefresh = KeyRepository.autoRefresh &&
                KeyRepository.count() > 0 &&
                System.currentTimeMillis() - lastRefreshAt > 30_000L
        if (shouldAutoRefresh) refreshAll(silent = true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun reloadList() {
        val list = KeyRepository.all()
        adapter.submit(list)
        val empty = list.isEmpty()
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun refreshAll(silent: Boolean = false) {
        val list = KeyRepository.all()
        if (list.isEmpty()) return
        val context = context ?: return

        if (!silent) Toast.makeText(context, R.string.refreshing, Toast.LENGTH_SHORT).show()

        val finished = AtomicInteger(0)
        val failed = AtomicInteger(0)

        list.forEach { key ->
            AppExecutors.io.execute {
                val result = DeepSeekClient.query(key.secret)
                AppExecutors.main {
                    when (result) {
                        is DeepSeekClient.Result.Ok -> {
                            val primary = result.balance.primary
                            KeyRepository.update(
                                key.copy(
                                    lastTotal = primary?.total,
                                    lastGranted = primary?.granted,
                                    lastToppedUp = primary?.toppedUp,
                                    lastCurrency = primary?.currency,
                                    lastAvailable = result.balance.available,
                                    lastUpdated = System.currentTimeMillis()
                                )
                            )
                        }
                        is DeepSeekClient.Result.Error -> failed.incrementAndGet()
                    }

                    if (finished.incrementAndGet() == list.size) {
                        lastRefreshAt = System.currentTimeMillis()
                        if (_binding != null) reloadList()
                        if (!silent) {
                            val ctx = context ?: return@main
                            Toast.makeText(
                                ctx,
                                ctx.getString(R.string.refreshed_count, list.size - failed.get()),
                                Toast.LENGTH_SHORT
                            ).show()
                            if (failed.get() > 0) {
                                Toast.makeText(
                                    ctx,
                                    ctx.getString(R.string.refresh_failed, failed.get()),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openDetail(key: ApiKey) {
        startActivity(
            Intent(requireContext(), DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_KEY_ID, key.id)
        )
    }

    private fun showItemMenu(key: ApiKey) {
        val options = arrayOf(
            getString(R.string.menu_refresh),
            getString(R.string.menu_rename),
            getString(R.string.menu_delete)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(key.displayName())
            .setItems(options) { _, which ->
                when (which) {
                    0 -> refreshSingle(key)
                    1 -> showRenameDialog(key)
                    2 -> confirmDelete(key)
                }
            }
            .show()
    }

    private fun refreshSingle(key: ApiKey) {
        AppExecutors.io.execute {
            val result = DeepSeekClient.query(key.secret)
            AppExecutors.main {
                when (result) {
                    is DeepSeekClient.Result.Ok -> {
                        val primary = result.balance.primary
                        KeyRepository.update(
                            key.copy(
                                lastTotal = primary?.total,
                                lastGranted = primary?.granted,
                                lastToppedUp = primary?.toppedUp,
                                lastCurrency = primary?.currency,
                                lastAvailable = result.balance.available,
                                lastUpdated = System.currentTimeMillis()
                            )
                        )
                    }
                    is DeepSeekClient.Result.Error -> {
                        val ctx = context ?: return@main
                        Toast.makeText(ctx, ErrorText.of(ctx, result), Toast.LENGTH_LONG).show()
                    }
                }
                if (_binding != null) reloadList()
            }
        }
    }

    private fun showRenameDialog(key: ApiKey) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(key.alias)
            setSelection(key.alias.length)
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.menu_rename)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                KeyRepository.update(key.copy(alias = input.text.toString().trim()))
                reloadList()
            }
            .show()
    }

    private fun confirmDelete(key: ApiKey) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_msg, key.displayName()))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                KeyRepository.remove(key.id)
                reloadList()
            }
            .show()
    }
}
