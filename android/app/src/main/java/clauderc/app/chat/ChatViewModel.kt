package clauderc.app.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import clauderc.app.data.DraftStore
import clauderc.app.data.Fleet
import clauderc.app.net.ConnectionState
import clauderc.app.net.Relay
import clauderc.app.net.RemoteException
import clauderc.app.net.call
import clauderc.app.protocol.Answer
import clauderc.app.protocol.AutoResume
import clauderc.app.protocol.ChatAutoResumeRequest
import clauderc.app.protocol.ChatAutoResumeResponse
import clauderc.app.protocol.ChatAnswerRequest
import clauderc.app.protocol.ChatCloseRequest
import clauderc.app.protocol.ChatCommandsResponse
import clauderc.app.protocol.ChatImageRequest
import clauderc.app.protocol.ChatImageResponse
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
import clauderc.app.protocol.ChatToolOutputRequest
import clauderc.app.protocol.Command
import clauderc.app.protocol.Decision
import clauderc.app.protocol.Empty
import clauderc.app.protocol.ErrorCode
import clauderc.app.protocol.Image
import clauderc.app.protocol.ItemKind
import clauderc.app.protocol.Limit
import clauderc.app.protocol.Method
import clauderc.app.protocol.ModelOption
import clauderc.app.protocol.Pending
import clauderc.app.protocol.ProtocolJson
import clauderc.app.protocol.Session
import clauderc.app.protocol.SessionCreateRequest
import clauderc.app.protocol.SessionCreateResponse
import clauderc.app.protocol.SessionIdRequest
import clauderc.app.protocol.SessionState
import clauderc.app.protocol.ToolResult
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A picture waiting in the composer, already resized and re-encoded as JPEG. */
class Attachment(val name: String, val jpeg: ByteArray)

/** The pictures' size together, which [ChatLimits.MAX_IMAGES_TOTAL_BYTES] bounds. */
val List<Attachment>.bytes: Long get() = sumOf { it.jpeg.size.toLong() }

/** The leading pictures that fit under [ChatLimits.MAX_IMAGES_TOTAL_BYTES] together. */
private fun List<Attachment>.fitting(): List<Attachment> {
    var total = 0L
    return takeWhile { total += it.jpeg.size; total <= ChatLimits.MAX_IMAGES_TOTAL_BYTES }
}

/**
 * A message this phone sent that the transcript has not shown yet; drawn faded under the
 * last item. Only items after [afterSeq] can be its delivery. [queued] is set once the agent
 * says it waits in Claude Code's queue, as a slash command does: the transcript only shows
 * it when it runs. [delivered] is set once chat.send says the transcript shows it.
 */
data class Outgoing(
    val id: Long,
    val text: String,
    val attachments: List<Attachment>,
    val afterSeq: Long,
    val queued: Boolean = false,
    val delivered: Boolean = false,
)

/** A send that failed; [out] goes back to the composer unless the transcript showed it meanwhile. */
data class SendFailure(val message: String, val out: Outgoing?)

/** Text put back into the composer after a failed send; [id] tells two restores of the same text apart. */
data class Restore(val id: Long, val text: String)

/** Drops the outgoing messages that [delivered] user items or slash command rows show, oldest first. */
fun settleOutgoing(outgoing: List<Outgoing>, delivered: List<ChatItem>): List<Outgoing> {
    val shown = matchOutgoing(outgoing, delivered).map { it.first.id }.toSet()
    return if (shown.isEmpty()) outgoing else outgoing.filter { it.id !in shown }
}

/**
 * The list keys of [delivered] items that show an outgoing message: the outgoing bubble's own key,
 * so the bubble turns into the transcript's item in place instead of one fading out and another in.
 */
fun deliveredKeys(outgoing: List<Outgoing>, delivered: List<ChatItem>): Map<Long, String> =
    matchOutgoing(outgoing, delivered).associate { (o, item) -> item.seq to outgoingKey(o) }

/** The list key of an outgoing bubble. */
fun outgoingKey(o: Outgoing) = "out-${o.id}"

