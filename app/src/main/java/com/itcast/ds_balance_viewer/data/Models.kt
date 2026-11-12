package com.itcast.ds_balance_viewer.data

import java.util.UUID

/**
 * 一个币种的余额明细，对应接口 balance_infos 数组里的一项。
 * 接口返回的金额是字符串（如 "110.00"），这里保持字符串避免精度丢失。
 */
data class BalanceItem(
    val currency: String,
    val total: String,
    val granted: String,
    val toppedUp: String
)

/**
 * 一次余额查询的完整结果。
 * @param available 接口的 is_available 字段，表示余额是否足够继续调用 API
 * @param rawJson   原始响应体，供详情页"查看原始数据"排查问题
 */
data class BalanceResult(
    val available: Boolean,
    val items: List<BalanceItem>,
    val rawJson: String
) {
    /** 列表页只展示主币种（通常是 CNY） */
    val primary: BalanceItem? get() = items.firstOrNull()
}

/**
 * 用户保存的一把 API 密钥。
 * @param secret 明文密钥，仅在内存中存在；落盘时由 SecureStore 加密
 * @param provider 商家，决定查询哪个余额接口；没有该字段的旧数据按 DeepSeek 处理
 * @param lastTotal 等字段是上一次查询的快照，用于列表页秒开显示
 */
data class ApiKey(
    val id: String = UUID.randomUUID().toString(),
    val alias: String,
    val secret: String,
    val provider: Provider = Provider.DEEPSEEK,
    val lastTotal: String? = null,
    val lastGranted: String? = null,
    val lastToppedUp: String? = null,
    val lastCurrency: String? = null,
    val lastAvailable: Boolean? = null,
    val lastUpdated: Long = 0L
) {
    /** 没有备注名时，用掩码密钥作为展示标题 */
    fun displayName(): String = alias.ifBlank { "未命名" }
}
