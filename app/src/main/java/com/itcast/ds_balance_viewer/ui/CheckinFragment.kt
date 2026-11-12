package com.itcast.ds_balance_viewer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.itcast.ds_balance_viewer.R
import com.itcast.ds_balance_viewer.data.CheckinEngine
import com.itcast.ds_balance_viewer.data.WbAccount
import com.itcast.ds_balance_viewer.data.WbRepository
import com.itcast.ds_balance_viewer.data.WbSession
import com.itcast.ds_balance_viewer.databinding.FragmentCheckinBinding
import com.itcast.ds_balance_viewer.net.WorkBuddyClient
import com.itcast.ds_balance_viewer.util.AppExecutors
import com.itcast.ds_balance_viewer.util.SystemBars
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「签到」Tab：WorkBuddy 账号列表 + 一键签到。
 *
 * 签到逻辑本身不在这个类里，全部委托给 [CheckinEngine]：
 * 卡片上的按钮走 [CheckinEngine.runSingle]，右上角一键签到走 [CheckinEngine.runAll]。
 * 这样后续的定时签到（没有 Activity、没有 Context）可以复用同一份实现。
 *
 * 令牌策略：
 * - accessToken 有效期 30 天，快到期（<5 分钟）或接口返回 401 时，先用 refreshToken 续期再重试一次
 * - refreshToken 有效期 60 天，实测不轮换，所以电脑端与手机端可共用同一套凭据
 * - 续期失败则提示重新登录（走 WebView 登录流程）
 */
class CheckinFragment : Fragment() {

    private var _binding: FragmentCheckinBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: WbAccountAdapter
    private var lastRefreshAt = 0L

    /** 批量签到期间常驻的进度条，结束时统一 dismiss */
    private var batchBar: Snackbar? = null

