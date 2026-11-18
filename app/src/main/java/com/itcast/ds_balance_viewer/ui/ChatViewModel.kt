package com.itcast.ds_balance_viewer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.itcast.ds_balance_viewer.data.ChatHistory
import com.itcast.ds_balance_viewer.data.ChatPreferences
import com.itcast.ds_balance_viewer.data.ChatTurn
import com.itcast.ds_balance_viewer.data.SecureStore
import com.itcast.ds_balance_viewer.net.ChatRequest
import com.itcast.ds_balance_viewer.util.AppExecutors
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID

data class Conversation(
    val turns: List<ChatTurn>, val busy: Boolean = false,
    val target: Long = -1, val partial: String = "", val notice: String = "", val reasoning: String = ""
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val history = ChatHistory(app)
    private var session = SecureStore.getString("chat_session") ?: UUID.randomUUID().toString()
    private var active: ChatRequest? = null
    val state = MutableLiveData(Conversation(history.turns(session)))

    fun sessions(query: String) = history.sessions(query)
    fun pin(item: com.itcast.ds_balance_viewer.data.ChatSession) = history.pin(item)
    fun choose(turn: ChatTurn, index: Int) { if (active == null) { history.select(turn,index); reload() } }
    fun vote(turn: ChatTurn, value: Int) { if (active == null) { history.vote(turn, if (turn.vote == value) 0 else value); reload() } }
    private fun reload() { state.value = Conversation(history.turns(session)) }

    fun open(id: String = UUID.randomUUID().toString()) {
        if (active != null) return
        session = id
        SecureStore.putString("chat_session", id)
        state.value = Conversation(history.turns(id))
    }

    fun delete(id: String) {
        if (active != null) return
        history.delete(id)
        if (id == session) open()
    }

    fun send(question: String?, target: Long? = null, attachment: String = "", search: Boolean = false): Boolean {
        val key = ChatPreferences.key ?: return false
        if (active != null) return false
        val previous = state.value!!.turns
        val turns = if (question != null) previous + history.ask(session, question, attachment, search) else previous
        if (turns.isEmpty()) return false
        SecureStore.putString("chat_session", session)
        val model = ChatPreferences.model(key.provider)
        val thinking = ChatPreferences.thinking
        val index = if (target == null) turns.lastIndex else turns.indexOfFirst { it.id == target }
        if (index < 0) return false
        val context = turns.take(index) + turns[index].copy(search = search)
        val targetId = context.last().id
        val request = ChatRequest(getApplication<Application>().contentResolver)
        active = request
        state.value = Conversation(turns, busy = true, target = targetId)
        AppExecutors.io.execute {
            val result = runCatching {
                request.stream(key, model, thinking, context, history::uploaded) { partial ->
                    AppExecutors.main {
                        if (active === request) state.value = Conversation(turns, true, targetId, partial.text,partial.status,partial.reasoning)
                    }
                }
            }
            AppExecutors.main {
                if (active !== request) return@main
                active = null
                result.onSuccess { answer ->
                    history.answer(targetId, answer.text,answer.reasoning)
                    reload()
                }.onFailure {
                    val message = when (it) {
                        is SocketTimeoutException -> "连接超时，请重试"
                        is java.net.UnknownHostException -> "无法连接服务，请检查网络"
                        is IOException -> it.localizedMessage ?: "网络连接中断"
                        else -> "无法读取服务端回复"
                    }
                    state.value = Conversation(history.turns(session), notice = message)
                }
            }
        }
        return true
    }

    fun stop() {
        val request = active ?: return
        active = null
        AppExecutors.io.execute { request.cancel() }
        state.value = Conversation(history.turns(session), notice = "已停止生成")
    }

    override fun onCleared() {
        stop()
        history.close()
    }
}
