package com.itcast.ds_balance_viewer

import androidx.test.platform.app.InstrumentationRegistry
import com.itcast.ds_balance_viewer.data.ApiKey
import com.itcast.ds_balance_viewer.data.ChatSource
import com.itcast.ds_balance_viewer.data.ChatTurn
import com.itcast.ds_balance_viewer.net.ChatMemory
import com.itcast.ds_balance_viewer.net.ChatRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ChatContextTest {
    private val key = ApiKey(alias = "测试", secret = "fixture")
    private val payloads = mutableListOf<ByteArrayOutputStream>()
    private val endpoints = mutableListOf<String>()

    private fun request(vararg responses: Pair<Int, String>): ChatRequest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return ChatRequest(context.contentResolver) { endpoint ->
            val (code, data) = responses[endpoints.size]
            endpoints.add(endpoint)
            val sent = ByteArrayOutputStream().also(payloads::add)
            object : HttpURLConnection(URL(endpoint)) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = code
                override fun getInputStream() = data.byteInputStream()
                override fun getErrorStream() = data.byteInputStream()
                override fun getOutputStream() = sent
            }
        }
    }

    private fun chunk(text: String, reason: String? = null, finish: String? = null): String =
        "data: " + JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
            .put("delta", JSONObject().put("content", text).put("reasoning_content", reason))
            .put("finish_reason", finish))) + "\n\n"

    @Test fun oldFilesDoNotUploadAfterModelSwitchAndEofKeepsPartial() {
        val turns = listOf(ChatTurn(1, "旧问题", "旧附件摘要", attachment = "过期附件"), ChatTurn(2, "新问题", ""))
        val result = request(200 to chunk("已收到")).stream(key, "deepseek-v4-pro", false, turns,
            { _, _ -> fail("不能重传旧附件") }) {}
        assertEquals(1, endpoints.size)
        assertTrue(payloads.single().toString("UTF-8").contains("旧附件摘要"))
        assertEquals("已收到", result.text)
        assertTrue(result.outcome.contains("中断"))
    }

    @Test fun savesThinkingOnlyAndRecognizesLengthWithoutDone() {
        val turns = listOf(ChatTurn(1, "说明一下", ""))
        val stream = request(200 to chunk("", "思考内容", "length"))
        val result = stream.stream(key, "deepseek-flash", true, turns, { _, _ -> }) {}
        assertEquals("思考内容", result.reasoning)
        assertEquals("已达到回复长度上限", result.outcome)
    }

    @Test fun sourceFollowUpUsesPreviousEvidenceWithoutNewSearch() {
        val source = ChatSource("已选报告", "https://example.org/report", "原检索证据")
        val turns = listOf(ChatTurn(1, "查资料", "已有回答", sources = listOf(source)),
            ChatTurn(2, "上一条的来源 1 说了什么？", "", search = true))
        val result = request(200 to chunk("引用来源", finish = "stop")).stream(
            key, "deepseek-flash", false, turns, { _, _ -> }
        ) {}
        assertEquals(1, endpoints.size)
        assertEquals(listOf(source), result.sources)
        assertTrue(payloads.single().toString("UTF-8").contains("原检索证据"))
    }

    @Test fun compactsBySizeAndReusesSummaryOnlyForMatchingVersions() {
        val old = (1L..6L).map { ChatTurn(it, "第${it}轮", "长".repeat(6_000)) }
        val latest = ChatTurn(7, "继续说明", "")
        var saved: ChatMemory? = null
        val stream = request(200 to chunk("早期摘要", finish = "stop"), 200 to chunk("新回答", finish = "stop"))
        val reply = stream.stream(key, "deepseek-flash", false, old + latest, { _, _ -> },
            saveMemory = { saved = it }) {}
        assertEquals("新回答", reply.text)
        assertEquals(2, endpoints.size)
        assertTrue(payloads.last().toString("UTF-8").contains("早期摘要"))
        assertFalse(payloads.last().toString("UTF-8").contains("第1轮"))
        assertNotNull(saved)
        payloads.clear()
        endpoints.clear()
        request(200 to chunk("再答", finish = "stop")).stream(key, "deepseek-flash", false, old + latest,
            { _, _ -> }, memory = saved) {}
        assertEquals(1, endpoints.size)
        assertTrue(payloads.last().toString("UTF-8").contains("早期摘要"))
        payloads.clear()
        endpoints.clear()
        val changed = listOf(old.first().copy(selected = 1)) + old.drop(1) + latest
        request(200 to chunk("更新摘要", finish = "stop"), 200 to chunk("再答", finish = "stop")).stream(
            key, "deepseek-flash", false, changed, { _, _ -> }, memory = saved
        ) {}
        assertEquals(2, endpoints.size)
        assertTrue(payloads.last().toString("UTF-8").contains("更新摘要"))
    }

    @Test fun onlyAcknowledgedOverflowRetriesWithoutAnotherSearch() {
        val data = """{"results":[{"title":"资料","url":"https://example.org","summary":"证据"}]}"""
        val error = """{"error":{"code":"context_length_exceeded"}}"""
        val turns = listOf(ChatTurn(1, "旧问题", "旧回答"), ChatTurn(2, "继续查", "", search = true))
        val reply = request(200 to data, 400 to error, 200 to chunk("重试成功", finish = "stop")).stream(
            key, "deepseek-flash", false, turns, { _, _ -> }
        ) {}
        assertEquals("重试成功", reply.text)
        assertEquals(1, endpoints.count { it.endsWith("/search") })
        assertTrue(payloads.last().toString("UTF-8").contains("证据"))
        assertFalse(payloads.last().toString("UTF-8").contains("旧回答"))
    }
}
