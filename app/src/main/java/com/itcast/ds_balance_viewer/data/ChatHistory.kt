package com.itcast.ds_balance_viewer.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.itcast.ds_balance_viewer.net.ChatMemory

/** 单次回答的真实 token 用量，来自 API 流式响应的 usage 字段；旧数据全为 0。 */
data class TokenUsage(
    val prompt: Int = 0, val completion: Int = 0, val reasoning: Int = 0, val total: Int = 0
) {
    val hasData get() = prompt > 0 || completion > 0
}

data class ChatTurn(
    val id: Long, val question: String, val answer: String, val versions: List<String> = emptyList(),
    val selected: Int = 0, val vote: Int = 0, val attachment: String = "", val search: Boolean = false,
    val reasoning: String = "", val sources: List<ChatSource> = emptyList(),
    val usage: TokenUsage = TokenUsage(), val outcome: String = ""
)
data class ChatSession(val id: String, val title: String, val updated: Long, val pinned: Boolean)

/** 每个问题独立保存，网络中断后可以重新回答最后一题。 */
class ChatHistory(context: Context, name: String = "conversations.db") : SQLiteOpenHelper(context, name, null, 6) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE turns (id INTEGER PRIMARY KEY, session TEXT NOT NULL, question TEXT NOT NULL, answer TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE INDEX session_turns ON turns(session, id)")
        onUpgrade(db, 1, 6)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE turns ADD COLUMN created INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE turns ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE turns ADD COLUMN attachment TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE turns ADD COLUMN search INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE turns ADD COLUMN selected INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE TABLE replies(turn INTEGER NOT NULL, version INTEGER NOT NULL, body TEXT NOT NULL, vote INTEGER DEFAULT 0, PRIMARY KEY(turn,version))")
            db.execSQL("INSERT INTO replies(turn,version,body) SELECT id,0,answer FROM turns WHERE answer <> ''")
        }
        if (oldVersion < 3) db.execSQL("ALTER TABLE replies ADD COLUMN reasoning TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 4) db.execSQL("ALTER TABLE replies ADD COLUMN sources TEXT NOT NULL DEFAULT '[]'")
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE replies ADD COLUMN outcome TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE TABLE chat_memory(session TEXT PRIMARY KEY, through_id INTEGER NOT NULL, fingerprint TEXT NOT NULL, body TEXT NOT NULL)")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE replies ADD COLUMN prompt_tokens INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE replies ADD COLUMN completion_tokens INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE replies ADD COLUMN reasoning_tokens INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE replies ADD COLUMN total_tokens INTEGER NOT NULL DEFAULT 0")
        }
    }

    fun sessions(query: String = ""): List<ChatSession> = readableDatabase.rawQuery(
        """SELECT t.session, substr(t.question, 1, 42), s.updated, t.pinned FROM turns t
           JOIN (SELECT min(id) first_id, max(id) last_id, max(created) updated FROM turns GROUP BY session) s
           ON t.id = s.first_id WHERE EXISTS (SELECT 1 FROM turns x WHERE x.session=t.session AND (instr(lower(x.question),lower(?)) > 0 OR instr(lower(x.answer),lower(?)) > 0)) ORDER BY t.pinned DESC, s.last_id DESC""", arrayOf(query, query)
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(ChatSession(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getInt(3) != 0)) }
    }

    fun turns(session: String): List<ChatTurn> = readableDatabase.rawQuery(
        "SELECT id, question, answer, selected, attachment, search FROM turns WHERE session = ? ORDER BY id", arrayOf(session)
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val versions = mutableListOf<String>()
                var vote = 0
                var reasoning = ""
                var sources = emptyList<ChatSource>()
                var usage = TokenUsage()
                var outcome = ""
                readableDatabase.rawQuery("SELECT body,vote,reasoning,sources,prompt_tokens,completion_tokens,reasoning_tokens,total_tokens,outcome FROM replies WHERE turn=? ORDER BY version", arrayOf(id.toString())).use { rows ->
                    while (rows.moveToNext()) {
                        if (versions.size == cursor.getInt(3)) {
                            vote = rows.getInt(1)
                            reasoning = rows.getString(2)
                            sources = ChatSource.decode(rows.getString(3))
                            usage = TokenUsage(rows.getInt(4), rows.getInt(5), rows.getInt(6), rows.getInt(7))
                            outcome = rows.getString(8)
                        }
                        versions.add(rows.getString(0))
                    }
                }
                val storedAnswer = cursor.getString(2)
                val (answer, displayedSources) = if (sources.isEmpty()) {
                    ChatSource.splitLegacyAnswer(storedAnswer)
                } else storedAnswer to sources
                add(ChatTurn(id, cursor.getString(1), answer, versions, cursor.getInt(3), vote,
                    cursor.getString(4), cursor.getInt(5) != 0, reasoning, displayedSources, usage, outcome))
            }
        }
    }

    fun ask(session: String, question: String, attachment: String = "", search: Boolean = false): ChatTurn {
        val id = writableDatabase.insertOrThrow("turns", null, ContentValues().apply {
            put("session", session)
            put("question", question)
            put("created", System.currentTimeMillis())
            put("attachment", attachment)
            put("search", search)
        })
        return ChatTurn(id, question, "", attachment = attachment, search = search)
    }

    fun answer(id: Long, text: String, reasoning: String = "", sources: List<ChatSource> = emptyList(), usage: TokenUsage = TokenUsage(), outcome: String = "") {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT INTO replies(turn,version,body,reasoning,sources,prompt_tokens,completion_tokens,reasoning_tokens,total_tokens,outcome) SELECT ?,count(*),?,?,?,?,?,?,?,? FROM replies WHERE turn=?",
                arrayOf<Any>(id, text, reasoning, ChatSource.encode(sources),
                    usage.prompt, usage.completion, usage.reasoning, usage.total, outcome, id)
            )
            db.execSQL("UPDATE turns SET answer=?, selected=(SELECT max(version) FROM replies WHERE turn=?) WHERE id=?", arrayOf(text,id,id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun memory(session: String): ChatMemory? = readableDatabase.rawQuery(
        "SELECT through_id,fingerprint,body FROM chat_memory WHERE session=?", arrayOf(session)
    ).use { if (it.moveToFirst()) ChatMemory(it.getLong(0), it.getString(1), it.getString(2)) else null }

    fun saveMemory(session: String, memory: ChatMemory) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO chat_memory VALUES(?,?,?,?)",
            arrayOf<Any>(session, memory.through, memory.fingerprint, memory.text))
    }

    fun select(turn: ChatTurn, index: Int) {
        writableDatabase.execSQL("UPDATE turns SET selected=?, answer=? WHERE id=?", arrayOf(index,turn.versions[index],turn.id))
    }

    fun vote(turn: ChatTurn, value: Int) {
        writableDatabase.execSQL("UPDATE replies SET vote=? WHERE turn=? AND version=?", arrayOf(value,turn.id,turn.selected))
    }

    fun pin(item: ChatSession) {
        writableDatabase.execSQL("UPDATE turns SET pinned=? WHERE session=?", arrayOf(if (item.pinned) 0 else 1,item.id))
    }

    fun uploaded(id: Long, attachment: String) {
        writableDatabase.execSQL("UPDATE turns SET attachment=? WHERE id=?", arrayOf(attachment,id))
    }

    fun delete(session: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM replies WHERE turn IN (SELECT id FROM turns WHERE session=?)", arrayOf(session))
            db.delete("turns", "session = ?", arrayOf(session))
            db.delete("chat_memory", "session = ?", arrayOf(session))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
