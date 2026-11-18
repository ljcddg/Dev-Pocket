package com.itcast.ds_balance_viewer

import android.database.sqlite.SQLiteDatabase
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
                db.answer(1,"**新版**")
                val turn = db.turns("s").single()
                assertEquals(1,turn.selected)
                db.pin(db.sessions("新版").single())
                db.select(turn,0)
                assertTrue(db.sessions("旧版").single().pinned)
                assertEquals("旧版本", db.turns("s").single().answer)
            }
        } finally { ctx.deleteDatabase(name) }
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
                override fun getInputStream() = (if (endpoint.endsWith("/files")) """{"id":"file-api-test","expires_at":2100000000}""" else
                    "data: {\"choices\":[{\"delta\":{\"content\":\"**答复**\"}}]}\n\ndata: [DONE]\n").byteInputStream()
            }
        }
        try {
            val attachment = JSONObject().put("uri",android.net.Uri.fromFile(file)).put("name","test.txt").toString()
            val turn = ChatTurn(3,"总结","",attachment=attachment,search=true)
            val result = request.stream(ApiKey(alias="测试",secret="fixture"),"deepseek-flash",false,listOf(turn),{ _, data ->
                assertEquals("file-api-test",JSONObject(data).getString("remote"))
            }) {}
            assertEquals("**答复**",result)
            assertEquals(listOf("https://api.deepseek.com/files","https://api.deepseek.com/chat/completions"),endpoints)
            assertTrue(sent.first().toString("UTF-8").contains("测试附件正文"))
            val json = JSONObject(sent.last().toString("UTF-8"))
            assertEquals("deepseek_web_search",json.getJSONArray("tools").getJSONObject(0).getString("type"))
            assertEquals("file-api-test",json.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("file_id"))
        } finally { file.delete() }
    }
}
