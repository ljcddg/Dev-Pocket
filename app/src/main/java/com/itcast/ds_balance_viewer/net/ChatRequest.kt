package com.itcast.ds_balance_viewer.net

import com.itcast.ds_balance_viewer.data.ChatTurn
import com.itcast.ds_balance_viewer.data.ApiKey
import com.itcast.ds_balance_viewer.data.Provider
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ChatRequest(
    private val resolver: android.content.ContentResolver,
    private val connect: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
) {
    private fun filePart(key: ApiKey, item: ChatTurn, uploaded: (Long, String) -> Unit): JSONObject {
        val file = JSONObject(item.attachment)
        fun part(id: String) = JSONObject().put("type","file").put("file_id",id)
        if (file.optString("account") == key.id && file.optLong("expires") > System.currentTimeMillis()) return part(file.getString("remote"))
        val name = file.getString("name").replace(Regex("""[^\p{L}\p{N}._ -]"""), "_").takeLast(160)
        val purpose = if (name.substringAfterLast('.').lowercase() in setOf("pdf","doc","docx","ppt","pptx")) "text_recognition" else "user_data"
        val boundary = java.util.UUID.randomUUID().toString()
        val http = connect("https://api.deepseek.com/files")
        connection = http
        try {
            if (stopped) throw IOException("已停止")
            http.requestMethod = "POST"
            http.instanceFollowRedirects = false
            http.doOutput = true
            http.connectTimeout = 15000
            http.readTimeout = 180000
            http.setChunkedStreamingMode(65536)
            http.setRequestProperty("Authorization", "Bearer " + key.secret)
            http.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
            http.outputStream.buffered().use { out ->
                fun line(text: String) = out.write((text + "\r\n").toByteArray())
                fun field(label: String, value: String) {
                    line("--" + boundary)
                    line("Content-Disposition: form-data; name=\"" + label + "\"")
                    line("")
                    line(value)
                }
                field("purpose", purpose)
                field("expires_after[anchor]", "created_at")
                field("expires_after[seconds]", "86400")
                line("--" + boundary)
                line("Content-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"")
                line("Content-Type: application/octet-stream")
                line("")
                val uri = android.net.Uri.parse(file.getString("uri"))
                resolver.openInputStream(uri)?.use { input -> input.copyTo(out) } ?: throw IOException("无法读取附件，请重新选择")
                line("")
                line("--" + boundary + "--")
            }
            if (http.responseCode !in 200..299) throw IOException("附件上传失败（HTTP " + http.responseCode + "），请检查文件格式、大小和账户权限")
            val result = JSONObject(http.inputStream.bufferedReader().use { it.readText() })
            val id = result.getString("id")
            file.put("remote", id).put("account", key.id).put("expires", result.optLong("expires_at") * 1000L)
            uploaded(item.id, file.toString())
            return part(id)
        } finally { http.disconnect() }
    }

    @Volatile private var stopped = false
    @Volatile private var connection: HttpURLConnection? = null

    fun cancel() {
        stopped = true
        connection?.disconnect()
    }

    fun stream(key: ApiKey, model: String, thinking: Boolean, turns: List<ChatTurn>, uploaded: (Long, String) -> Unit, onText: (String) -> Unit): String {
        val messages = JSONArray()
        turns.forEachIndexed { i, turn ->
            // 跳过已放弃的问题，重试最后一题时也不发送旧答案。
            if (i == turns.lastIndex || turn.answer.isNotEmpty()) {
                val content: Any = if (turn.attachment.isEmpty()) turn.question else {
                    if (model != "deepseek-flash") throw IOException("带附件的对话请切换到 DeepSeek Flash")
                    JSONArray().put(JSONObject().put("type","text").put("text",turn.question)).put(filePart(key,turn,uploaded))
                }
                messages.put(JSONObject().put("role", "user").put("content", content))
                if (i < turns.lastIndex) messages.put(JSONObject().put("role", "assistant").put("content", turn.answer))
            }
        }
        val body = JSONObject().put("model", model).put("messages", messages).put("stream", true)
            .put("thinking", JSONObject().put("type", if (thinking) "enabled" else "disabled"))
        if (turns.last().search && model == "deepseek-flash") body.put("tools", JSONArray().put(JSONObject().put("type","deepseek_web_search")))
        val endpoint = when (key.provider) {
            Provider.DEEPSEEK -> "https://api.deepseek.com/chat/completions"
            Provider.KIMI -> "https://api.moonshot.cn/v1/chat/completions"
            Provider.GLM -> "https://open.bigmodel.cn/api/paas/v4/chat/completions"
        }
        val http = connect(endpoint)
        connection = http
        try {
            if (stopped) throw IOException("已停止")
            http.requestMethod = "POST"
            http.instanceFollowRedirects = false
            http.connectTimeout = 15000
            http.readTimeout = 120000
            http.doOutput = true
            http.setRequestProperty("Authorization", "Bearer " + key.secret)
            http.setRequestProperty("Content-Type", "application/json")
            http.setRequestProperty("Accept", "text/event-stream")
            http.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = http.responseCode
            if (status !in 200..299) throw IOException(when (status) {
                401, 403 -> "密钥无效或没有访问权限"
                402 -> "API 账户余额不足"
                429 -> "请求过于频繁，请稍后重试"
                400, 422 -> "请求内容有误或对话过长，可尝试新对话"
                else -> "服务暂时不可用（HTTP " + status + "）"
            })
            val answer = StringBuilder()
            var lastUpdate = 0L
            var finished = false
            http.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (!stopped) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val event = line.substringAfter(':').trim()
                    if (event == "[DONE]") { finished = true; break }
                    val chunk = JSONObject(event)
                    if (chunk.has("error")) throw IOException("服务端中断了回复")
                    val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: continue
                    val delta = choice.optJSONObject("delta")
                    if (delta != null && !delta.isNull("content")) answer.append(delta.getString("content"))
                    val citations = delta?.optJSONArray("annotations")
                    if (citations != null) {
                        val urls = (0 until citations.length()).mapNotNull { citations.optJSONObject(it)?.optJSONObject("url_citation")?.optString("url") }
                            .filter { it.startsWith("https://") || it.startsWith("http://") }.distinct()
                        if (urls.isNotEmpty()) answer.append("\n\n参考来源\n" + urls.mapIndexed { i, url -> "- [来源 " + (i + 1) + "](" + url.replace(")", "%29") + ")" }.joinToString("\n"))
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (answer.isNotEmpty() && now - lastUpdate >= 80) {
                        onText(answer.toString())
                        lastUpdate = now
                    }
                    when (choice.optString("finish_reason")) {
                        "length" -> answer.append("\n\n（已达到回复长度上限）")
                        "content_filter" -> throw IOException("该回复已被服务端过滤")
                        "insufficient_system_resource" -> throw IOException("服务繁忙，请重试")
                    }
                }
            }
            if (!finished || answer.isBlank()) throw IOException("未收到完整回复，请重试")
            return answer.toString()
        } finally {
            http.disconnect()
            connection = null
        }
    }
}
