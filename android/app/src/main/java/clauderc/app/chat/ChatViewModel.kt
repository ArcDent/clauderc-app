package clauderc.app.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import clauderc.app.data.Fleet
import clauderc.app.net.ConnectionState
import clauderc.app.net.Relay
import clauderc.app.net.RemoteException
import clauderc.app.net.call
import clauderc.app.protocol.Answer
import clauderc.app.protocol.ChatAnswerRequest
import clauderc.app.protocol.ChatCommandsResponse
import clauderc.app.protocol.ChatItem
import clauderc.app.protocol.ChatItemsEvent
import clauderc.app.protocol.ChatLimits
import clauderc.app.protocol.ChatModelsResponse
import clauderc.app.protocol.ChatOpenRequest
import clauderc.app.protocol.ChatOpenResponse
import clauderc.app.protocol.ChatPendingEvent
import clauderc.app.protocol.ChatSendRequest
import clauderc.app.protocol.ChatSendResponse
import clauderc.app.protocol.ChatSetModelRequest
import clauderc.app.protocol.ChatSetPermissionModeRequest
import clauderc.app.protocol.ChatStateEvent
import clauderc.app.protocol.Command
import clauderc.app.protocol.Decision
import clauderc.app.protocol.Empty
import clauderc.app.protocol.ErrorCode
import clauderc.app.protocol.Image
import clauderc.app.protocol.ItemKind
import clauderc.app.protocol.Method
import clauderc.app.protocol.ModelOption
import clauderc.app.protocol.Pending
import clauderc.app.protocol.ProtocolJson
import clauderc.app.protocol.Session
import clauderc.app.protocol.SessionIdRequest
import clauderc.app.protocol.SessionState
import java.util.Base64
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A picture waiting in the composer, already resized and re-encoded as JPEG. */
class Attachment(val name: String, val jpeg: ByteArray)

/**
 * A message this phone sent that the transcript has not shown yet; drawn faded under the
 * last item. Only items after [afterSeq] can be its delivery. [queued] is set once the agent
 * says it waits in Claude Code's queue, as a slash command does: the transcript only shows
 * it when it runs.
 */
data class Outgoing(val id: Long, val text: String, val attachments: List<Attachment>, val afterSeq: Long, val queued: Boolean = false)

/** Text put back into the composer after a failed send; [id] tells two restores of the same text apart. */
data class Restore(val id: Long, val text: String)

/** Drops the outgoing messages that [delivered] user items or slash command rows show, oldest first. */
fun settleOutgoing(outgoing: List<Outgoing>, delivered: List<ChatItem>): List<Outgoing> {
    if (outgoing.isEmpty()) return outgoing
    val left = outgoing.toMutableList()
    for (item in delivered) {
        // Pictures show up as "[Image #1] " in front of the text, so compare the end.
        val text = when {
            item.kind == ItemKind.USER -> item.text.orEmpty().trim()
            item.tool != null && isCommand(item.tool) -> item.tool.summary.trim()
            else -> continue
        }
        val hit = left.firstOrNull { o ->
            item.seq > o.afterSeq && if (o.text.isEmpty()) !item.images.isNullOrEmpty() else text.endsWith(o.text)
        }
        if (hit != null) left.remove(hit)
    }
    return left
}

/** What a chat page showed last time, so reopening it draws at once while chat.open catches up. Memory only. */
class ChatCache(private val capacity: Int = 8) {
    data class Entry(val items: List<ChatItem>, val hasMore: Boolean, val state: SessionState, val model: String, val permissionMode: String)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > capacity
    }

    @Synchronized fun get(key: String): Entry? = entries[key]

    @Synchronized fun put(key: String, entry: Entry) {
        entries[key] = entry
    }

    companion object {
        /** Items kept per session; older ones are fetched again when scrolled to. */
        const val KEEP = 100
    }
}

