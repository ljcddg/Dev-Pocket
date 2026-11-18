package com.itcast.ds_balance_viewer.data

object ChatPreferences {
    // 只保存余额仓库的稳定 ID，删除密钥后要求用户重新指定。
    var keyId: String?
        get() = SecureStore.getString("chat_key_id")
        set(value) { if (value == null) SecureStore.remove("chat_key_id") else SecureStore.putString("chat_key_id", value) }

    val key: ApiKey? get() = keyId?.let(KeyRepository::find)

    fun models(provider: Provider): List<String> = when (provider) {
        Provider.DEEPSEEK -> listOf("deepseek-flash", "deepseek-v4-pro")
        Provider.KIMI -> listOf("kimi-k2.5", "kimi-k2.6")
        Provider.GLM -> listOf("glm-4.7-flash", "glm-4.7")
    }

    fun model(provider: Provider): String =
        SecureStore.getString("chat_model_" + provider.id) ?: models(provider).first()

    fun setModel(provider: Provider, value: String) = SecureStore.putString("chat_model_" + provider.id, value)

    var thinking: Boolean
        get() = SecureStore.getString("chat_thinking") == "true"
        set(value) = SecureStore.putString("chat_thinking", value.toString())
}
