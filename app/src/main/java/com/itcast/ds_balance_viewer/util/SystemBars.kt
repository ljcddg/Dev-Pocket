package com.itcast.ds_balance_viewer.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 刘海屏 / 挖孔屏 / 手势导航条适配。
 *
 * targetSdk 35+ 强制 edge-to-edge：窗口内容会绘制到状态栏和导航条底下，
 * 不做处理的话标题会顶进刘海、按钮会压到手势条。
 *
 * 处理策略：
 * - 工具栏向下延伸 status bar / 刘海的高度（背景色连贯，文字仍在安全区）
 * - 根布局左右避让侧边刘海（横屏），底部避让导航条与软键盘
 */
object SystemBars {

    fun apply(toolbar: View, root: View) {
        val baseHeight = toolbar.layoutParams.height

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val cutout = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            // 工具栏垫高：原高度 + 状态栏高度，文字通过 padding 推到安全区
            if (toolbar.paddingTop != cutout.top) {
                toolbar.setPadding(
                    toolbar.paddingLeft, cutout.top, toolbar.paddingRight, toolbar.paddingBottom
                )
                toolbar.layoutParams = toolbar.layoutParams
                    .also { it.height = baseHeight + cutout.top }
            }

            // 根布局：左右避让侧刘海，底部取导航条与键盘的较大者
            val bottom = maxOf(nav.bottom, ime.bottom)
            if (root.paddingBottom != bottom || root.paddingLeft != cutout.left) {
                root.setPadding(cutout.left, root.paddingTop, cutout.right, bottom)
            }

            WindowInsetsCompat.CONSUMED
        }
    }
}
