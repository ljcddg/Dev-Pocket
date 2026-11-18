package com.itcast.ds_balance_viewer.data

import org.json.JSONArray
import org.json.JSONObject

internal data class ChatAttachment(
    val uri: String,
    val name: String,
    val mime: String = "",
    val remote: String = "",
    val account: String = "",
    val expires: Long = 0
) {
    val isImage get() = mime.startsWith("image/") ||
        name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif")

    companion object {
        const val MAX_PER_MESSAGE = 20

        fun decode(stored: String): List<ChatAttachment> {
            if (stored.isBlank()) return emptyList()
            // 旧版的单附件以对象存储，升级后仍可查看和重新生成。
            val array = if (stored.trimStart().startsWith("[")) JSONArray(stored)
                else JSONArray().put(JSONObject(stored))
            return (0 until array.length()).map { index ->
                val obj = array.getJSONObject(index)
                ChatAttachment(obj.getString("uri"), obj.optString("name", "附件"), obj.optString("mime"),
                    obj.optString("remote"), obj.optString("account"), obj.optLong("expires"))
            }
        }

        fun encode(files: List<ChatAttachment>): String {
            if (files.isEmpty()) return ""
            return JSONArray().apply {
                files.forEach { file ->
                    put(JSONObject().put("uri", file.uri).put("name", file.name).put("mime", file.mime)
                        .put("remote", file.remote).put("account", file.account).put("expires", file.expires))
                }
            }.toString()
        }
    }
}