    /** 登录页返回后自动刷新列表 */
    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == WbLoginActivity.RESULT_LOGGED_IN) {
            refreshAllStatus(silent = false)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCheckinBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        SystemBars.apply(binding.toolbar, binding.root)

        adapter = WbAccountAdapter(
            onCheckin = { account -> doCheckin(account) },
            onOpenDetail = { account -> openDetail(account) },
            onLongClick = { account -> showItemMenu(account) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_checkin_all -> {
                    checkinAll()
                    true
                }
                R.id.action_wb_add -> {
                    openLogin()
                    true
                }
                R.id.action_wb_refresh -> {
                    refreshAllStatus(silent = false)
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
        // 切走再切回来时，批量任务可能还在后台跑，按钮状态要跟着恢复
        setBatchRunning(CheckinEngine.isRunning())
        val shouldAutoRefresh = WbRepository.count() > 0 &&
                !CheckinEngine.isRunning() &&
                System.currentTimeMillis() - lastRefreshAt > 30_000L
        if (shouldAutoRefresh) refreshAllStatus(silent = true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        batchBar?.dismiss()
        batchBar = null
        _binding = null
    }

    // ---------------- 列表 ----------------

    private fun reloadList() {
        val list = WbRepository.all()
        adapter.submit(list)
        val empty = list.isEmpty()
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun openLogin() {
        loginLauncher.launch(Intent(requireContext(), WbLoginActivity::class.java))
    }

    /** 短按卡片进积分详情页 */
    private fun openDetail(account: WbAccount) {
        startActivity(
            Intent(requireContext(), WbDetailActivity::class.java)
                .putExtra(WbDetailActivity.EXTRA_ACCOUNT_ID, account.id)
        )
    }

    // ---------------- 一键签到 ----------------

    /**
     * 对「今日未签」的账号逐个签到。
     *
     * 串行执行是有意为之：并发打同一个接口是最典型的脚本特征，而且 claim 是写操作，
     * 串行才能保证每个账号的状态干净。已签的账号由 [CheckinEngine] 在本地过滤掉，
     * 不会浪费请求。
     */
    private fun checkinAll() {
        val ctx = context ?: return
        if (WbRepository.count() == 0) {
            Toast.makeText(ctx, R.string.wb_checkin_no_account, Toast.LENGTH_SHORT).show()
            return
        }
        if (CheckinEngine.isRunning()) {
            Toast.makeText(ctx, R.string.wb_checkin_running, Toast.LENGTH_SHORT).show()
            return
        }

        val total = WbRepository.count()
        setBatchRunning(true)
        val bar = Snackbar.make(
            binding.root,
            getString(R.string.wb_checkin_progress, 0, total),
            Snackbar.LENGTH_INDEFINITE
        ).apply { show() }
        batchBar = bar

        AppExecutors.checkin.execute {
            // 进度回调已经切回主线程，直接改 UI 是安全的
            val report = CheckinEngine.runAll(CheckinEngine.Trigger.MANUAL) { done, sum, _, _ ->
                batchBar?.setText(getString(R.string.wb_checkin_progress, done, sum))
                if (_binding != null) reloadList()
            }
            AppExecutors.main {
                batchBar?.dismiss()
                batchBar = null
                setBatchRunning(false)
                if (_binding != null) reloadList()
                lastRefreshAt = System.currentTimeMillis()
                context?.let { Toast.makeText(it, summarize(report), Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun setBatchRunning(running: Boolean) {
        val item = _binding?.toolbar?.menu?.findItem(R.id.action_checkin_all) ?: return
        item.isEnabled = !running
    }

    /** 把批量结果拼成一句话：领到的、已签的、失败的分别计数 */
    private fun summarize(report: Map<String, CheckinEngine.Result>): String {
        if (report.isEmpty()) return getString(R.string.wb_checkin_all_done)

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
        return if (text.isEmpty()) getString(R.string.wb_checkin_all_done) else text.toString()
    }

    // ---------------- 单个签到 ----------------

    private fun doCheckin(account: WbAccount) {
        val ctx = context ?: return
        Toast.makeText(ctx, R.string.wb_checking, Toast.LENGTH_SHORT).show()

        AppExecutors.checkin.execute {
            val result = CheckinEngine.runSingle(account)
            AppExecutors.main {
                val target = context ?: return@main
                val msg = when (result) {
                    null -> getString(R.string.wb_checkin_running)
                    is CheckinEngine.Result.Claimed ->
                        if (result.credit > 0) getString(R.string.wb_claim_success, result.credit)
                        else getString(R.string.wb_checked_today)
                    CheckinEngine.Result.AlreadyDone -> getString(R.string.wb_claim_already)
                    is CheckinEngine.Result.Failed -> describeFail(result)
                }
                Toast.makeText(target, msg, Toast.LENGTH_SHORT).show()
                if (_binding != null) reloadList()
                lastRefreshAt = System.currentTimeMillis()
            }
        }
    }

    private fun describeFail(fail: CheckinEngine.Result.Failed): String = when (fail.kind) {
        CheckinEngine.Fail.EXPIRED -> getString(R.string.wb_expired)
        CheckinEngine.Fail.TIMEOUT -> getString(R.string.err_timeout)
        CheckinEngine.Fail.NETWORK -> getString(R.string.wb_network)
        CheckinEngine.Fail.PARSE -> getString(R.string.err_parse)
        CheckinEngine.Fail.BUSINESS -> getString(R.string.wb_api_error, fail.detail)
    }

    // ---------------- 状态刷新 ----------------

    private fun refreshAllStatus(silent: Boolean) {
        val list = WbRepository.all()
        if (list.isEmpty()) return
        val ctx = context ?: return
        // 批量签到在跑时不要插进来抢令牌，等它结束再说
        if (CheckinEngine.isRunning()) return
        if (!silent) Toast.makeText(ctx, R.string.wb_refreshing, Toast.LENGTH_SHORT).show()

        val finished = AtomicInteger(0)
        list.forEach { account ->
            AppExecutors.io.execute {
                val ready = WbSession.ensureToken(account)
                val status = ready?.let {
                    WorkBuddyClient.checkinStatus(it.accessToken, it.uid, it.domain)
                }
                // 余额与签到是两个接口，任何一边成功都要写回
                val credit = ready?.let {
                    WorkBuddyClient.creditSummary(it.accessToken, it.uid, it.domain)
                }
                AppExecutors.main {
                    if (ready == null) {
                        WbRepository.update(account.copy(lastResult = getString(R.string.wb_expired)))
                    } else {
                        var acc = ready
                        if (status is WorkBuddyClient.Res.Ok) acc = WbSession.merge(acc, status.value)
                        if (credit is WorkBuddyClient.Res.Ok) {
                            acc = WbSession.mergeBalance(acc, credit.value)
                        }
                        WbRepository.update(acc)
                    }
                    if (finished.incrementAndGet() == list.size) {
                        lastRefreshAt = System.currentTimeMillis()
                        if (_binding != null) reloadList()
                        if (!silent) {
                            context?.let {
                                Toast.makeText(
                                    it,
                                    it.getString(R.string.wb_refreshed, list.size),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------- 长按菜单 ----------------

    private fun showItemMenu(account: WbAccount) {
        val options = arrayOf(
            getString(R.string.wb_menu_refresh),
            getString(R.string.wb_menu_rename),
            getString(R.string.wb_menu_relogin),
            getString(R.string.wb_raw_menu),
            getString(R.string.wb_menu_delete)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(account.displayName())
            .setItems(options) { _, which ->
                when (which) {
                    0 -> refreshSingle(account)
                    1 -> showRenameDialog(account)
                    2 -> openLogin()
                    3 -> showRawData(account)
                    4 -> confirmDelete(account)
                }
            }
            .show()
    }

    /**
     * 显示两个接口的原始返回（签到状态 + 账户余额），便于核对服务端到底给了哪些字段。
     * 响应里只有业务数据，不含令牌，可以安全复制。
     */
    private fun showRawData(account: WbAccount) {
        AppExecutors.io.execute {
            val ready = WbSession.ensureToken(account)
            val raw = ready?.let {
                val checkin = WorkBuddyClient.rawCheckinStatus(it.accessToken, it.uid, it.domain)
                val credit = WorkBuddyClient.rawCreditSummary(it.accessToken, it.uid, it.domain)
                "【签到接口】\n$checkin\n\n【余额接口】\n$credit"
            }
            AppExecutors.main {
                val ctx = context ?: return@main
                val text = raw ?: getString(R.string.wb_raw_failed)
                val view = TextView(ctx).apply {
                    this.text = text
                    setPadding(48, 32, 48, 32)
                    setTextIsSelectable(true)
                    typeface = Typeface.MONOSPACE
                    textSize = 11f
                }
                val scroll = ScrollView(ctx).apply { addView(view) }
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.wb_raw_title)
                    .setView(scroll)
                    .setPositiveButton(R.string.close, null)
                    .setNeutralButton(R.string.copy) { _, _ ->
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("wb_raw", text))
                        Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        }
    }

    private fun refreshSingle(account: WbAccount) {
        AppExecutors.io.execute {
            val ready = WbSession.ensureToken(account)
            val status = ready?.let {
                WorkBuddyClient.checkinStatus(it.accessToken, it.uid, it.domain)
            }
            val credit = ready?.let {
                WorkBuddyClient.creditSummary(it.accessToken, it.uid, it.domain)
            }
            AppExecutors.main {
                val ctx = context ?: return@main
                if (ready == null) {
                    Toast.makeText(ctx, R.string.wb_expired, Toast.LENGTH_LONG).show()
                } else {
                    var acc = ready
                    if (status is WorkBuddyClient.Res.Ok) {
                        acc = WbSession.merge(acc, status.value)
                    } else if (status is WorkBuddyClient.Res.Err) {
                        Toast.makeText(ctx, describe(status.error), Toast.LENGTH_LONG).show()
                    }
                    if (credit is WorkBuddyClient.Res.Ok) {
                        acc = WbSession.mergeBalance(acc, credit.value)
                    }
                    WbRepository.update(acc)
                }
                lastRefreshAt = System.currentTimeMillis()
                if (_binding != null) reloadList()
            }
        }
    }

    private fun showRenameDialog(account: WbAccount) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(account.alias)
            setSelection(account.alias.length)
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.wb_menu_rename)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                WbRepository.update(account.copy(alias = input.text.toString().trim()))
                reloadList()
            }
            .show()
    }

    private fun confirmDelete(account: WbAccount) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.wb_delete_confirm_title)
            .setMessage(getString(R.string.wb_delete_confirm_msg, account.displayName()))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                WbRepository.remove(account.id)
                reloadList()
            }
            .show()
    }

    // ---------------- 错误文案（刷新路径） ----------------

    private fun describe(error: WorkBuddyClient.ApiError): String = when (error) {
        WorkBuddyClient.ApiError.Timeout -> getString(R.string.err_timeout)
        WorkBuddyClient.ApiError.Network -> getString(R.string.wb_network)
        WorkBuddyClient.ApiError.Unauthorized -> getString(R.string.wb_expired)
        WorkBuddyClient.ApiError.Parse -> getString(R.string.err_parse)
        is WorkBuddyClient.ApiError.Business ->
            getString(R.string.wb_api_error, error.message.ifBlank { "code=${error.code}" })
    }
}
