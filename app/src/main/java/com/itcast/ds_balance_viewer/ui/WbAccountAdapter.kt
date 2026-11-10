package com.itcast.ds_balance_viewer.ui

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.itcast.ds_balance_viewer.R
import com.itcast.ds_balance_viewer.data.WbAccount
import com.itcast.ds_balance_viewer.databinding.ItemWbAccountBinding
import com.itcast.ds_balance_viewer.util.Formatting

/**
 * 签到 Tab 的账号列表适配器。
 *
 * 每个卡片展示：备注名、累计积分、今日到账、连签进度、签到按钮。
 * 短按卡片进积分详情页，长按弹操作菜单。
 */
class WbAccountAdapter(
    private val onCheckin: (WbAccount) -> Unit,
    private val onOpenDetail: (WbAccount) -> Unit,
    private val onLongClick: (WbAccount) -> Unit
) : RecyclerView.Adapter<WbAccountAdapter.Holder>() {

    private val items = mutableListOf<WbAccount>()

    fun submit(list: List<WbAccount>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemWbAccountBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemWbAccountBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(account: WbAccount) {
            val context = binding.root.context

            binding.tvAlias.text = account.displayName()
            binding.tvUid.text = context.getString(R.string.wb_uid_mask, Formatting.mask(account.uid))

            // 余额（账户本周期剩余额度）：未知时显示占位符并提示刷新
            val hasBalance = account.creditLeft >= 0
            binding.tvTotalCredits.text = Formatting.credit(account.creditLeft)
            binding.tvTodayCredit.text = when {
                account.checkedIn == true && account.todayCredit > 0 ->
                    context.getString(R.string.wb_credit_today, account.todayCredit)
                account.checkedIn == true -> context.getString(R.string.wb_credit_today_none)
                account.dailyCredit > 0 ->
                    context.getString(R.string.wb_credit_today_pending, account.dailyCredit)
                !hasBalance -> context.getString(R.string.wb_credit_unknown)
                else -> ""
            }

            // 连签进度：能算出距下次奖励的天数时给具体提示
            binding.tvStreak.text = when {
                account.streakDays > 0 && account.nextStreakDay > account.streakDays &&
                        account.streakBonusCredit > 0 -> context.getString(
                    R.string.wb_streak_progress,
                    account.streakDays,
                    account.nextStreakDay - account.streakDays,
                    Formatting.credit(account.streakBonusCredit)
                )
                account.streakDays > 0 -> context.getString(R.string.wb_streak_plain, account.streakDays)
                else -> context.getString(R.string.wb_streak_zero)
            }

            // 次要信息：签到累计获得 + 上次签到时间（两者都有用，用 · 连接）
            val detailParts = ArrayList<String>(2)
            if (account.totalCredits >= 0) {
                detailParts.add(
                    context.getString(
                        R.string.wb_credit_checkin_total,
                        Formatting.credit(account.totalCredits)
                    )
                )
            }
            when {
                account.lastCheckinAt > 0L -> detailParts.add(
                    context.getString(
                        R.string.wb_credit_last_checkin,
                        Formatting.relativeTime(account.lastCheckinAt)
                    )
                )
                account.lastResult != null -> detailParts.add(account.lastResult)
            }
            binding.tvLastResult.text = detailParts.joinToString(" · ").ifBlank {
                context.getString(R.string.wb_never_checked)
            }

            // 状态胶囊
            val statusColor = when {
                account.active == false -> ContextCompat.getColor(context, R.color.ds_warning)
                account.checkedIn == true -> ContextCompat.getColor(context, R.color.ds_success)
                account.checkedIn == false -> ContextCompat.getColor(context, R.color.ds_primary)
                else -> themeSecondaryColor()
            }
            binding.tvStatus.setText(
                when {
                    account.active == false -> R.string.wb_activity_closed
                    account.checkedIn == true -> R.string.wb_checked_today
                    account.checkedIn == false -> R.string.wb_not_checked
                    else -> R.string.wb_unknown_status
                }
            )
            binding.tvStatus.setTextColor(statusColor)
            binding.tvStatus.background.mutate()
                .setTint(ColorUtils.setAlphaComponent(statusColor, 0x1F))

            // 已签到时按钮变为不可点的"今日已签"
            val done = account.checkedIn == true || account.active == false
            binding.btnCheckin.isEnabled = !done
            binding.btnCheckin.setText(
                when {
                    account.active == false -> R.string.wb_activity_closed
                    account.checkedIn == true -> R.string.wb_checked_today
                    else -> R.string.wb_checkin_now
                }
            )
            binding.btnCheckin.setOnClickListener { onCheckin(account) }
            binding.root.setOnClickListener { onOpenDetail(account) }
            binding.root.setOnLongClickListener {
                onLongClick(account)
                true
            }
        }

        private fun themeSecondaryColor(): Int {
            val context = binding.root.context
            val value = TypedValue()
            context.theme.resolveAttribute(android.R.attr.textColorSecondary, value, true)
            return if (value.resourceId != 0) {
                ContextCompat.getColor(context, value.resourceId)
            } else {
                value.data
            }
        }
    }
}
