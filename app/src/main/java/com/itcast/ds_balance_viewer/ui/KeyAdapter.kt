package com.itcast.ds_balance_viewer.ui

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.itcast.ds_balance_viewer.R
import com.itcast.ds_balance_viewer.data.ApiKey
import com.itcast.ds_balance_viewer.databinding.ItemKeyBinding
import com.itcast.ds_balance_viewer.util.Formatting

/**
 * 首页密钥列表适配器。
 * 数据量很小（通常个位数），直接 notifyDataSetChanged 即可，无需 DiffUtil。
 */
class KeyAdapter(
    private val onClick: (ApiKey) -> Unit,
    private val onLongClick: (ApiKey) -> Unit
) : RecyclerView.Adapter<KeyAdapter.Holder>() {

    private val items = mutableListOf<ApiKey>()

    fun submit(list: List<ApiKey>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemKeyBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemKeyBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(key: ApiKey) {
            val context = binding.root.context

            binding.tvAlias.text = key.displayName()
            binding.tvProvider.text = key.provider.shortName
            binding.tvMask.text = Formatting.mask(key.secret)

            binding.tvUpdated.text = if (key.lastUpdated > 0L) {
                context.getString(R.string.updated_at, Formatting.relativeTime(key.lastUpdated))
            } else {
                context.getString(R.string.never_updated)
            }

            binding.tvBalance.text = if (key.lastTotal != null) {
                Formatting.money(key.lastTotal, key.lastCurrency)
            } else {
                "—"
            }

            // 状态胶囊：文字按状态着色，底色为同色 10% 透明
            val statusColor = when (key.lastAvailable) {
                true -> ContextCompat.getColor(context, R.color.ds_success)
                false -> ContextCompat.getColor(context, R.color.ds_danger)
                null -> themeSecondaryColor()
            }
            binding.tvStatus.setText(
                when (key.lastAvailable) {
                    true -> R.string.status_available
                    false -> R.string.status_unavailable
                    null -> R.string.status_unknown
                }
            )
            binding.tvStatus.setTextColor(statusColor)
            // 底色用同色 12% 透明度，浅深色下都是柔和的胶囊徽标
            binding.tvStatus.background.mutate()
                .setTint(ColorUtils.setAlphaComponent(statusColor, 0x1F))

            binding.root.setOnClickListener { onClick(key) }
            binding.root.setOnLongClickListener {
                onLongClick(key)
                true
            }
        }

        /** 解析主题里的 textColorSecondary，保证深浅色下都正确 */
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