/** Pairs each outgoing message with the [delivered] user item or slash command row that shows it, oldest first. */
private fun matchOutgoing(outgoing: List<Outgoing>, delivered: List<ChatItem>): List<Pair<Outgoing, ChatItem>> {
    if (outgoing.isEmpty()) return emptyList()
    val left = outgoing.toMutableList()
    val matched = mutableListOf<Pair<Outgoing, ChatItem>>()
    for (item in delivered) {
        // Pictures show up as "[Image #1] " in front of the text, so compare the end.
        val text = when {
            item.kind == ItemKind.USER -> item.text.orEmpty().trim()
            item.tool != null && isCommand(item.tool) -> squeezeBang(item.tool.summary.trim())
            else -> continue
        }
        val hit = left.firstOrNull { o ->
            item.seq > o.afterSeq && if (o.text.isEmpty()) !item.images.isNullOrEmpty() else text.endsWith(squeezeBang(o.text))
        }
        if (hit != null) {
            left.remove(hit)
            matched += hit to item
        }
    }
    return matched
}

/** A "!" command without the space after the "!": its row says "! ls" whether "!ls" or "! ls" was sent. */
private fun squeezeBang(text: String): String = if (text.startsWith("!")) "!" + text.drop(1).trimStart() else text

/** What a chat page showed last time, so reopening it draws at once while chat.open catches up. Memory only. */
class ChatCache(private val capacity: Int = 8) {
    /** [workingSinceMillis] keeps the 「工作中」 count going when the page is opened again. */
    data class Entry(
        val items: List<ChatItem>,
        val hasMore: Boolean,
        val state: SessionState,
        val model: String,
        val permissionMode: String,
        val workingSinceMillis: Long? = null,
    )

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > capacity
    }

    @Synchronized fun get(key: String): Entry? = entries[key]

    @Synchronized fun put(key: String, entry: Entry) {
        entries[key] = entry
    }

    /** One session's messages the transcript has not shown yet, and failed sends no page has reported. */
    class Outbox {
        val outgoing = MutableStateFlow<List<Outgoing>>(emptyList())
        val failed = MutableStateFlow<List<SendFailure>>(emptyList())
    }

    private val outboxes = HashMap<String, Outbox>()
    private val ids = AtomicLong()

    /** Outlives the chat page, as the send does, and is never evicted. */
    @Synchronized fun outbox(key: String): Outbox = outboxes.getOrPut(key) { Outbox() }

    /** Outgoing ids are unique across pages, so a reopened page never reuses a pending message's id. */
    fun nextId(): Long = ids.incrementAndGet()

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
    /** List keys of items that took over an outgoing bubble, by seq; see [deliveredKeys]. */
    val keys: Map<Long, String> = emptyMap(),
    val restore: Restore? = null,
    /** Whole results of tool calls that came cut to 8 KB, fetched with chat.toolOutput, by tool_use id. */
    val fullResults: Map<String, ToolResult> = emptyMap(),
    /** The tool_use ids whose whole result is on its way. */
    val fetchingResults: Set<String> = emptySet(),
    /** Pictures of tool results fetched with chat.image for the viewer, by picture id. */
    val wholeImages: Map<String, ByteArray> = emptyMap(),
    val fetchingImages: Set<String> = emptySet(),
    /** Pictures chat.image could not bring; opening one again tries again. */
    val failedImages: Set<String> = emptySet(),
    /** True while the session is blocked on a panel or dialog the agent does not know; the page offers the terminal. */
    val unknownDialog: Boolean = false,
    /** Claude Code's status line while it works, e.g. 「Whirring… (20s · ↓ 2.4k tokens)」; null falls back to 「工作中 · N 秒」. */
    val activity: String? = null,
    /** The reply Claude Code has drawn on the computer's screen and not written yet, drawn under the last item. */
    val partial: PartialReply? = null,
    /** False until chat.open answered; replies that come in before then were already there and are not typed out. */
    val opened: Boolean = true,
    /** The plan usage limit the session waits out, with what the agent is to send once it is lifted. */
    val limit: Limit? = null,
    /** The text to send once the limit is lifted, as typed; it follows the agent's setting when that changes. */
    val resumeText: String = RESUME_TEXT,
    /** True while session.create starts a session in a new worktree of this session's directory. */
    val creatingWorktree: Boolean = false,
    /** Why the last session.create from here failed, shown in the worktree dialog. */
    val worktreeError: String? = null,
)

/**
 * A reply drawn on the computer's screen and not yet in the transcript. [key] is its bubble's list key: the
 * reply's item takes it over once written, so the bubble carries on typing in place. Only a reply after
 * [afterSeq] can be it.
 */
