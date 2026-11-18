package com.itcast.ds_balance_viewer

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.*
import com.itcast.ds_balance_viewer.ui.MainActivity
import androidx.test.platform.app.InstrumentationRegistry
import com.itcast.ds_balance_viewer.data.*
import com.itcast.ds_balance_viewer.net.ChatRequest
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatUpgradeTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun oldAnswersSurviveRegenerationAndSearch() {
        val name = "upgrade-test-" + System.nanoTime()
        try {
            ctx.openOrCreateDatabase(name,0,null).use { db ->
                db.execSQL("CREATE TABLE turns(id INTEGER PRIMARY KEY, session TEXT, question TEXT, answer TEXT)")
                db.execSQL("INSERT INTO turns VALUES(1,'s','标题','旧版本')")
                db.version = 1
            }
            ChatHistory(ctx,name).use { db ->
                assertEquals(listOf("旧版本"),db.turns("s").single().versions)
                db.answer(1,"**新版**","新版本推理")
                val turn = db.turns("s").single()
                assertEquals(1,turn.selected)
                assertEquals("新版本推理",turn.reasoning)
                db.pin(db.sessions("新版").single())
                db.select(turn,0)
                assertTrue(db.sessions("旧版").single().pinned)
                assertEquals("旧版本", db.turns("s").single().answer)
                assertEquals("", db.turns("s").single().reasoning)
            }
        } finally { ctx.deleteDatabase(name) }
    }

    @Test fun drawerPushesPageAndFiltersAnswerText() {
        val history = ChatHistory(ctx)
        val session = java.util.UUID.randomUUID().toString()
        try {
            val row = history.ask(session,"侧栏回归测试")
            history.answer(row.id,"needle-" + session)
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                onView(withContentDescription("历史对话")).perform(click())
                onView(withId(R.id.historySearch)).check(matches(isDisplayed()))
                activity.onActivity {
                    val page = it.findViewById<android.view.View>(R.id.chatPage)
                    val panel = it.findViewById<android.view.View>(R.id.historyPanel)
                    assertEquals(panel.width.toFloat(), page.translationX, 1f)
                }
                onView(withId(R.id.historySearch)).perform(replaceText("needle-" + session))
                onView(withText("侧栏回归测试")).check(matches(isDisplayed()))
                onView(withId(R.id.historySearch)).perform(replaceText("empty-" + session))
                onView(withText("侧栏回归测试")).check(doesNotExist())
                onView(withText("没有匹配的对话")).check(matches(isDisplayed()))
            }
        } finally { history.delete(session); history.close() }
    }

    @Test fun uploadThenSendSelectedFileAndSearch() {
        val file = java.io.File.createTempFile("fixture",".txt",ctx.cacheDir)
        file.writeText("测试附件正文")
        val sent = mutableListOf<ByteArrayOutputStream>()
        val endpoints = mutableListOf<String>()
        val request = ChatRequest(ctx.contentResolver) { endpoint ->
            endpoints.add(endpoint)
            val body = ByteArrayOutputStream().also(sent::add)
            object : HttpURLConnection(URL(endpoint)) {
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun connect() {}
                override fun getOutputStream() = body
                override fun getResponseCode() = 200
                override fun getInputStream() = (if (endpoint.endsWith("/files")) """{"id":"file-api-test","expires_at":2100000000}""" else if (endpoint.endsWith("/search")) """{"results":[{"title":"测试来源","url":"https://example.org/report","summary":"最新公告摘要"}]}""" else
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"分析资料\",\"content\":\"**答复**\"}}]}\n\ndata: [DONE]\n").byteInputStream()
            }
        }
        try {
            val attachment = JSONObject().put("uri",android.net.Uri.fromFile(file)).put("name","test.txt").toString()
            val turn = ChatTurn(3,"总结","",attachment=attachment,search=true)
            val result = request.stream(ApiKey(alias="测试",secret="fixture"),"deepseek-flash",false,listOf(turn),{ _, data ->
                assertEquals("file-api-test",JSONObject(data).getString("remote"))
            }) {}
            assertTrue(result.text.contains("**答复**"))
            assertTrue(result.text.contains("https://example.org/report"))
            assertEquals("分析资料",result.reasoning)
            assertEquals(listOf("https://api.deepseek.com/v1/tools/search","https://api.deepseek.com/files","https://api.deepseek.com/chat/completions"),endpoints)
            assertTrue(sent[1].toString("UTF-8").contains("测试附件正文"))
            val json = JSONObject(sent.last().toString("UTF-8"))
            assertTrue(json.toString().contains("最新公告摘要"))
            assertEquals("file-api-test",json.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(1).getString("file_id"))
        } finally { file.delete() }
    }
}