data class ChatUiState(
    val session: Session? = null,
    /** False when the machine is offline or the relay is unreachable; the page turns read-only. */
    val online: Boolean = true,
    val offlineText: String = "",
    val loading: Boolean = true,
    val items: List<ChatItem> = emptyList(),
    val hasMore: Boolean = false,
    val loadingOlder: Boolean = false,
    val state: SessionState = SessionState.Idle,
    val model: String = "",
    val permissionMode: String = "",
    val pending: Pending? = null,
    val commands: List<Command> = emptyList(),
    /** Null until requested; empty with [modelsError] when the agent cannot list them. */
    val models: List<ModelOption>? = null,
    val modelsError: String? = null,
    val workingSinceMillis: Long? = null,
    /** A short centered note under the last item, e.g. 「已中断」. */
    val notice: String? = null,
    val waking: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    val attachError: String? = null,
    val sending: Boolean = false,
    /** Messages sent from this phone that the transcript has not shown yet, oldest first. */
    val outgoing: List<Outgoing> = emptyList(),
    val restore: Restore? = null,
)

/** Only the item kinds the phone knows how to draw; anything else (thinking and so on) is dropped. */
private val shownKinds = setOf(ItemKind.USER, ItemKind.ASSISTANT, ItemKind.TOOL, ItemKind.INTERRUPTED)

/** Merges items by seq: a later item with the same seq replaces the earlier one, and a removed one deletes it. */
fun mergeItems(old: List<ChatItem>, new: List<ChatItem>): List<ChatItem> {
    val bySeq = LinkedHashMap<Long, ChatItem>()
    for (item in old + new) {
        when (item.kind) {
            ItemKind.REMOVED -> bySeq.remove(item.seq)
            in shownKinds -> bySeq[item.seq] = item
        }
    }
    return bySeq.values.sortedBy { it.seq }
}