data class PartialReply(val text: String, val key: String, val afterSeq: Long)

/**
 * chat.state's [partial] on top of what the page shows. The agent reads the screen and the transcript
 * apart, so right after the reply is written it may send it once more: a partial that begins the
 * current turn's latest reply is that echo and is dropped.
 */
fun nextPartial(old: PartialReply?, partial: String?, items: List<ChatItem>, newKey: () -> String): PartialReply? {
    if (partial.isNullOrEmpty() || writtenAlready(items, partial)) return null
    return old?.copy(text = partial) ?: PartialReply(partial, newKey(), items.lastOrNull()?.seq ?: 0)
}

/** The first reply among [new] that came after [partial] started: it takes over the partial's bubble. */
fun takeOver(partial: PartialReply?, new: List<ChatItem>): ChatItem? =
    partial?.let { p -> new.firstOrNull { it.kind == ItemKind.ASSISTANT && it.seq > p.afterSeq } }

/** True when the latest reply since the last user message begins with [partial], ignoring markdown and spacing. */
private fun writtenAlready(items: List<ChatItem>, partial: String): Boolean {
    val reply = items.subList(items.indexOfLast { it.kind == ItemKind.USER } + 1, items.size).lastOrNull { it.kind == ItemKind.ASSISTANT } ?: return false
    val written = letters(reply.text.orEmpty())
    val drawn = letters(partial).take(40)
    return drawn.isNotEmpty() && written.startsWith(drawn)
}

/** The letters and digits of [s]: the screen draws markdown as plain text and wraps lines its own way. */
private fun letters(s: String): String = s.filter { it.isLetterOrDigit() }

/** Only the item kinds the phone knows how to draw; anything else (thinking and so on) is dropped. */
private val shownKinds = setOf(ItemKind.USER, ItemKind.ASSISTANT, ItemKind.TOOL, ItemKind.NOTICE, ItemKind.INTERRUPTED)

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

/**
 * [mergeItems] for an event on top of what the page shows. While a reply streams in, everything new
 * comes after [shown], which is already merged, so it is appended without re-sorting the transcript.
 */
