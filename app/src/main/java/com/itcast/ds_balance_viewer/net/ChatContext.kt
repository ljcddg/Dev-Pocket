package com.itcast.ds_balance_viewer.net

import com.itcast.ds_balance_viewer.data.ChatTurn
import java.security.MessageDigest

data class ChatMemory(val through: Long, val fingerprint: String, val text: String)

internal data class ChatModelLimits(
    val window: Int,
    val acceptsFiles: Boolean = false,
    val acceptsThinking: Boolean = true
) {
    fun output(thinking: Boolean) = if (thinking && acceptsThinking) 16_384 else 8_192
}

internal fun modelLimits(model: String) = when (model) {
    "deepseek-flash" -> ChatModelLimits(1_000_000, acceptsFiles = true)
    "deepseek-v4-pro" -> ChatModelLimits(1_000_000)
    "kimi-k2.5", "kimi-k2.6" -> ChatModelLimits(256_000)
    "glm-4.7", "glm-4.7-flash" -> ChatModelLimits(200_000)
    else -> ChatModelLimits(32_000, acceptsThinking = false)
}

internal object ChatContext {
    // UTF-8 字节数作为保守估算，没有借用其他模型的分词器。
    fun estimate(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    fun cost(turn: ChatTurn): Long = estimate(turn.question).toLong() + estimate(turn.answer) + 24

    fun inputLimit(model: String, thinking: Boolean, hasFile: Boolean): Int {
        val limits = modelLimits(model)
        // 服务端解析文件后才知道实际开销，预留空间并允许服务端报告精确超限。
        return limits.window - limits.output(thinking) - 2048 - if (hasFile) 16_384 else 0
    }

    fun recent(turns: List<ChatTurn>, budget: Int): List<ChatTurn> {
        var remaining = budget.toLong()
        return turns.asReversed().takeWhile { turn ->
            remaining -= cost(turn)
            remaining >= 0
        }.asReversed()
    }

    fun fingerprint(turns: List<ChatTurn>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (turn in turns) {
            digest.update("${turn.id}/${turn.selected}/${turn.question.length}/${turn.answer.length}:".toByteArray())
            digest.update(turn.question.toByteArray())
            digest.update(turn.answer.toByteArray())
        }
        return digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }

    fun covered(turns: List<ChatTurn>, memory: ChatMemory?): Int {
        if (memory == null) return 0
        val index = turns.indexOfFirst { it.id == memory.through } + 1
        return if (index > 0 && fingerprint(turns.take(index)) == memory.fingerprint) index else 0
    }
}
