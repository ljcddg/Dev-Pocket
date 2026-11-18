package com.itcast.ds_balance_viewer

import android.os.SystemClock
import android.view.View
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.withId
import com.itcast.ds_balance_viewer.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatKeyboardTest {
    @Test fun multilineDraftAndSendStayAboveKeyboard() {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            val draft = "第一行输入\n第二行继续输入"
            onView(withId(R.id.input)).perform(click(), replaceText(draft))
            activity.onActivity {
                ViewCompat.getWindowInsetsController(it.findViewById(R.id.input))
                    ?.show(WindowInsetsCompat.Type.ime())
            }

            var keyboardShown = false
            var visible = false
            val deadline = SystemClock.uptimeMillis() + 5000
            while (!visible && SystemClock.uptimeMillis() < deadline) {
                activity.onActivity {
                    val input = it.findViewById<EditText>(R.id.input)
                    val send = it.findViewById<View>(R.id.send)
                    val insets = ViewCompat.getRootWindowInsets(input)!!
                    keyboardShown = insets.isVisible(WindowInsetsCompat.Type.ime())
                    val imeTop = input.rootView.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    val xy = IntArray(2)
                    send.getLocationInWindow(xy)
                    val buttonVisible = xy[1] + send.height <= imeTop
                    input.getLocationInWindow(xy)
                    visible = keyboardShown && input.hasFocus() && input.lineCount >= 2 &&
                        xy[1] >= 0 && xy[1] + input.height <= imeTop && buttonVisible
                }
                if (!visible) SystemClock.sleep(50)
            }
            assertTrue("测试需要打开系统软键盘", keyboardShown)
            assertTrue("键盘打开时，多行输入和发送按钮应完整可见", visible)
            onView(withId(R.id.input)).perform(closeSoftKeyboard())
            activity.onActivity { assertEquals(draft, it.findViewById<EditText>(R.id.input).text.toString()) }
        }
    }
}
