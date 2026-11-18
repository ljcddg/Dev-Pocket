package com.itcast.ds_balance_viewer

import com.itcast.ds_balance_viewer.ui.ReplyMarkdown
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.parser.Parser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyMarkdownTest {
    private val parser = Parser.builder().also(ReplyMarkdown::configure).build()

    private fun render(text: String, streaming: Boolean = false): String =
        HtmlRenderer.builder().build().render(ReplyMarkdown.repair(parser.parse(text), text, streaming))

    @Test fun keepsChineseListLabelsBoldAndRemovesBrokenMarkers() {
        val text = """
            1. **核心逻辑：**比较相邻的元素。
            2. **时间复杂度：平均为 O(n²)。
            3. **空间复杂度：**只使用 `temp`。
            4. **稳定性：**冒泡排序是稳定**的排序。
        """.trimIndent()
        val html = render(text)
        assertTrue(html.contains("<strong>核心逻辑：</strong>比较"))
        assertTrue(html.contains("<strong>时间复杂度：</strong>平均"))
        assertTrue(html.contains("<strong>空间复杂度：</strong>只使用 <code>temp</code>"))
        assertTrue(html, html.contains("冒泡排序是稳定的排序"))
        assertFalse(html.contains("**"))
    }

    @Test fun protectsStarOperatorsAndEscapedLiterals() {
        val html = render("""
            ```python
            print(2**3)
            ```

            行内代码 `args/**/main` 和文字 2**3。

            显式转义 \*\*字面符号\*\*。

            ***正常的粗斜体***
        """.trimIndent())
        assertTrue(html.contains("print(2**3)"))
        assertTrue(html.contains("<code>args/**/main</code>"))
        assertTrue(html.contains("文字 2**3"))
        assertTrue(html.contains("**字面符号**"))
        assertTrue(html.contains("<em><strong>正常的粗斜体</strong></em>"))
    }

    @Test fun hidesIncompleteMarkerFromPreviewAndRendersCompletedEmphasis() {
        assertFalse(render("正在回答 **", streaming = true).contains("**"))
        assertFalse(render("正在回答 *", streaming = true).contains("*"))
        assertTrue(render("正在回答 **关键内容**。").contains("<strong>关键内容</strong>。"))
    }
}