fun mergeNewer(shown: List<ChatItem>, new: List<ChatItem>): List<ChatItem> {
    val last = shown.lastOrNull()?.seq ?: Long.MIN_VALUE
    val newer = new.firstOrNull()?.let { it.seq > last } != false && new.zipWithNext().all { (a, b) -> a.seq < b.seq }
    return if (newer) shown + new.filter { it.kind in shownKinds } else mergeItems(shown, new)
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
    /** Set for a subagent's page: its transcript, read only, with no commands to fetch. */
    private val agentId: String? = null,
    /** Where the composer's unsent text is kept; null keeps none. */
    private val drafts: DraftStore? = null,
) : ViewModel() {
    private val cacheKey = "$machineId/$sessionId" + agentId?.let { "/$it" }.orEmpty()
    private val outbox = cache.outbox(cacheKey)
    private val compressing = Mutex()

    private val _state = MutableStateFlow(
        (
            cache.get(cacheKey)?.let { c ->
                ChatUiState(
                    loading = false, items = c.items, hasMore = c.hasMore, state = c.state, model = c.model, permissionMode = c.permissionMode,
                    workingSinceMillis = c.workingSinceMillis,
                )
            } ?: ChatUiState()
        ).let { s ->
            val out = outbox.outgoing.value
            s.copy(outgoing = out, waking = s.state == SessionState.Stopped && out.any { !it.delivered && !it.queued }, opened = false)
        },
    )
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val _created = Channel<String>(Channel.BUFFERED)
    /** Emits the id of a session started in a new worktree from here, once the agent has started it. */
    val created: Flow<String> = _created.receiveAsFlow()

    /** The wait before a text typed while the auto-send is on goes to the agent. */
    private var resuming: Job? = null

    /** The tool_use id this phone just answered, so its own answer is not reported as handled elsewhere. */
    private var answered: String? = null

    /** True while chat.open is outstanding. */
    private var opening = false

    /** True once chat.open was answered, or failed; a reconnect opens again. */
    private var opened = false

    /** Typed text not saved as the draft yet, and the wait before it is. */
    private var unsaved: String? = null
    private var saving: Job? = null

    init {
        // The draft goes back into the composer, the cursor after it; not when it is a message still on its way.
        if (drafts != null && agentId == null) {
            viewModelScope.launch {
                val d = drafts.draft(machineId, sessionId).first()
                if (d.isNotBlank() && outbox.outgoing.value.none { it.text == d.trim() }) {
                    _state.update { it.copy(restore = Restore(cache.nextId(), d)) }
                }
            }
        }
        viewModelScope.launch { outbox.outgoing.collect { o -> _state.update { it.copy(outgoing = o) } } }
        viewModelScope.launch {
            outbox.failed.filter { it.isNotEmpty() }.collect {
                for (f in outbox.failed.getAndUpdate { emptyList() }) takeBack(f)
            }
        }
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
                        is ChatItemsEvent -> {
                            val before = outbox.outgoing.getAndUpdate { settleOutgoing(it, e.items) }
                            _state.update {
                                // The written reply takes over the bubble its screen preview was typing in.
                                val taker = takeOver(it.partial, e.items)
                                it.copy(
                                    items = mergeNewer(it.items, e.items), notice = null,
                                    outgoing = outbox.outgoing.value,
                                    keys = it.keys + deliveredKeys(before, e.items) + listOfNotNull(taker?.let { t -> t.seq to it.partial!!.key }),
                                    partial = if (taker != null) null else it.partial,
                                )
                            }
                        }
                        is ChatPendingEvent -> onPending(e.pending)
                        is ChatStateEvent -> _state.update {
                            applyStatus(it, e.state, e.model, e.permissionMode).copy(unknownDialog = e.unknownDialog == true).withScreen(e.state, e.activity, e.partial).withLimit(e.limit)
                        }
                    }
                }
        }
    }

    /** The decoded event for this session, or null; runs on [background]. A subagent's events carry its id; the session's own none. */
    private fun decodeEvent(env: clauderc.app.protocol.Envelope): Any? {
        val body = env.body ?: return null
        return when (env.method) {
            Method.CHAT_ITEMS -> decode(body, ChatItemsEvent.serializer())?.takeIf { it.sessionId == sessionId && it.agentId == agentId }
            Method.CHAT_PENDING -> decode(body, ChatPendingEvent.serializer())?.takeIf { it.sessionId == sessionId && agentId == null }
            Method.CHAT_STATE -> decode(body, ChatStateEvent.serializer())?.takeIf { it.sessionId == sessionId && it.agentId == agentId }
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

    /** What the computer's screen shows while the session works: the status line and the reply drawn so far. */
    private fun ChatUiState.withScreen(state: SessionState, activity: String?, partial: String?): ChatUiState {
        val working = state == SessionState.Working
        return copy(
            activity = activity?.takeIf { working && it.isNotEmpty() },
            partial = if (working) nextPartial(this.partial, partial, items) { "partial-${cache.nextId()}" } else null,
        )
    }

    /** The agent's [limit]; a text the agent is newly set to send replaces what was typed. */
    private fun ChatUiState.withLimit(limit: Limit?): ChatUiState {
        val set = limit?.autoResume?.text
        return copy(limit = limit, resumeText = if (set != null && set != this.limit?.autoResume?.text) set else resumeText)
    }

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
                    relay.call(Method.CHAT_OPEN, machineId, ChatOpenRequest(sessionId, agentId = agentId), ChatOpenRequest.serializer(), ChatOpenResponse.serializer())
                }
                // A delivered message missing from the page came before it, e.g. while the page was closed.
                val before = outbox.outgoing.getAndUpdate { o -> settleOutgoing(o, res.items).filter { !it.delivered } }
                _state.update {
                    applyStatus(it, res.state, res.model, res.permissionMode).copy(
                        loading = false,
                        opened = true,
                        items = mergeItems(emptyList(), res.items),
                        hasMore = res.hasMore,
                        pending = res.pending,
                        unknownDialog = res.unknownDialog == true,
                        outgoing = outbox.outgoing.value,
                        keys = it.keys + deliveredKeys(before, res.items),
                    ).let { s -> s.withScreen(res.state, res.activity, res.partial).withLimit(res.limit) }
                }
            } catch (e: RemoteException) {
                _state.update { it.copy(loading = false, opened = true) }
                _messages.send(e.message ?: "打不开这个会话")
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, opened = true) }
            } finally {
                opening = false
                opened = true
            }
            if (agentId != null) return@launch
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
                    relay.call(Method.CHAT_OPEN, machineId, ChatOpenRequest(sessionId, before = s.items.first().seq, agentId = agentId), ChatOpenRequest.serializer(), ChatOpenResponse.serializer())
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
     * returns its text and pictures to the composer. The send and its bubble outlive the
     * page: one that fails after the page closed is reported by the next page of this session.
     */
    fun send(text: String): Boolean = send(text, command = false)

    /**
     * Sends `/fork` with [instruction], if any, as the composer would, leaving the composer's pictures and
     * the draft alone. The fork starts as a background session of its own; the page stays on this one.
     */
    fun fork(instruction: String): Boolean = send("/fork ${instruction.trim()}".trim(), command = true)

    /** [command] is a message from a dialog, not the composer: it takes no pictures and keeps no draft. */
    private fun send(text: String, command: Boolean): Boolean {
        val s = _state.value
        val images = if (command) emptyList() else s.attachments
        if (!s.online || s.sending || (text.isBlank() && images.isEmpty())) return false
        if (!command) {
            saving?.cancel()
            unsaved = null
        }
        val out = Outgoing(cache.nextId(), text.trim(), images, afterSeq = s.items.lastOrNull()?.seq ?: 0)
        outbox.outgoing.update { it + out }
        _state.update {
            it.copy(
                sending = true, waking = it.state == SessionState.Stopped, notice = null, outgoing = outbox.outgoing.value,
                attachments = if (command) it.attachments else emptyList(), attachError = if (command) it.attachError else null,
            )
        }
        val job = appScope.launch { deliver(out, text, draft = !command) }
        viewModelScope.launch {
            job.join()
            _state.update { it.copy(sending = false) }
        }
        return true
    }

    private suspend fun deliver(out: Outgoing, text: String, draft: Boolean) {
        // The text stays the draft until the send succeeds, so a failed one is not lost with the page.
        val kept = if (draft) drafts?.let { d -> appScope.launch { d.set(machineId, sessionId, text) } } else null
        try {
            val images = withContext(background) { out.attachments.map { Image("image/jpeg", Base64.getEncoder().encodeToString(it.jpeg)) } }
            val res = relay.call(
                Method.CHAT_SEND, machineId, ChatSendRequest(sessionId, text, images.ifEmpty { null }), ChatSendRequest.serializer(), ChatSendResponse.serializer(),
                timeoutMillis = SEND_IMAGES_TIMEOUT_MILLIS.takeIf { images.isNotEmpty() },
            )
            val queued = res.queued == true
            outbox.outgoing.update { o -> o.map { if (it.id == out.id) it.copy(queued = queued, delivered = !queued) else it } }
            kept?.join()
            if (draft) drafts?.clear(machineId, sessionId, text)
        } catch (e: RemoteException) {
            fail(out, if (e.code == ErrorCode.NOT_DELIVERED) "消息没有送达，请重试" else e.message ?: "发送失败")
        } catch (e: Exception) {
            fail(out, "发送失败：${e.message}")
        }
    }

    private fun fail(out: Outgoing, message: String) {
        // The transcript may have shown it already; then there is nothing to take back.
        val local = outbox.outgoing.getAndUpdate { o -> o.filter { it.id != out.id } }.any { it.id == out.id }
        outbox.failed.update { it + SendFailure(message, out.takeIf { local }) }
    }

    private fun takeBack(f: SendFailure) {
        val out = f.out
        _state.update { s ->
            s.copy(
                waking = false,
                attachments = if (out != null) (out.attachments + s.attachments).fitting() else s.attachments,
                restore = if (out != null && out.text.isNotEmpty()) Restore(out.id, out.text) else s.restore,
            )
        }
        _messages.trySend(f.message)
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

    /** Fetches the whole result of a tool call cut to 8 KB, when its row opens; a failure is reported and the row stays as it was. */
    fun loadToolOutput(toolUseId: String) {
        val s = _state.value
        if (toolUseId in s.fullResults || toolUseId in s.fetchingResults) return
        if (!s.online) {
            _messages.trySend("离线时取不到全文")
            return
        }
        _state.update { it.copy(fetchingResults = it.fetchingResults + toolUseId) }
        viewModelScope.launch {
            val res = runCatching {
                withContext(background) {
                    relay.call(Method.CHAT_TOOL_OUTPUT, machineId, ChatToolOutputRequest(sessionId, toolUseId), ChatToolOutputRequest.serializer(), ToolResult.serializer())
                }
            }
            _state.update { s ->
                s.copy(
                    fetchingResults = s.fetchingResults - toolUseId,
                    fullResults = res.getOrNull()?.let { s.fullResults + (toolUseId to it) } ?: s.fullResults,
                )
            }
            res.exceptionOrNull()?.let { _messages.send((it as? RemoteException)?.message ?: "取不到全文") }
        }
    }

    /** Fetches a picture for the viewer, once; a failure marks it so the viewer can say so. */
    fun loadImage(id: String) {
        val s = _state.value
        if (id in s.wholeImages || id in s.fetchingImages) return
        if (!s.online) {
            _state.update { it.copy(failedImages = it.failedImages + id) }
            return
        }
        _state.update { it.copy(fetchingImages = it.fetchingImages + id, failedImages = it.failedImages - id) }
        viewModelScope.launch {
            val bytes = runCatching {
                withContext(background) {
                    val res = relay.call(Method.CHAT_IMAGE, machineId, ChatImageRequest(sessionId, id), ChatImageRequest.serializer(), ChatImageResponse.serializer())
                    java.util.Base64.getDecoder().decode(res.data)
                }
            }.getOrNull()
            _state.update { s ->
                s.copy(
                    fetchingImages = s.fetchingImages - id,
                    wholeImages = if (bytes != null) s.wholeImages + (id to bytes) else s.wholeImages,
                    failedImages = if (bytes == null) s.failedImages + id else s.failedImages,
                )
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

    /**
     * Checks the original's size before compressing and the total after; [read] loads the original bytes.
     * Pictures are compressed one at a time, as a big photo decodes to tens of MB, and so keep the order they were picked in.
     */
    fun addImage(name: String, sizeBytes: Long, read: suspend () -> ByteArray) {
        if (sizeBytes > ChatLimits.MAX_IMAGE_BYTES) {
            _state.update { it.copy(attachError = "$name 有 ${"%.1f".format(sizeBytes / 1048576.0)} MB，超过 10 MB，没有添加") }
            return
        }
        viewModelScope.launch {
            val jpeg = runCatching { compressing.withLock { withContext(background) { compress(read()) } } }.getOrNull()
            _state.update {
                when {
                    jpeg == null -> it.copy(attachError = "$name 读不出来，没有添加")
                    it.attachments.bytes + jpeg.size > ChatLimits.MAX_IMAGES_TOTAL_BYTES ->
                        it.copy(attachError = "图片加起来超过 ${ChatLimits.MAX_IMAGES_TOTAL_BYTES shr 20} MB，$name 没有添加")
                    else -> it.copy(attachments = it.attachments + Attachment(name, jpeg), attachError = null)
                }
            }
        }
    }

    fun removeImage(index: Int) = _state.update { s -> s.copy(attachments = s.attachments.filterIndexed { i, _ -> i != index }, attachError = null) }

    /**
     * Turns on or off the message the agent sends once the usage limit is lifted, with the typed text. The
     * switch moves at once and goes back if the agent refuses.
     */
    fun setAutoResume(on: Boolean) {
        val s = _state.value
        val limit = s.limit ?: return
        val text = s.resumeText.trim()
        if (!s.online || (on && text.isEmpty())) return
        resuming?.cancel()
        _state.update { it.copy(limit = limit.copy(autoResume = if (on) AutoResume(text, limit.resetsAt.plusSeconds(RESUME_DELAY_SECONDS)) else null)) }
        viewModelScope.launch {
            try {
                val res = relay.call(
                    Method.CHAT_AUTO_RESUME, machineId, ChatAutoResumeRequest(sessionId, on, text.takeIf { on }),
                    ChatAutoResumeRequest.serializer(), ChatAutoResumeResponse.serializer(),
                )
                _state.update { it.withLimit(res.limit) }
            } catch (e: Exception) {
                _state.update { if (it.limit?.resetsAt == limit.resetsAt) it.copy(limit = limit) else it }
                _messages.send(
                    when {
                        e is RemoteException && e.code == ErrorCode.UNKNOWN_METHOD -> "这台目标机的程序还不支持自动发送"
                        e is RemoteException -> e.message ?: "设置自动发送失败"
                        else -> "设置自动发送失败：${e.message}"
                    },
                )
            }
        }
    }

    /** The text to send once the limit is lifted was edited; while the auto-send is on, it goes to the agent once typing pauses. */
    fun typedResume(text: String) {
        _state.update { it.copy(resumeText = text) }
        resuming?.cancel()
        if (_state.value.limit?.autoResume == null || text.isBlank()) return
        resuming = viewModelScope.launch {
            delay(DRAFT_SAVE_MILLIS)
            setAutoResume(true)
        }
    }

    /**
     * Starts a session in a new git worktree [name] of the directory this session is listed under, with this
     * session's profile and [prompt] as its first message; [created] then brings its id.
     */
    fun createWorktree(name: String, prompt: String) {
        val s = _state.value
        val session = s.session ?: return
        if (!s.online || s.creatingWorktree) return
        if (!WORKTREE_NAME.matches(name)) {
            _state.update { it.copy(worktreeError = WORKTREE_NAME_RULE) }
            return
        }
        _state.update { it.copy(creatingWorktree = true, worktreeError = null) }
        viewModelScope.launch {
            val request = SessionCreateRequest(
                profileId = session.profileId,
                cwd = session.dir ?: session.cwd,
                prompt = prompt.trim().ifEmpty { null },
                worktree = name,
            )
            try {
                val res = relay.call(Method.SESSION_CREATE, machineId, request, SessionCreateRequest.serializer(), SessionCreateResponse.serializer())
                fleet.refresh(machineId)
                _state.update { it.copy(creatingWorktree = false) }
                _created.send(res.sessionId)
            } catch (e: RemoteException) {
                _state.update { it.copy(creatingWorktree = false, worktreeError = e.message ?: "新建失败") }
            } catch (e: Exception) {
                _state.update { it.copy(creatingWorktree = false, worktreeError = "新建失败：${e.message}") }
            }
        }
    }

    /** The worktree dialog closed or its name changed: its last failure goes. */
    fun clearWorktreeError() = _state.update { it.copy(worktreeError = null) }

    /** The composer's text changed: kept as the draft once typing pauses for half a second. */
    fun typed(text: String) {
        if (drafts == null || agentId != null) return
        unsaved = text
        saving?.cancel()
        saving = viewModelScope.launch {
            delay(DRAFT_SAVE_MILLIS)
            unsaved = null
            drafts.set(machineId, sessionId, text)
        }
    }

    override fun onCleared() {
        // Leaving the page keeps what was typed in the last half second too.
        unsaved?.let { text -> drafts?.let { d -> appScope.launch { d.set(machineId, sessionId, text) } } }
        val s = _state.value
        if (s.items.isNotEmpty()) {
            val kept = s.items.takeLast(ChatCache.KEEP)
            cache.put(cacheKey, ChatCache.Entry(kept, s.hasMore || kept.size < s.items.size, s.state, s.model, s.permissionMode, s.workingSinceMillis))
        }
        appScope.launch {
            runCatching { relay.call(Method.CHAT_CLOSE, machineId, ChatCloseRequest(sessionId, agentId), ChatCloseRequest.serializer(), Empty.serializer()) }
        }
    }
}

private const val INTERRUPTED = "已中断"

/** How long typing pauses before the composer's text is kept as the draft. */
const val DRAFT_SAVE_MILLIS = 500L

/** What the agent sends once a usage limit is lifted, unless changed. */
const val RESUME_TEXT = "继续"

/** The agent sends a minute after the reset, so the message does not land on its edge and get refused. */
private const val RESUME_DELAY_SECONDS = 60L

/** A message with pictures waits longer: up to 11 MB to upload, and the agent pastes each picture with a pause. */
const val SEND_IMAGES_TIMEOUT_MILLIS = 120_000L

/** A worktree name session.create takes, as the agent checks it. */
val WORKTREE_NAME = Regex("^[A-Za-z0-9_][A-Za-z0-9._-]{0,63}$")

const val WORKTREE_NAME_RULE = "只能用字母、数字、点、横线和下划线，不能以点或横线开头"

/** The name the worktree dialog suggests: 「wt-」 and the month, day, hour and minute, e.g. 「wt-1119-1530」. */
fun worktreeName(now: java.time.LocalDateTime): String = "wt-%02d%02d-%02d%02d".format(now.monthValue, now.dayOfMonth, now.hour, now.minute)
