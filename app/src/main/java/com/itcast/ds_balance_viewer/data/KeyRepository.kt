package com.itcast.ds_balance_viewer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 密钥仓库：内存缓存 + 持久化。
 *
 * 密钥数量是个位数级别，数据量极小，所以直接在内存里维护一个 List，
 * 每次增删改后整体序列化写盘，避免引入数据库带来的复杂度。
 * 落盘时 secret 字段走 SecureStore 加密，其余字段（别名、余额快照）明文保存。
 */
object KeyRepository {

    private const val STORE_KEY = "keys_json"
    private const val AUTO_REFRESH_KEY = "auto_refresh"

    private val cache = mutableListOf<ApiKey>()
    private var loaded = false

    /** 在 Application 启动时调用一次 */
    fun init(context: Context) {
        SecureStore.init(context)
        if (loaded) return
        load()
        loaded = true
    }

    private fun load() {
        cache.clear()
        val raw = SecureStore.getString(STORE_KEY)
        if (raw.isNullOrBlank()) return
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                // 解密失败说明该条记录不可用（例如 Keystore 被系统重置），直接跳过
                val secret = SecureStore.decrypt(obj.optString("secret")) ?: continue
                cache.add(
                    ApiKey(
                        id = obj.optString("id"),
                        alias = obj.optString("alias"),
                        secret = secret,
                        lastTotal = obj.optString("lastTotal").ifBlank { null },
                        lastGranted = obj.optString("lastGranted").ifBlank { null },
                        lastToppedUp = obj.optString("lastToppedUp").ifBlank { null },
                        lastCurrency = obj.optString("lastCurrency").ifBlank { null },
                        lastAvailable = if (obj.has("lastAvailable")) obj.getBoolean("lastAvailable") else null,
                        lastUpdated = obj.optLong("lastUpdated", 0L)
                    )
                )
            }
        }
    }

    fun all(): List<ApiKey> = cache.toList()

    fun find(id: String): ApiKey? = cache.firstOrNull { it.id == id }

    fun findBySecret(secret: String): ApiKey? = cache.firstOrNull { it.secret == secret }

    fun count(): Int = cache.size

    fun add(key: ApiKey) {
        cache.add(key)
        persist()
    }

    /** 按 id 覆盖更新，用于刷新余额、改名 */
    fun update(key: ApiKey) {
        val index = cache.indexOfFirst { it.id == key.id }
        if (index >= 0) {
            cache[index] = key
            persist()
        }
    }

    fun remove(id: String) {
        val removed = cache.removeAll { it.id == id }
        if (removed) persist()
    }

    /** 启动时自动刷新开关，默认开启 */
    var autoRefresh: Boolean
        get() = SecureStore.getString(AUTO_REFRESH_KEY) != "false"
        set(value) {
            SecureStore.putString(AUTO_REFRESH_KEY, value.toString())
        }

    private fun persist() {
        val array = JSONArray()
        cache.forEach { key ->
            array.put(JSONObject().apply {
                put("id", key.id)
                put("alias", key.alias)
                put("secret", SecureStore.encrypt(key.secret))
                put("lastTotal", key.lastTotal ?: "")
                put("lastGranted", key.lastGranted ?: "")
                put("lastToppedUp", key.lastToppedUp ?: "")
                put("lastCurrency", key.lastCurrency ?: "")
                key.lastAvailable?.let { put("lastAvailable", it) }
                put("lastUpdated", key.lastUpdated)
            })
        }
        SecureStore.putString(STORE_KEY, array.toString())
    }
}
