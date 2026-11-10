package com.itcast.ds_balance_viewer.util

import android.util.Base64
import org.json.JSONObject

/**
 * JWT 载荷解析。
 *
 * WorkBuddy 的 accessToken 是标准 JWT，载荷里 sub 即账号 uid、exp 是过期时间戳。
 * App 不校验签名（签名由服务端校验），只读取这两个字段用于展示和过期判断。
 */
object Jwt {

    data class Claims(val sub: String?, val expSeconds: Long, val username: String?)

    fun parse(token: String): Claims? {
        val parts = token.split('.')
        if (parts.size < 2) return null
        return runCatching {
            val payload = parts[1]
            val normalized = payload.replace('-', '+').replace('_', '/')
            val padding = (4 - normalized.length % 4) % 4
            val bytes = Base64.decode(normalized + "=".repeat(padding), Base64.NO_WRAP)
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            Claims(
                sub = json.optString("sub").ifBlank { null },
                expSeconds = json.optLong("exp", 0L),
                username = json.optString("preferred_username").ifBlank { null }
            )
        }.getOrNull()
    }

    /** 从令牌里取 uid，取不到时返回空串 */
    fun subjectOf(token: String): String = parse(token)?.sub.orEmpty()
}
