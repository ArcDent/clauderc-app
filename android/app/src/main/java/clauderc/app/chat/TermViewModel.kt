package clauderc.app.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import clauderc.app.data.Fleet
import clauderc.app.net.ConnectionState
import clauderc.app.net.Relay
import clauderc.app.net.RemoteException
import clauderc.app.net.call
import clauderc.app.protocol.Empty
import clauderc.app.protocol.Method
import clauderc.app.protocol.ProtocolJson
import clauderc.app.protocol.Session
import clauderc.app.protocol.SessionIdRequest
import clauderc.app.protocol.SessionState
import clauderc.app.protocol.TermKeyRequest
import clauderc.app.protocol.TermScreen
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
) : ViewModel() {
    private val _state = MutableStateFlow(TermUiState())
    val state: StateFlow<TermUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var opened = false
    private var opening = false

    init {
        viewModelScope.launch {
            combine(relay.stateOf(machineId), fleet.machines, fleet.sessions) { conn, machines, sessions ->
                val machine = machines?.firstOrNull { it.id == machineId }
                Triple(conn == ConnectionState.Connected && machine?.online != false, sessions[machineId]?.sessions?.firstOrNull { it.id == sessionId }, conn)
            }.collect { (online, session, _) ->
                val reopen = online && !_state.value.online
                _state.update { it.copy(session = session ?: it.session, online = online) }
                if (reopen || (online && !opened && !opening)) open()
            }
        }
        viewModelScope.launch {
            relay.events
                .filter { it.machine == machineId && it.method == Method.TERM_SCREEN && it.body != null }
                .map { e -> runCatching { ProtocolJson.decodeFromJsonElement(TermScreen.serializer(), e.body!!) }.getOrNull()?.takeIf { it.sessionId == sessionId } }
                .flowOn(background)
                .collect { s -> if (s != null) _state.update { it.copy(screen = s, loading = false) } }
        }
    }

    private fun open() {
        opening = true
        viewModelScope.launch {
            try {
                val screen = withContext(background) {
                    relay.call(Method.TERM_OPEN, machineId, SessionIdRequest(sessionId), SessionIdRequest.serializer(), TermScreen.serializer())
                }
                _state.update { it.copy(screen = screen, loading = false, error = null) }
            } catch (e: RemoteException) {
                _state.update { it.copy(loading = false, error = e.message ?: "打不开终端") }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false) }
            } finally {
                opening = false
                opened = true
            }
        }
    }

    /** Presses one key of the key bar; a refused key is reported. */
    fun key(key: String) {
        if (_state.value.ended || !_state.value.online) return
        viewModelScope.launch {
            try {
                relay.call(Method.TERM_KEY, machineId, TermKeyRequest(sessionId, key), TermKeyRequest.serializer(), Empty.serializer())
            } catch (e: Exception) {
                _messages.send((e as? RemoteException)?.message ?: "按键没有发出")
            }
        }
    }

    override fun onCleared() {
        appScope.launch {
            runCatching { relay.call(Method.TERM_CLOSE, machineId, SessionIdRequest(sessionId), SessionIdRequest.serializer(), Empty.serializer()) }
        }
    }
}
