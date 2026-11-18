package com.itcast.ds_balance_viewer.ui

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Node
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/** 在展示层修复解析器留下的正文定界符，原始回答继续供复制和导出使用。 */
internal object ReplyMarkdown {
    private val paired = Regex("""(?<!\*)\*\*(?!\*)([^*\r\n]{1,240})\*\*(?!\*)""")
    private val leadingLabel = Regex("""^\h*\*\*(?!\*)([^*\r\n：:]{1,32}[：:])""")
    private val stray = Regex("""(?<!\*)\*{2,3}(?!\*)""")

    fun configure(builder: Parser.Builder) {
        builder.includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
    }

    fun repair(document: Node, input: String, streaming: Boolean = false): Node {
        if ("**" !in input && !streaming) return document
        val lines = input.lines()
        val textNodes = mutableListOf<Text>()
        document.accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                if ("**" in text.literal || (streaming && text.literal.endsWith('*'))) textNodes.add(text)
            }
        })
        for (text in textNodes) {
            val spans = text.sourceSpans
            // 转义和字符实体在 AST 中已解码。保留这类字面内容。
            if (spans.isEmpty() || spans.joinToString("") { span ->
                    val line = lines.getOrNull(span.lineIndex).orEmpty()
                    val end = span.columnIndex + span.length
                    if (end <= line.length) line.substring(span.columnIndex, end) else ""
                } != text.literal) continue
            val tail = spans.last()
            val liveTail = streaming && tail.lineIndex == lines.lastIndex &&
                tail.columnIndex + tail.length == lines.last().length
            repairText(text, text.literal, liveTail)
        }
        return document
    }

    private fun repairText(node: Text, literal: String, liveTail: Boolean) {
        if ("**" !in literal && !liveTail) return
        var position = 0

        fun addPlain(end: Int) {
            val value = literal.substring(position, end)
            val clean = removeOrphans(value, liveTail && end == literal.length)
            if (clean.isNotEmpty()) node.insertBefore(Text(clean))
        }

        fun addStrong(match: MatchResult) {
            addPlain(match.range.first)
            val content = match.groupValues[1].trim()
            node.insertBefore(StrongEmphasis("**").apply { appendChild(Text(content)) })
            position = match.range.last + 1
        }

        for (match in paired.findAll(literal)) {
            if (match.groupValues[1].any(::isCjk)) addStrong(match)
        }
        if (position == 0) {
            leadingLabel.find(literal)?.takeIf { it.groupValues[1].any(::isCjk) }?.let { label ->
                if (label.range.last == literal.lastIndex) {
                    // 中文冒号后的闭合标记可能被解析成下一段的开头。
                    val following = node.next as? StrongEmphasis
                    val start = following?.sourceSpans?.firstOrNull()
                    val end = node.sourceSpans.last()
                    if (following?.openingDelimiter == "**" && start?.lineIndex == end.lineIndex &&
                        start.columnIndex == end.columnIndex + end.length) {
                        // 保留后续节点及其行内样式，只移除误配的粗体外层。
                        while (following.lastChild != null) following.insertAfter(following.lastChild)
                        following.unlink()
                    }
                }
                addStrong(label)
            }
        }
        addPlain(literal.length)
        // 每段都先插入替换节点，因此移除原节点，避免正文重复。
        node.unlink()
    }

    private fun removeOrphans(text: String, liveTail: Boolean): String {
        val cleaned = stray.replace(text) { match ->
            val left = text.getOrNull(match.range.first - 1)
            val right = text.getOrNull(match.range.last + 1)
            when {
                left?.let(::isCjk) == true || right?.let(::isCjk) == true -> ""
                liveTail && right == null -> ""
                match.range.first == 0 && right?.isLetter() == true && text.any(Char::isWhitespace) -> ""
                else -> match.value
            }
        }
        return if (liveTail && cleaned.endsWith('*') && !cleaned.endsWith("****")) cleaned.trimEnd('*') else cleaned
    }

    private fun isCjk(char: Char): Boolean =
        char in '\u2e80'..'\u9fff' || char in '\uf900'..'\ufaff' || char in '\uff00'..'\uffef'
}
