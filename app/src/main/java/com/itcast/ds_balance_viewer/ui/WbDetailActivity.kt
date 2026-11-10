package com.itcast.ds_balance_viewer.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.itcast.ds_balance_viewer.R
import com.itcast.ds_balance_viewer.data.WbAccount
import com.itcast.ds_balance_viewer.data.WbRepository
import com.itcast.ds_balance_viewer.data.WbSession
import com.itcast.ds_balance_viewer.databinding.ActivityWbDetailBinding
import com.itcast.ds_balance_viewer.net.WorkBuddyClient
import com.itcast.ds_balance_viewer.util.AppExecutors
import com.itcast.ds_balance_viewer.util.Formatting
import com.itcast.ds_balance_viewer.util.SystemBars
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * WorkBuddy 账号的积分详情页。
 *
 * 展示：累计积分、今日到账、连续签到天数、下一次连签奖励进度、
 * 活动名称与结束时间，以及最近 28 天的签到日历。
 *
 * 进入时先渲染仓库里的快照（秒开），到页面后自动静默拉一次最新状态；
 * 点「签到」按钮走完整领取流程（含令牌续期）。
 */
class WbDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACCOUNT_ID = "extra_wb_account_id"
        /** 日历展示的天数（4 周） */
        private const val CALENDAR_DAYS = 28
    }

    private lateinit var binding: ActivityWbDetailBinding
    private var account: WbAccount? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWbDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(binding.toolbar, binding.root)

        val id = intent.getStringExtra(EXTRA_ACCOUNT_ID)
        val found = id?.let { WbRepository.find(it) }
        if (found == null) {
            // 账号可能已被删除，直接退出避免展示脏数据
            finish()
            return
        }
        account = found

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnCheckin.setOnClickListener { doCheckin() }
        binding.btnRefresh.setOnClickListener { refreshStatus(userTriggered = true) }

        render(found)
        refreshStatus(userTriggered = false)
    }

    override fun onResume() {
        super.onResume()
        // 从列表页返回时同步最新快照
        account?.let { current -> WbRepository.find(current.id)?.let { account = it } }
        account?.let { render(it) }
    }

    // ---------------- 渲染 ----------------

    private fun render(a: WbAccount) {
        binding.tvAlias.text = a.displayName()
        binding.tvUid.text = getString(R.string.wb_uid_mask, Formatting.mask(a.uid))

        // hero：账户剩余额度（真实余额），不是签到累计
        binding.tvTotalCredits.text = Formatting.credit(a.creditLeft)
        binding.tvHeroMeta.text = if (a.creditLeft < 0) {
            getString(R.string.wb_credit_unknown)
        } else {
            getString(
                R.string.wb_detail_hero_meta,
                Formatting.credit(a.creditTotal),
                Formatting.credit(a.creditUsed)
            )
        }
        binding.tvCheckinTotal.text = if (a.totalCredits < 0) {
            "—"
        } else {
            "${Formatting.credit(a.totalCredits)} ${getString(R.string.wb_credit_unit)}"
        }

        // hero 副标题：今日到账 + 连签天数
        val streakPart = if (a.streakDays > 0) {
            getString(R.string.wb_hero_streak, a.streakDays)
        } else {
            ""
        }
        binding.tvTodayLine.text = when {
            a.checkedIn == true && a.todayCredit > 0 ->
                listOf(getString(R.string.wb_credit_today, a.todayCredit), streakPart)
                    .filter { it.isNotEmpty() }.joinToString(" · ")
            a.checkedIn == true ->
                listOf(getString(R.string.wb_credit_today_none), streakPart)
                    .filter { it.isNotEmpty() }.joinToString(" · ")
            a.dailyCredit > 0 -> getString(R.string.wb_credit_today_pending, a.dailyCredit)
            else -> streakPart
        }

        binding.tvHeroStatus.text = when {
            a.active == false -> getString(R.string.wb_activity_closed)
            a.checkedIn == true -> getString(R.string.wb_checked_today)
            a.checkedIn == false -> getString(R.string.wb_not_checked)
            else -> getString(R.string.wb_unknown_status)
        }

        // 明细行
        binding.tvStreak.text = getString(R.string.wb_detail_days, a.streakDays)
        binding.tvNextBonus.text = when {
            a.isStreakDay && a.streakBonusCredit > 0 ->
                getString(R.string.wb_detail_bonus_today, Formatting.credit(a.streakBonusCredit))
            a.nextStreakDay > a.streakDays && a.streakBonusCredit > 0 ->
                getString(
                    R.string.wb_detail_bonus_progress,
                    a.nextStreakDay - a.streakDays,
                    Formatting.credit(a.streakBonusCredit)
                )
            else -> getString(R.string.wb_detail_bonus_none)
        }
        binding.tvToday.text = when {
            a.checkedIn == true && a.todayCredit > 0 ->
                getString(R.string.wb_detail_today_signed, a.todayCredit)
            a.checkedIn == true -> getString(R.string.wb_checked_today)
            a.dailyCredit > 0 -> getString(R.string.wb_detail_today_pending, a.dailyCredit)
            else -> "—"
        }
        binding.tvLastCheckin.text = if (a.lastCheckinAt > 0L) {
            Formatting.relativeTime(a.lastCheckinAt)
        } else {
            getString(R.string.wb_never_checked)
        }

        // 活动信息：有名字或结束时间才显示这一行
        val activityText = listOfNotNull(
            a.activityName,
            a.activityEndTime?.let { getString(R.string.wb_detail_activity_end, shortDate(it)) }
        ).joinToString(" · ")
        val hasActivity = activityText.isNotBlank()
        binding.rowActivity.visibility = if (hasActivity) View.VISIBLE else View.GONE
        binding.dividerActivity.visibility = if (hasActivity) View.VISIBLE else View.GONE
        binding.tvActivity.text = activityText

        // 签到按钮
        val done = a.checkedIn == true || a.active == false
        binding.btnCheckin.isEnabled = !done
        // backgroundTint 固定了底色，禁用时 MaterialButton 不会自动变灰，这里用透明度区分
        binding.btnCheckin.alpha = if (done) 0.45f else 1f
        binding.btnCheckin.setText(
            when {
                a.active == false -> R.string.wb_activity_closed
                a.checkedIn == true -> R.string.wb_checked_today
                else -> R.string.wb_checkin_now
            }
        )

        renderCalendar(a.checkinDates)
    }

    /** 最近 28 天的签到日历，4 行 × 7 列 */
    private fun renderCalendar(dates: List<String>) {
        val container = binding.calendarContainer
        container.removeAllViews()

        val signed = dates.toHashSet()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = Calendar.getInstance()
        val todayKey = fmt.format(today.time)
        val start = (today.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -(CALENDAR_DAYS - 1))
        }

        val signedColor = ContextCompat.getColor(this, R.color.ds_primary)
        val emptyColor = ContextCompat.getColor(this, R.color.ds_divider)
        val onPrimary = ContextCompat.getColor(this, R.color.ds_on_primary)
        val secondary = resolveSecondaryColor()

        var index = 0
        while (index < CALENDAR_DAYS) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for (col in 0 until 7) {
                val cal = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, index) }
                val key = fmt.format(cal.time)
                val isSigned = signed.contains(key)
                val isToday = key == todayKey

                val cell = TextView(this).apply {
                    text = cal.get(Calendar.DAY_OF_MONTH).toString()
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(if (isSigned) onPrimary else secondary)
                    background = cellBackground(
                        fill = if (isSigned) signedColor else emptyColor,
                        strokeColor = if (isToday) signedColor else null
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        dp(36),
                        1f
                    ).apply {
                        val gap = dp(3)
                        setMargins(gap, gap, gap, gap)
                    }
                }
                row.addView(cell)
                index++
            }
            container.addView(row)
        }

        binding.tvCalendarHint.text = if (signed.isEmpty()) {
            getString(R.string.wb_detail_calendar_empty)
        } else {
            getString(R.string.wb_detail_calendar_hint, signed.size)
        }
    }

    // ---------------- 网络 ----------------

    private fun doCheckin() {
        val current = account ?: return
        Toast.makeText(this, R.string.wb_checking, Toast.LENGTH_SHORT).show()
        setBusy(true)

        AppExecutors.io.execute {
            val ready = WbSession.ensureToken(current)
            if (ready == null) {
                AppExecutors.main {
                    setBusy(false)
                    Toast.makeText(this, R.string.wb_expired, Toast.LENGTH_LONG).show()
                }
                return@execute
            }

            val claim = WorkBuddyClient.claim(ready.accessToken, ready.uid, ready.domain)
            val status = WorkBuddyClient.checkinStatus(ready.accessToken, ready.uid, ready.domain)
            // 签到会加额度，余额要一起刷新才看得到变化
            val credit = WorkBuddyClient.creditSummary(ready.accessToken, ready.uid, ready.domain)

            AppExecutors.main {
                setBusy(false)
                when (claim) {
                    is WorkBuddyClient.Res.Ok -> {
                        val r = claim.value
                        val msg = when {
                            r.alreadyCheckedIn -> getString(R.string.wb_claim_already)
                            r.credit > 0 -> getString(R.string.wb_claim_success, r.credit)
                            else -> getString(R.string.wb_checked_today)
                        }
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                    is WorkBuddyClient.Res.Err ->
                        Toast.makeText(this, describe(claim.error), Toast.LENGTH_LONG).show()
                }
                applyAll(ready, status, credit)
            }
        }
    }

    /** 拉最新签到状态 + 账户余额；userTriggered 决定是否有"查询中"的即时反馈 */
    private fun refreshStatus(userTriggered: Boolean) {
        val current = account ?: return
        if (userTriggered) {
            setBusy(true)
            Toast.makeText(this, R.string.wb_refreshing, Toast.LENGTH_SHORT).show()
        }

        AppExecutors.io.execute {
            val ready = WbSession.ensureToken(current)
            if (ready == null) {
                AppExecutors.main {
                    if (userTriggered) {
                        setBusy(false)
                        Toast.makeText(this, R.string.wb_expired, Toast.LENGTH_LONG).show()
                    }
                }
                return@execute
            }
            val status = WorkBuddyClient.checkinStatus(ready.accessToken, ready.uid, ready.domain)
            val credit = WorkBuddyClient.creditSummary(ready.accessToken, ready.uid, ready.domain)
            AppExecutors.main {
                if (userTriggered) setBusy(false)
                if (userTriggered) {
                    // 两个接口各自可能失败，分别提示，避免"刷新了但没反应"的错觉
                    if (status is WorkBuddyClient.Res.Err) {
                        Toast.makeText(this, describe(status.error), Toast.LENGTH_LONG).show()
                    } else if (credit is WorkBuddyClient.Res.Err) {
                        Toast.makeText(this, describe(credit.error), Toast.LENGTH_LONG).show()
                    }
                }
                applyAll(ready, status, credit)
            }
        }
    }

    /** 签到状态与余额分别合并写回仓库并重绘；任一接口失败不影响另一个 */
    private fun applyAll(
        base: WbAccount,
        status: WorkBuddyClient.Res<WorkBuddyClient.CheckinStatus>,
        credit: WorkBuddyClient.Res<WorkBuddyClient.CreditSummary>
    ) {
        if (status !is WorkBuddyClient.Res.Ok && credit !is WorkBuddyClient.Res.Ok) return
        var acc = base
        if (status is WorkBuddyClient.Res.Ok) acc = WbSession.merge(acc, status.value)
        if (credit is WorkBuddyClient.Res.Ok) acc = WbSession.mergeBalance(acc, credit.value)
        WbRepository.update(acc)
        account = acc
        render(acc)
    }

    private fun setBusy(busy: Boolean) {
        val a = account ?: return
        val done = a.checkedIn == true || a.active == false
        binding.btnCheckin.isEnabled = !busy && !done
        binding.btnCheckin.alpha = if (busy || done) 0.45f else 1f
        binding.btnRefresh.isEnabled = !busy
    }

    // ---------------- 小工具 ----------------

    private fun describe(error: WorkBuddyClient.ApiError): String = when (error) {
        WorkBuddyClient.ApiError.Timeout -> getString(R.string.err_timeout)
        WorkBuddyClient.ApiError.Network -> getString(R.string.wb_network)
        WorkBuddyClient.ApiError.Unauthorized -> getString(R.string.wb_expired)
        WorkBuddyClient.ApiError.Parse -> getString(R.string.err_parse)
        is WorkBuddyClient.ApiError.Business ->
            getString(R.string.wb_api_error, error.message.ifBlank { "code=${error.code}" })
    }

    private fun cellBackground(fill: Int, strokeColor: Int?): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(fill)
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun resolveSecondaryColor(): Int {
        val value = TypedValue()
        theme.resolveAttribute(android.R.attr.textColorSecondary, value, true)
        return if (value.resourceId != 0) {
            ContextCompat.getColor(this, value.resourceId)
        } else if (value.data != 0) {
            value.data
        } else {
            Color.GRAY
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /** 2026-11-30T23:59:59+08:00 → 11-30 */
    private fun shortDate(raw: String): String {
        val head = raw.substringBefore('T').substringBefore(' ').replace('/', '-')
        val parts = head.split('-')
        return if (parts.size >= 3) "${parts[1]}-${parts[2]}" else head
    }
}
