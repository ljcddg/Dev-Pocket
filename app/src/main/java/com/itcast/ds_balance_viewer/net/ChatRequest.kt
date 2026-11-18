package com.itcast.ds_balance_viewer.net

import com.itcast.ds_balance_viewer.data.ChatTurn
import com.itcast.ds_balance_viewer.data.ChatSource
import com.itcast.ds_balance_viewer.data.ApiKey
import com.itcast.ds_balance_viewer.data.Provider
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ChatReply(
    val text: String = "", val reasoning: String = "", val status: String = "",
    val sources: List<ChatSource> = emptyList()
)

private data class SearchHit(val title: String, val url: String, val summary: String, val date: String)

class ChatRequest(
    private val resolver: android.content.ContentResolver,
    private val connect: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
) {
    private val recallQuestion = Regex(
        """我\s*(?:刚才|刚刚|之前|上一条)\s*(?:问|说)(?:了)?(?:你)?(?:了)?\s*(?:什么|啥)(?:了|呢|来着)?\s*[？?。！!]*"""
    )

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

    private fun search(key: ApiKey, question: String): List<SearchHit> {
        if (key.provider != Provider.DEEPSEEK) throw IOException("智能搜索需要 DeepSeek 密钥")
        val http = connect("https://api.deepseek.com/v1/tools/search")
        connection = http
        try {
            if (stopped) throw IOException("已停止")
            http.requestMethod = "POST"
            http.instanceFollowRedirects = false
            http.doOutput = true
            http.connectTimeout = 15000
            http.readTimeout = 60000
            http.setRequestProperty("Authorization", "Bearer " + key.secret)
            http.setRequestProperty("Content-Type", "application/json")
            val length = question.codePointCount(0, question.length).coerceAtMost(128)
            val query = question.substring(0, question.offsetByCodePoints(0,length))
            val payload = JSONObject().put("query",query).put("count",5)
            http.outputStream.use { it.write(payload.toString().toByteArray()) }
            if (http.responseCode !in 200..299) throw IOException("联网检索失败（HTTP " + http.responseCode + "），请检查账户权限、余额或稍后重试")
            val rows = JSONObject(http.inputStream.bufferedReader().use { it.readText() }).getJSONArray("results")
            return buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val url = row.optString("url")
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        add(SearchHit(row.optString("title").ifBlank { url },url,
                            row.optString("summary").ifBlank { row.optString("snippet") }.take(2500),row.optString("date")))
                    }
                }
            }.distinctBy { it.url }
        } finally { http.disconnect() }
    }

    fun stream(key: ApiKey, model: String, thinking: Boolean, turns: List<ChatTurn>, uploaded: (Long, String) -> Unit, onText: (ChatReply) -> Unit): ChatReply {
        if (stopped) throw IOException("已停止")
        val latest = turns.last()
        val deviceTime = DeviceDateTime()
        if (latest.attachment.isEmpty()) {
            deviceTime.calendarAnswer(latest.question)?.let { return ChatReply(text = it) }
            if (recallQuestion.matches(latest.question.trim())) {
                val previous = turns.getOrNull(turns.lastIndex - 1)?.question
                return ChatReply(text = if (previous == null) "这是你在这段对话里发来的第一条消息。"
                    else "你的上一条消息是「$previous」。")
            }
        }

        val searching = latest.search
        val searchGuidance = if (searching) {
            "本次回答必须充分利用下方提供的联网检索资料：" +
                "逐条阅读并整合与问题直接相关的信息，不要只复述标题或笼统概括；" +
                "在对应结论后标注资料编号（如 [1][2]）及该条资料的日期；" +
                "资料之间存在冲突或时效差异时明确指出；" +
                "资料不足以支撑某结论时说明「现有资料无法确认」，不要臆测；" +
                "回答要结构完整、分点清晰、覆盖问题的各个方面，不要过于简略。"
        } else ""
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", deviceTime.systemContext() + searchGuidance))
        val hits = if (searching) {
            onText(ChatReply(status = "正在联网检索…"))
            search(key, latest.question)
        } else emptyList()
        val sources = hits.map { ChatSource(it.title, it.url) }
        val searchStatus = if (searching) "联网检索完成 · " + hits.size + " 条结果" else ""
        if (searching) onText(ChatReply(status = searchStatus))
        turns.forEachIndexed { i, turn ->
            // 跳过已放弃的问题，重试最后一题时也不发送旧答案。
            if (i == turns.lastIndex || turn.answer.isNotEmpty()) {
                val question = if (searching && i == turns.lastIndex) {
                    val evidence = hits.mapIndexed { index, hit -> "[" + (index+1) + "] " + hit.title + "\n" + hit.url + "\n" + hit.date + "\n" + hit.summary }
                    turn.question + "\n\n本次联网检索资料：\n" + evidence.joinToString("\n\n").ifEmpty { "未找到相关结果" }
                } else turn.question
                val content: Any = if (turn.attachment.isEmpty()) question else {
                    if (model != "deepseek-flash") throw IOException("带附件的对话请切换到 DeepSeek Flash")
                    JSONArray().put(JSONObject().put("type","text").put("text",question)).put(filePart(key,turn,uploaded))
                }
                messages.put(JSONObject().put("role", "user").put("content", content))
                if (i < turns.lastIndex) messages.put(JSONObject().put("role", "assistant").put("content", turn.answer))
            }
        }
        val body = JSONObject().put("model", model).put("messages", messages).put("stream", true)
            .put("thinking", JSONObject().put("type", if (thinking) "enabled" else "disabled"))
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
            val reasoning = StringBuilder()
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
                    if (delta != null && !delta.isNull("reasoning_content")) reasoning.append(delta.getString("reasoning_content"))
                    val now = android.os.SystemClock.elapsedRealtime()
                    if ((answer.isNotEmpty() || reasoning.isNotEmpty()) && now - lastUpdate >= 80) {
                        onText(ChatReply(answer.toString(),reasoning.toString(),searchStatus))
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
            if (searching && sources.isEmpty()) answer.append("\n\n联网检索未找到相关来源。")
            return ChatReply(answer.toString(), reasoning.toString(), sources = sources)
        } finally {
            http.disconnect()
            connection = null
        }
    }
}
