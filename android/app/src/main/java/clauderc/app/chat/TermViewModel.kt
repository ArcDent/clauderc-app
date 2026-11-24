package clauderc.app.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import clauderc.app.data.Fleet
import clauderc.app.net.ConnectionState
import clauderc.app.net.Relay
import clauderc.app.net.RemoteException
import clauderc.app.net.call
import clauderc.app.protocol.Empty
import clauderc.app.protocol.ErrorCode
import clauderc.app.protocol.Method
import clauderc.app.protocol.ProtocolJson
import clauderc.app.protocol.Session
import clauderc.app.protocol.SessionIdRequest
import clauderc.app.protocol.SessionState
import clauderc.app.protocol.TermCloseRequest
import clauderc.app.protocol.TermKeyRequest
import clauderc.app.protocol.TermScreen
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
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

data class TermUiState(
    val session: Session? = null,
    val online: Boolean = true,
    val loading: Boolean = true,
    /** The screen term.open answered with or term.screen last brought; null until then. */
    val screen: TermScreen? = null,
    /** Why term.open failed, such as a stopped session; the keys are off then. */
    val error: String? = null,
) {
    /** The session is gone: its terminal shows its last screen and takes no keys. */
    val ended: Boolean get() = error != null || session?.state == SessionState.Stopped
}

/**
 * The terminal view of one session: term.open on start and after a reconnect, term.screen
 * events in between, term.key for the key bar and term.close once the page is gone.
 */
class TermViewModel(
    private val machineId: String,
    private val sessionId: String,
    private val relay: Relay,
    private val fleet: Fleet,
    /** Outlives the view model so term.close still goes out when the page closes. */
    private val appScope: CoroutineScope,
    private val background: CoroutineContext = Dispatchers.Default,
    /** Shown once on the terminal page's snackbar, e.g. why a rewind fell back here. */
    initialNotice: String? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(TermUiState())
    val state: StateFlow<TermUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Key taps sent strictly in order, each waiting for the previous term.key to answer. */
    private val keys = Channel<String>(Channel.UNLIMITED)

    private var opened = false
    private var opening = false
    private var lastConnId: String? = null
    private var lastRev = 0L
    @Volatile private var subId: String? = null

    init {
        if (!initialNotice.isNullOrEmpty()) _messages.trySend(initialNotice)
        viewModelScope.launch {
            for (key in keys) {
                if (_state.value.ended || !_state.value.online) continue
                try {
                    relay.call(Method.TERM_KEY, machineId, TermKeyRequest(sessionId, key), TermKeyRequest.serializer(), Empty.serializer())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _messages.send((e as? RemoteException)?.message ?: "按键没有发出")
                }
            }
        }
        viewModelScope.launch {
            combine(relay.stateOf(machineId), fleet.machines, fleet.sessions) { conn, machines, sessions ->
                val machine = machines?.firstOrNull { it.id == machineId }
                Triple(conn == ConnectionState.Connected && machine?.online != false, sessions[machineId]?.sessions?.firstOrNull { it.id == sessionId }, machine?.connId)
            }.collect { (online, session, rawConnId) ->
                val connId = rawConnId?.takeIf { it.isNotEmpty() }
                val connChanged = online && connId != null && lastConnId != null && connId != lastConnId
                if (online && connId != null) lastConnId = connId
                val reopen = (online && !_state.value.online) || connChanged
                _state.update { it.copy(session = session ?: it.session, online = online) }
                if (reopen) {
                    lastRev = 0L
                    subId = null
                    open()
                } else if (online && !opened && !opening) {
                    open()
                }
            }
        }
        viewModelScope.launch {
            relay.events
                .filter { it.machine == machineId && it.method == Method.TERM_SCREEN && it.body != null }
                .map { e -> runCatching { ProtocolJson.decodeFromJsonElement(TermScreen.serializer(), e.body!!) }.getOrNull()?.takeIf { it.sessionId == sessionId } }
                .flowOn(background)
                .collect { s ->
                    if (s == null) return@collect
                    val rev = s.rev ?: 0L
                    if (rev > 0L && rev < lastRev) return@collect
                    if (rev > 0L) lastRev = rev
                    _state.update { it.copy(screen = s, loading = false) }
                }
        }
    }

    private fun open() {
        opening = true
        viewModelScope.launch {
            try {
                val screen = withContext(background) {
                    relay.call(Method.TERM_OPEN, machineId, SessionIdRequest(sessionId), SessionIdRequest.serializer(), TermScreen.serializer())
                }
                if (!screen.subId.isNullOrEmpty()) subId = screen.subId
                val rev = screen.rev ?: 0L
                val fresh = rev == 0L || rev >= lastRev
                if (rev > 0L && fresh) lastRev = rev
                opened = true
                _state.update { it.copy(screen = if (fresh || it.screen == null) screen else it.screen, loading = false, error = null) }
            } catch (e: RemoteException) {
                val transient = e.code == ErrorCode.MACHINE_OFFLINE || e.code == ErrorCode.MACHINE_BUSY || e.code == ErrorCode.TIMEOUT
                if (!transient) {
                    opened = true
                    _state.update { it.copy(loading = false, error = e.message ?: "打不开终端") }
                } else {
                    _state.update { it.copy(loading = false) }
                }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                // Network-like failures leave [opened] false so reconnecting opens again.
                _state.update { it.copy(loading = false) }
            } finally {
                opening = false
            }
        }
    }

    /** Presses one key of the key bar; keys go out one by one in the order tapped. */
    fun key(key: String) {
        if (_state.value.ended || !_state.value.online) return
        keys.trySend(key)
    }

    override fun onCleared() {
        val closeSubId = subId
        appScope.launch {
            runCatching { relay.call(Method.TERM_CLOSE, machineId, TermCloseRequest(sessionId, closeSubId), TermCloseRequest.serializer(), Empty.serializer()) }
        }
    }
}