class ChatViewModel(
    private val machineId: String,
    private val sessionId: String,
    private val relay: Relay,
    private val fleet: Fleet,
    /** Outlives the view model so chat.close still goes out when the page closes. */
    private val appScope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val compress: (ByteArray) -> ByteArray = { ImageCompressor.compress(it) },
    private val cache: ChatCache = ChatCache(),
    /** Where responses and events are decoded, off the main thread. */
    private val background: CoroutineContext = Dispatchers.Default,
) : ViewModel() {
    private val cacheKey = "$machineId/$sessionId"

    private val _state = MutableStateFlow(
        cache.get(cacheKey)?.let { c ->
            ChatUiState(loading = false, items = c.items, hasMore = c.hasMore, state = c.state, model = c.model, permissionMode = c.permissionMode)
        } ?: ChatUiState(),
    )
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** The tool_use id this phone just answered, so its own answer is not reported as handled elsewhere. */
    private var answered: String? = null

    /** True while chat.open is outstanding. */
    private var opening = false

    /** True once chat.open was answered, or failed; a reconnect opens again. */
    private var opened = false

    private var outgoingIds = 0L

    init {
        viewModelScope.launch {
            combine(relay.stateOf(machineId), fleet.machines, fleet.sessions) { conn, machines, sessions -> Triple(conn, machines, sessions) }
                .collect { (conn, machines, sessions) ->
                    val machine = machines?.firstOrNull { it.id == machineId }
                    val session = sessions[machineId]?.sessions?.firstOrNull { it.id == sessionId }
                    val online = conn == ConnectionState.Connected && machine?.online != false
                    val text = when {
                        conn != ConnectionState.Connected -> "没有连上中转服务器"
                        machine != null && !machine.online -> "已离线 · 最后在线 ${clauderc.app.ui.TimeText.clock(machine.lastSeenAt)}"
                        else -> ""
                    }
                    val reopen = online && !_state.value.online
                    _state.update { it.copy(session = session ?: it.session, online = online, offlineText = text) }
                    if (reopen || (online && !opened && !opening)) open()
                }
        }
        viewModelScope.launch {
            relay.events
                .filter { it.machine == machineId && it.body != null }
                .map(::decodeEvent)
                .flowOn(background)
                .collect { e ->
                    when (e) {
                        is ChatItemsEvent -> _state.update {
                            it.copy(items = mergeItems(it.items, e.items), notice = null, outgoing = settleOutgoing(it.outgoing, e.items))
                        }
                        is ChatPendingEvent -> onPending(e.pending)
                        is ChatStateEvent -> _state.update { applyStatus(it, e.state, e.model, e.permissionMode) }
                    }
                }
        }
    }

    /** The decoded event for this session, or null; runs on [background]. */
    private fun decodeEvent(env: clauderc.app.protocol.Envelope): Any? {
        val body = env.body ?: return null
        return when (env.method) {
            Method.CHAT_ITEMS -> decode(body, ChatItemsEvent.serializer())?.takeIf { it.sessionId == sessionId }
            Method.CHAT_PENDING -> decode(body, ChatPendingEvent.serializer())?.takeIf { it.sessionId == sessionId }
            Method.CHAT_STATE -> decode(body, ChatStateEvent.serializer())?.takeIf { it.sessionId == sessionId }
            else -> null
        }
    }

    private fun <T> decode(body: kotlinx.serialization.json.JsonElement, s: kotlinx.serialization.KSerializer<T>): T? =
        runCatching { ProtocolJson.decodeFromJsonElement(s, body) }.getOrNull()

    private fun applyStatus(s: ChatUiState, state: SessionState, model: String, mode: String) = s.copy(
        state = state,
        model = model.ifEmpty { s.model },
        permissionMode = mode.ifEmpty { s.permissionMode },
        workingSinceMillis = if (state == SessionState.Working) s.workingSinceMillis ?: now() else null,
        waking = s.waking && state == SessionState.Stopped,
    )

    private fun onPending(p: Pending?) {
        val old = _state.value.pending
        if (p == null && old != null && old.toolUseId != answered) {
            _messages.trySend("已在电脑上处理")
        }
        _state.update { it.copy(pending = p) }
    }

    private fun open() {
        opening = true
        viewModelScope.launch {
            try {
                val res = withContext(background) {
                    relay.call(Method.CHAT_OPEN, machineId, ChatOpenRequest(sessionId), ChatOpenRequest.serializer(), ChatOpenResponse.serializer())
                }
                _state.update {
                    applyStatus(it, res.state, res.model, res.permissionMode).copy(
                        loading = false,
                        items = mergeItems(emptyList(), res.items),
                        hasMore = res.hasMore,
                        pending = res.pending,
                        outgoing = settleOutgoing(it.outgoing, res.items),
                    )
                }
            } catch (e: RemoteException) {
                _state.update { it.copy(loading = false) }
                _messages.send(e.message ?: "打不开这个会话")
            } catch (e: Exception) {
                _state.update { it.copy(loading = false) }
            } finally {
                opening = false
                opened = true
            }
            runCatching {
                relay.call(Method.CHAT_COMMANDS, machineId, SessionIdRequest(sessionId), SessionIdRequest.serializer(), ChatCommandsResponse.serializer())
            }.onSuccess { r -> _state.update { it.copy(commands = r.commands) } }
        }
    }

    fun loadOlder() {
        val s = _state.value
        if (!s.hasMore || s.loadingOlder || s.items.isEmpty() || !s.online) return
        _state.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            val res = runCatching {
                withContext(background) {
                    relay.call(Method.CHAT_OPEN, machineId, ChatOpenRequest(sessionId, before = s.items.first().seq), ChatOpenRequest.serializer(), ChatOpenResponse.serializer())
                }
            }.getOrNull()
            _state.update {
                if (res == null) it.copy(loadingOlder = false)
                else it.copy(loadingOlder = false, items = mergeItems(res.items, it.items), hasMore = res.hasMore)
            }
        }
    }

    /**
     * Shows the message at once as a faded bubble and clears the composer; the bubble
     * gives way to the transcript's own item. A failed send takes the bubble back and
     * returns its text and pictures to the composer.
     */
    fun send(text: String): Boolean {
        val s = _state.value
        if (!s.online || s.sending || (text.isBlank() && s.attachments.isEmpty())) return false
        val out = Outgoing(++outgoingIds, text.trim(), s.attachments, afterSeq = s.items.lastOrNull()?.seq ?: 0)
        _state.update {
            it.copy(sending = true, waking = it.state == SessionState.Stopped, notice = null, outgoing = it.outgoing + out, attachments = emptyList(), attachError = null)
        }
        viewModelScope.launch {
            try {
                val images = withContext(background) { out.attachments.map { Image("image/jpeg", Base64.getEncoder().encodeToString(it.jpeg)) } }
                val res = relay.call(Method.CHAT_SEND, machineId, ChatSendRequest(sessionId, text, images.ifEmpty { null }), ChatSendRequest.serializer(), ChatSendResponse.serializer())
                _state.update { s ->
                    s.copy(sending = false, outgoing = if (res.queued == true) s.outgoing.map { if (it.id == out.id) it.copy(queued = true) else it } else s.outgoing)
                }
            } catch (e: RemoteException) {
                unsend(out)
                _messages.send(if (e.code == ErrorCode.NOT_DELIVERED) "消息没有送达，请重试" else e.message ?: "发送失败")
            } catch (e: Exception) {
                unsend(out)
                _messages.send("发送失败：${e.message}")
            }
        }
        return true
    }

    private fun unsend(out: Outgoing) = _state.update { s ->
        // The transcript may have shown it already; then there is nothing to take back.
        val local = s.outgoing.any { it.id == out.id }
        s.copy(
            sending = false,
            waking = false,
            outgoing = s.outgoing.filter { it.id != out.id },
            attachments = if (local) (out.attachments + s.attachments).take(ChatLimits.MAX_IMAGES) else s.attachments,
            restore = if (local && out.text.isNotEmpty()) Restore(out.id, out.text) else s.restore,
        )
    }

    fun allow() {
        answer(ChatAnswerRequest(sessionId, currentToolUse() ?: return, decision = Decision.ALLOW))
    }

    fun deny() {
        answer(ChatAnswerRequest(sessionId, currentToolUse() ?: return, decision = Decision.DENY))
    }

    fun answerQuestions(answers: List<Answer>) {
        answer(ChatAnswerRequest(sessionId, currentToolUse() ?: return, answers = answers))
    }

    private fun currentToolUse() = _state.value.pending?.toolUseId

    /** Closes the panel at once; a refused answer brings it back unless a newer one came meanwhile. */
    private fun answer(req: ChatAnswerRequest) {
        answered = req.toolUseId
        val asked = _state.value.pending
        _state.update { if (it.pending?.toolUseId == req.toolUseId) it.copy(pending = null) else it }
        fun putBack() = _state.update { if (it.pending == null) it.copy(pending = asked) else it }
        viewModelScope.launch {
            try {
                relay.call(Method.CHAT_ANSWER, machineId, req, ChatAnswerRequest.serializer(), Empty.serializer())
            } catch (e: RemoteException) {
                putBack()
                _messages.send(if (e.code == ErrorCode.PENDING_CHANGED) "这个请求已经变了，看一下新的内容" else e.message ?: "回答失败")
            } catch (e: Exception) {
                putBack()
                _messages.send("回答失败：${e.message}")
            }
        }
    }

    fun interrupt() {
        _state.update { it.copy(notice = INTERRUPTED) }
        viewModelScope.launch {
            try {
                relay.call(Method.CHAT_INTERRUPT, machineId, SessionIdRequest(sessionId), SessionIdRequest.serializer(), Empty.serializer())
            } catch (e: Exception) {
                _state.update { if (it.notice == INTERRUPTED) it.copy(notice = null) else it }
                _messages.send("中断失败：${e.message}")
            }
        }
    }

    fun loadModels() {
        _state.update { it.copy(models = null, modelsError = null) }
        viewModelScope.launch {
            try {
                val res = relay.call(Method.CHAT_MODELS, machineId, SessionIdRequest(sessionId), SessionIdRequest.serializer(), ChatModelsResponse.serializer())
                _state.update { it.copy(models = res.models) }
            } catch (e: RemoteException) {
                _state.update {
                    it.copy(models = emptyList(), modelsError = if (e.code == ErrorCode.UNKNOWN_METHOD) "这台目标机的程序还不支持切换模型" else e.message)
                }
            } catch (e: Exception) {
                _state.update { it.copy(models = emptyList(), modelsError = "读不到模型列表") }
            }
        }
    }

    /** Shows the picked model at once; chat.state later brings the transcript's model id. */
    fun setModel(option: ModelOption) {
        val before = _state.value
        val label = option.label.ifEmpty { option.id }
        _state.update { s -> s.copy(model = label, models = s.models?.map { it.copy(current = it.id == option.id) }) }
        viewModelScope.launch {
            try {
                relay.call(Method.CHAT_SET_MODEL, machineId, ChatSetModelRequest(sessionId, option.id), ChatSetModelRequest.serializer(), Empty.serializer())
            } catch (e: Exception) {
                _state.update { s -> if (s.model == label) s.copy(model = before.model, models = before.models) else s }
                _messages.send("切换模型失败：${e.message}")
            }
        }
    }

    fun setPermissionMode(mode: String) {
        val before = _state.value.permissionMode
        _state.update { it.copy(permissionMode = mode) }
        viewModelScope.launch {
            try {
                relay.call(Method.CHAT_SET_PERMISSION_MODE, machineId, ChatSetPermissionModeRequest(sessionId, mode), ChatSetPermissionModeRequest.serializer(), Empty.serializer())
            } catch (e: Exception) {
                _state.update { if (it.permissionMode == mode) it.copy(permissionMode = before) else it }
                _messages.send("切换权限模式失败：${e.message}")
            }
        }
    }

    /** Checks the limits before compressing; [read] loads the original bytes. */
    fun addImage(name: String, sizeBytes: Long, read: suspend () -> ByteArray) {
        val s = _state.value
        val error = when {
            s.attachments.size >= ChatLimits.MAX_IMAGES -> "最多 ${ChatLimits.MAX_IMAGES} 张，$name 没有添加"
            sizeBytes > ChatLimits.MAX_IMAGE_BYTES -> "$name 有 ${"%.1f".format(sizeBytes / 1048576.0)} MB，超过 10 MB，没有添加"
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(attachError = error) }
            return
        }
        viewModelScope.launch {
            val jpeg = runCatching { withContext(Dispatchers.Default) { compress(read()) } }.getOrNull()
            _state.update {
                when {
                    jpeg == null -> it.copy(attachError = "$name 读不出来，没有添加")
                    it.attachments.size >= ChatLimits.MAX_IMAGES -> it.copy(attachError = "最多 ${ChatLimits.MAX_IMAGES} 张，$name 没有添加")
                    else -> it.copy(attachments = it.attachments + Attachment(name, jpeg), attachError = null)
                }
            }
        }
    }

    fun removeImage(index: Int) = _state.update { s -> s.copy(attachments = s.attachments.filterIndexed { i, _ -> i != index }, attachError = null) }

    override fun onCleared() {
        val s = _state.value
        if (s.items.isNotEmpty()) {
            val kept = s.items.takeLast(ChatCache.KEEP)
            cache.put(cacheKey, ChatCache.Entry(kept, s.hasMore || kept.size < s.items.size, s.state, s.model, s.permissionMode))
        }
        appScope.launch {
            runCatching { relay.call(Method.CHAT_CLOSE, machineId, SessionIdRequest(sessionId), SessionIdRequest.serializer(), Empty.serializer()) }
        }
    }
}

private const val INTERRUPTED = "已中断"
