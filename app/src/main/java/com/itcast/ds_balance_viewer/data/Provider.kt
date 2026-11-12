package com.itcast.ds_balance_viewer.data

/**
 * 支持「URL + Bearer Token」查询余额的商家。
 *
 * 只有这三家提供可直接调用的余额接口：
 *  - DeepSeek  GET api.deepseek.com/user/balance
 *  - Kimi      GET api.moonshot.cn/v1/users/me/balance
 *  - 智谱 GLM   GET open.bigmodel.cn/api/paas/v4/users/me/balance
 * 其余平台（OpenAI、百炼、千帆等）要么没有余额接口，要么只能进控制台看，所以不放进来。
 *
 * [id] 是落盘用的稳定标识，改名不能动它，否则老数据会被当成 DeepSeek。
 */
enum class Provider(
    val id: String,
    val displayName: String,
    val shortName: String,
    val currency: String,
    val keyHint: String
) {
    DEEPSEEK("deepseek", "DeepSeek 开放平台", "DeepSeek", "CNY", "sk-..."),
    KIMI("kimi", "Kimi 开放平台", "Kimi", "CNY", "sk-..."),
    GLM("glm", "智谱 GLM 开放平台", "GLM", "CNY", "xxxxx.xxxxx");

    companion object {
        /** 兼容没有 provider 字段的旧数据 */
        fun fromId(id: String?): Provider =
            entries.firstOrNull { it.id == id } ?: DEEPSEEK
    }
}
