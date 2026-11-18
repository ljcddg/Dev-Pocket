package com.itcast.ds_balance_viewer

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withClassName
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itcast.ds_balance_viewer.data.ApiKey
import com.itcast.ds_balance_viewer.data.ChatPreferences
import com.itcast.ds_balance_viewer.data.KeyRepository
import com.itcast.ds_balance_viewer.data.Provider
import com.itcast.ds_balance_viewer.ui.MainActivity
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.endsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatKeySelectionTest {
    @Test fun selectSecondKeyAndHandleDeletion() {
        val first = ApiKey(alias = "对话测试甲", secret = "fixture-for-chat-first")
        val second = ApiKey(alias = "对话测试乙", secret = "fixture-for-chat-second")
        val previous = ChatPreferences.keyId
        val originalModel = ChatPreferences.model(Provider.DEEPSEEK)
        try {
            KeyRepository.add(first)
            KeyRepository.add(second)
            ChatPreferences.keyId = first.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(withContentDescription("对话设置")).perform(click())
                onView(allOf(withClassName(endsWith("Spinner")),
                    hasDescendant(withText(containsString(first.alias))))).perform(click())
                onView(withText(containsString(second.alias))).perform(click())
                onView(withText("保存")).perform(click())
                scenario.recreate()
                assertEquals(second.id, ChatPreferences.key?.id)
                KeyRepository.remove(second.id)
                assertNull(ChatPreferences.key)
            }
        } finally {
            KeyRepository.remove(first.id)
            KeyRepository.remove(second.id)
            ChatPreferences.keyId = previous
            ChatPreferences.setModel(Provider.DEEPSEEK, originalModel)
        }
    }
}
