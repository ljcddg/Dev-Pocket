package com.itcast.ds_balance_viewer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.itcast.ds_balance_viewer.R
import io.noties.markwon.syntax.Prism4jSyntaxHighlight

internal class ChatCodeView(context: Context) : MaterialCardView(context) {
    private val label = TextView(context)
    private val code = TextView(context)
    private var source = ""
    private var language = ""
    private var highlighted = false

    init {
        radius = dp(14).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = context.getColor(R.color.ds_divider)
        setCardBackgroundColor(context.getColor(R.color.ds_card))
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
            setBackgroundColor(context.getColor(R.color.ds_divider))
        }
        label.typeface = Typeface.MONOSPACE
        label.textSize = 13f
        header.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "复制"
            contentDescription = "复制代码"
            setIconResource(R.drawable.chat_copy)
            iconSize = dp(16)
            setOnClickListener {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("代码", source))
                Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(-2, dp(48)))
        panel.addView(header, -1, -2)
        panel.addView(View(context).apply { setBackgroundColor(context.getColor(R.color.ds_divider)) }, -1, dp(1))
        code.apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(dp(12), dp(10), dp(14), dp(12))
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
            setHorizontallyScrolling(true)
        }
        panel.addView(HorizontalScrollView(context).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = true
            isNestedScrollingEnabled = true
            addView(code, -2, -2)
        }, -1, -2)
        addView(panel, -1, -2)
    }

    fun bind(info: String?, text: String, highlighter: Prism4jSyntaxHighlight, streaming: Boolean) {
        val syntax = info.orEmpty().trim().substringBefore(' ').lowercase().take(40)
        // 长代码和流式草稿先以等宽文本显示，控制主线程上的语法分析开销。
        val tokenize = !streaming && text.length <= 24_000
        if (source == text && language == syntax && highlighted == tokenize && label.text.isNotEmpty()) return
        source = text
        language = syntax
        highlighted = tokenize
        label.text = syntax.ifBlank { "代码" }
        val display = text.removeSuffix("\n")
        code.text = if (tokenize) highlighter.highlight(syntax, display) else display
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}
