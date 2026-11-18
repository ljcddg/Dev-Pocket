package com.itcast.ds_balance_viewer.data

import org.json.JSONArray
import org.json.JSONObject

data class ChatSource(val title: String, val url: String, val evidence: String = "", val date: String = "") {
    companion object {
        private const val legacyHeading = "\n\n**本次检索来源**\n\n"
        private val legacyLink = Regex("""- \[\d+ · (.+)]\((https?://.+)\)""")

        internal fun encode(sources: List<ChatSource>): String =
            JSONArray().apply {
                sources.forEach {
                    put(JSONObject().put("title", it.title).put("url", it.url)
                        .put("evidence", it.evidence).put("date", it.date))
                }
            }.toString()

        internal fun decode(value: String): List<ChatSource> {
            val array = runCatching { JSONArray(value) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val row = array.optJSONObject(index) ?: return@mapNotNull null
                ChatSource(row.optString("title"), row.optString("url"), row.optString("evidence"), row.optString("date"))
            }
        }

        /** 旧版本把检索来源追加到了回答末尾，读取历史时还原为独立条目。 */
        internal fun splitLegacyAnswer(answer: String): Pair<String, List<ChatSource>> {
            val split = answer.lastIndexOf(legacyHeading)
            if (split < 0) return answer to emptyList()
            val lines = answer.substring(split + legacyHeading.length).trimEnd().lines()
            val sources = lines.map { line ->
                val parts = legacyLink.matchEntire(line)?.groupValues ?: return answer to emptyList()
                ChatSource(parts[1], parts[2])
            }
            return answer.substring(0, split) to sources
        }
    }
}
