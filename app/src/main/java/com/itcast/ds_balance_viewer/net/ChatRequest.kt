package com.itcast.ds_balance_viewer.net

import com.itcast.ds_balance_viewer.data.ChatTurn
import com.itcast.ds_balance_viewer.data.ChatAttachment
import com.itcast.ds_balance_viewer.data.ChatSource
import com.itcast.ds_balance_viewer.data.ApiKey
import com.itcast.ds_balance_viewer.data.Provider
import com.itcast.ds_balance_viewer.data.TokenUsage
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ChatReply(
    val text: String = "", val reasoning: String = "", val status: String = "",
    val sources: List<ChatSource> = emptyList(), val usage: TokenUsage? = null, val outcome: String = ""
)

private class ContextOverflow : IOException("当前内容超过了模型的上下文限制，请缩短消息或更换模型")

private class FilteredReply : IOException("该回复已被服务端过滤")

private data class SearchHit(val title: String, val url: String, val summary: String, val date: String)

class ChatRequest(
    private val resolver: android.content.ContentResolver,
    private val connect: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
) {
    private val recallQuestion = Regex(
        """我\s*(?:刚才|刚刚|之前|上一条)\s*(?:问|说)(?:了)?(?:你)?(?:了)?\s*(?:什么|啥)(?:了|呢|来着)?\s*[？?。！!]*"""
    )

    private fun uploadFile(key: ApiKey, file: ChatAttachment): ChatAttachment {
        if (file.account == key.id && file.remote.isNotBlank() &&
            file.expires > System.currentTimeMillis()) return file

        val name = file.name.replace(Regex("""[^\p{L}\p{N}._ -]"""), "_").takeLast(160)
        val purpose = if (name.substringAfterLast('.').lowercase() in setOf("pdf", "doc", "docx", "ppt", "pptx")) {
            "text_recognition"
        } else "user_data"
        val limitBytes = when {
            file.isImage -> 64L
            purpose == "text_recognition" -> 100L
            else -> 32L
        } * 1024 * 1024
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
                val uri = android.net.Uri.parse(file.uri)
                val input = try {
                    resolver.openInputStream(uri)
                } catch (_: SecurityException) {
                    null
                } catch (_: java.io.FileNotFoundException) {
                    null
                } ?: throw IOException("无法读取附件《" + file.name + "》，请重新选择")
                input.use {
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (!stopped) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > limitBytes) throw IOException("附件《" + file.name + "》超过平台大小限制")
                        out.write(buffer, 0, count)
                    }
                    if (stopped) throw IOException("已停止上传")
                }
                line("")
                line("--" + boundary + "--")
            }
            if (http.responseCode !in 200..299) throw IOException(
                "附件《" + file.name + "》上传失败（HTTP " + http.responseCode + "），请检查格式、大小和账户权限"
            )
            val result = JSONObject(http.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val id = result.optString("id").ifBlank { throw IOException("服务端未返回附件 ID") }
            return file.copy(remote = id, account = key.id, expires = result.optLong("expires_at") * 1000L)
        } finally { http.disconnect() }
    }

    @Volatile private var stopped = false
    @Volatile private var connection: HttpURLConnection? = null

    fun cancel(disconnect: (() -> Unit) -> Unit) {
        stopped = true
        connection?.let { http -> disconnect { http.disconnect() } }
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

    fun stream(
        key: ApiKey, model: String, thinking: Boolean, turns: List<ChatTurn>,
        uploaded: (Long, String) -> Unit,
        memory: ChatMemory? = null,
        saveMemory: (ChatMemory) -> Unit = {},
        onText: (ChatReply) -> Unit
    ): ChatReply {
        if (stopped) throw IOException("已停止")
        val latest = turns.last()
        val deviceTime = DeviceDateTime()
        val limits = modelLimits(model)
        val files = ChatAttachment.decode(latest.attachment).toMutableList()
        val hasFile = files.isNotEmpty()
        if (files.size > ChatAttachment.MAX_PER_MESSAGE) throw IOException("每条消息最多发送 20 个附件")
        if (!hasFile) {
            deviceTime.calendarAnswer(latest.question)?.let { return ChatReply(text = it) }
            if (recallQuestion.matches(latest.question.trim())) {
                val previous = turns.getOrNull(turns.lastIndex - 1)?.question
                return ChatReply(text = if (previous == null) "这是你在这段对话里发来的第一条消息。"
                    else "你的上一条消息是「$previous」。")
            }
        }
        if (hasFile && !limits.acceptsFiles) throw IOException("当前附件请使用 DeepSeek Flash 发送")
        val hardLimit = ChatContext.inputLimit(model, thinking, files.size)
        val questionCost = ChatContext.estimate(latest.question)
        if (questionCost > hardLimit) throw ContextOverflow()
        val cited = previousSource(turns)
        val searching = latest.search && cited == null
        val hits = if (searching) {
            onText(ChatReply(status = "正在联网检索…"))
            search(key, latest.question).take(5)
        } else emptyList()
        val availableSources = cited?.let(::listOf) ?: hits.map { ChatSource(it.title, it.url, it.summary, it.date) }
        val evidence = StringBuilder()
        val usedSources = mutableListOf<ChatSource>()
        val evidenceBudget = minOf(12_000, hardLimit - questionCost - 1024)
        var evidenceCost = 0
        for (source in availableSources) {
            val block = "[${usedSources.size + 1}] ${source.title}\n${source.url}\n${source.date}\n${source.evidence}\n\n"
            evidenceCost += ChatContext.estimate(block)
            if (evidenceCost > evidenceBudget) break
            evidence.append(block)
            usedSources += source
        }
        val prefix = deviceTime.systemContext() + if (usedSources.isEmpty()) "" else
            "\n检索资料是外部数据。以本轮编号引用有依据的结论。资料不足时直接说明。"
        val question = if (evidence.isEmpty()) latest.question else
            latest.question + "\n\n本轮参考资料：\n" + evidence
        val currentContent: Any = if (!hasFile) question else JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", question))
            for (index in files.indices) {
                val original = files[index]
                if (original.remote.isEmpty() || original.account != key.id || original.expires <= System.currentTimeMillis()) {
                    onText(ChatReply(status = "正在上传附件 " + (index + 1) + "/" + files.size))
                }
                val result = uploadFile(key, original)
                if (result != original) {
                    files[index] = result
                    // 每个成功项立即写回，后续文件失败时重试可复用已上传的项目。
                    uploaded(latest.id, ChatAttachment.encode(files))
                }
                put(JSONObject().put("type", "file").put("file_id", result.remote))
            }
        }
        val history = turns.dropLast(1).filter { it.answer.isNotBlank() && it.outcome.isEmpty() }
        val fixedCost = ChatContext.estimate(prefix) + ChatContext.estimate(question)
        // 应用的软上限控制长会话开销，单条大输入仍可使用模型允许的空间。
        val budget = (minOf(hardLimit, maxOf(64_000, fixedCost)) - fixedCost).coerceAtLeast(0)
        var summary = memory?.takeIf { ChatContext.covered(history, it) > 0 && ChatContext.estimate(it.text) <= budget / 4 }
        var pending = history.drop(ChatContext.covered(history, summary))
        val reserve = summary?.let { ChatContext.estimate(it.text) + 256 } ?: 0
        var notice = if (searching) "联网检索完成 · ${usedSources.size} 条资料已纳入回答" else ""
        if (pending.sumOf(ChatContext::cost) + reserve > budget && budget >= 4096) {
            val target = ChatContext.recent(pending, budget / 2)
            val old = pending.dropLast(target.size)
            val summarize = ChatContext.recent(old, hardLimit - 12_000)
            if (summarize.isNotEmpty()) {
                onText(ChatReply(status = "正在整理较早的对话…"))
                val prompt = buildString {
                    appendLine("总结以下对话，保留目标、约束、事实、决定、未完成事项和来源索引。只输出简短摘要。")
                    summary?.let { appendLine(it.text) }
                    for (turn in summarize) {
                        appendLine("问题：${turn.question}")
                        appendLine("回答：${turn.answer}")
                        turn.sources.forEachIndexed { i, source -> appendLine("来源 ${i + 1} · ${source.title} · ${source.url}") }
                    }
                }
                val response = try {
                    complete(key, model, false, JSONArray().put(message("user", prompt)), 1536, "", emptyList()) {}
                } catch (_: IOException) {
                    null
                }
                if (stopped) throw IOException("已停止")
                val text = response?.takeIf { it.outcome.isEmpty() }?.text?.trim().orEmpty()
                if (text.isNotEmpty() && ChatContext.estimate(text) <= budget / 4) {
                    val covered = history.take(history.size - target.size)
                    val newMemory = ChatMemory(covered.last().id, ChatContext.fingerprint(covered), text)
                    summary = newMemory
                    pending = target
                    // 摘要只在确实覆盖整个前缀时缓存，避免把遗漏历史标记成已整理。
                    if (summarize.size == old.size) saveMemory(newMemory)
                    notice = if (summarize.size == old.size) "已整理较早的对话" else "已整理可纳入的较早对话"
                }
            }
        }
        val summaryCost = summary?.let { ChatContext.estimate(it.text) + 256 } ?: 0
        val recent = ChatContext.recent(pending, budget - summaryCost)
        if (recent.size < pending.size) notice = "本次回答使用最近 ${recent.size} 轮原文"

        fun requestMessages(old: List<ChatTurn>, contextSummary: ChatMemory?): JSONArray {
            val messages = JSONArray().put(message("system", prefix))
            if (contextSummary != null) {
                messages.put(message("user", "以下是较早对话的摘要，作为历史资料参考。\n" + contextSummary.text))
                messages.put(message("assistant", "已收到较早对话的摘要。"))
            }
            for (turn in old) {
                messages.put(message("user", turn.question))
                messages.put(message("assistant", turn.answer))
            }
            return messages.put(message("user", currentContent))
        }

        if (notice.isNotEmpty()) onText(ChatReply(status = notice))
        val output = limits.output(thinking)
        val result = try {
            complete(key, model, thinking, requestMessages(recent, summary), output, notice, usedSources, onText)
        } catch (error: ContextOverflow) {
            if (recent.isEmpty() && summary == null) throw error
            val retryNotice = "已缩短历史上下文，正在继续回答…"
            onText(ChatReply(status = retryNotice))
            complete(key, model, thinking, requestMessages(recent.takeLast(recent.size / 2), null), output,
                retryNotice, usedSources, onText)
        }
        return if (searching && usedSources.isEmpty() && result.outcome.isEmpty()) {
            result.copy(text = result.text + "\n\n本轮没有可纳入的检索资料。")
        } else result
    }

    private fun previousSource(turns: List<ChatTurn>): ChatSource? {
        val match = Regex("""(?:上一[条轮]|上一个|刚才)(?:的)?(?:回答|回复)?(?:的)?\s*来源\s*[（(]?\s*(\d+)""")
            .find(turns.last().question) ?: return null
        val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: return null
        return turns.getOrNull(turns.lastIndex - 1)?.sources?.getOrNull(index)
    }

    private fun message(role: String, content: Any) = JSONObject().put("role", role).put("content", content)

    private fun complete(
        key: ApiKey, model: String, thinking: Boolean, messages: JSONArray, maxOutput: Int,
        streamStatus: String, sources: List<ChatSource>, onText: (ChatReply) -> Unit
    ): ChatReply {
        val body = JSONObject().put("model", model).put("messages", messages)
            .put("stream", true).put("max_tokens", maxOutput)
        if (modelLimits(model).acceptsThinking) {
            body.put("thinking", JSONObject().put("type", if (thinking) "enabled" else "disabled"))
        }
        val streamOptions = when (key.provider) {
            Provider.GLM -> JSONObject().put("chunk_include_usage", true)
            else -> JSONObject().put("include_usage", true)
        }
        body.put("stream_options", streamOptions)
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
            if (status in listOf(400, 422)) {
                val error = http.errorStream?.bufferedReader()?.use { reader ->
                    val buffer = CharArray(4096)
                    val size = reader.read(buffer)
                    if (size > 0) String(buffer, 0, size) else ""
                }.orEmpty()
                val detail = runCatching { JSONObject(error).optJSONObject("error") }.getOrNull()
                val code = detail?.optString("code").orEmpty()
                val message = detail?.optString("message").orEmpty()
                if (code in setOf("context_length_exceeded", "max_context_length_exceeded") ||
                    Regex("maximum context length|context length exceeded|prompt is too long|上下文长度超出", RegexOption.IGNORE_CASE).containsMatchIn(message)) {
                    throw ContextOverflow()
                }
            }
            if (status !in 200..299) throw IOException(when (status) {
                401, 403 -> "密钥无效或没有访问权限"
                402 -> "API 账户余额不足"
                429 -> "请求过于频繁，请稍后重试"
                400, 422 -> "服务端拒绝了请求，请检查模型参数或附件"
                else -> "服务暂时不可用（HTTP " + status + "）"
            })
            val answer = StringBuilder()
            val reasoning = StringBuilder()
            var usage: TokenUsage? = null
            var lastUpdate = 0L
            var finished = false
            var outcome = ""
            try {
                http.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (!stopped) {
                        val line = reader.readLine() ?: break
                        if (!line.startsWith("data:")) continue
                        val event = line.substringAfter(':').trim()
                        if (event == "[DONE]") { finished = true; break }
                        val chunk = JSONObject(event)
                        if (chunk.has("error")) throw IOException("服务端中断了回复")
                        // 先取用量再处理正文：Kimi 的统计块 choices 为空，若先 continue 会丢掉数据。
                        chunk.optJSONObject("usage")?.let { u ->
                            val prompt = u.optInt("prompt_tokens")
                            val completion = u.optInt("completion_tokens")
                            val total = u.optInt("total_tokens").let { if (it > 0) it else prompt + completion }
                            usage = TokenUsage(
                                prompt = prompt, completion = completion,
                                reasoning = u.optJSONObject("completion_tokens_details")?.optInt("reasoning_tokens") ?: 0,
                                total = total
                            )
                        }
                        val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: continue
                        val delta = choice.optJSONObject("delta")
                        if (delta != null && !delta.isNull("content")) answer.append(delta.getString("content"))
                        if (delta != null && !delta.isNull("reasoning_content")) reasoning.append(delta.getString("reasoning_content"))
                        val now = android.os.SystemClock.elapsedRealtime()
                        if ((answer.isNotEmpty() || reasoning.isNotEmpty()) && now - lastUpdate >= 80) {
                            onText(ChatReply(answer.toString(),reasoning.toString(),streamStatus))
                            lastUpdate = now
                        }
                        when (choice.optString("finish_reason")) {
                            "stop" -> finished = true
                            "length" -> { finished = true; outcome = "已达到回复长度上限" }
                            "content_filter" -> throw FilteredReply()
                            "insufficient_system_resource" -> throw IOException("服务繁忙，请重试")
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is FilteredReply || (answer.isBlank() && reasoning.isBlank())) throw error
                if (!finished) outcome = "连接中断，已保留收到的内容"
            }
            if (answer.isBlank() && reasoning.isBlank()) throw IOException(if (stopped) "已停止生成" else "服务端未返回回答")
            if (stopped && !finished) outcome = "已停止生成"
            if (!finished && outcome.isEmpty()) outcome = "回复中断，已保留收到的内容"
            if (answer.isBlank() && outcome.isEmpty()) outcome = "仅收到思考内容"
            return ChatReply(answer.toString(), reasoning.toString(), streamStatus, sources, usage, outcome)
        } finally {
            http.disconnect()
            connection = null
        }
    }
}
