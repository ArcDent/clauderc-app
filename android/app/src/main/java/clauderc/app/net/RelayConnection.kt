package clauderc.app.net

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import clauderc.app.protocol.CloseCode
import clauderc.app.protocol.Envelope
import clauderc.app.protocol.Kind
import clauderc.app.protocol.ProtocolJson
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface ConnectionState {
    /** No credential, or the app is in the background. */
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState

    /** Disconnected; the next attempt starts at [retryAtMillis]. */
    data class Waiting(val attempt: Int, val retryAtMillis: Long) : ConnectionState

    /** The relay refused the device token or closed with 1008; pair again. */
    data object Revoked : ConnectionState
}

/** A response that carried an error body. */
class RemoteException(val code: String, message: String) : Exception(message)

class NotConnectedException : IOException("没有连上中转服务器")

/** What view models need from the relay connection; tests use a fake. */
interface Relay {
    val state: StateFlow<ConnectionState>
    val events: SharedFlow<Envelope>

    /** Sends a request and returns the response body, or throws [RemoteException]. */
    suspend fun request(method: String, machine: String?, body: JsonElement? = null): JsonElement

    /** The state of the relay that reaches [machine]; with a single relay that is [state]. */
    fun stateOf(machine: String): Flow<ConnectionState> = state

    /** Ticks when machine lists should be reloaded: a relay (re)connected or the set of relays changed. */
    val changes: Flow<Unit> get() = state.filter { it == ConnectionState.Connected }.map { }
}

/** Something that is connected only while the app is in the foreground. */
interface ForegroundAware {
    fun setForeground(value: Boolean)
}

/** One relay connection as [RelayHub] drives it. */
interface RelayLine : Relay, ForegroundAware {
    /** Closes the connection for good. */
    fun close()
}

suspend fun <Req, Res> Relay.call(
    method: String,
    machine: String?,
    request: Req,
    requestSerializer: SerializationStrategy<Req>,
    responseSerializer: DeserializationStrategy<Res>,
): Res {
    val body = request(method, machine, ProtocolJson.encodeToJsonElement(requestSerializer, request))
    return ProtocolJson.decodeFromJsonElement(responseSerializer, body)
}

/**
 * The phone's WebSocket to /ws/app (design D2). It is open only while the app
 * has a credential and is in the foreground, reconnects with growing delays,
 * and pairs each response with its request by id.
 */
class RelayConnection(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val reconnectDelaysMillis: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000),
    private val requestTimeoutMillis: Long = 30_000,
    private val now: () -> Long = System::currentTimeMillis,
) : RelayLine {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Envelope>(extraBufferCapacity = 1024)
    override val events: SharedFlow<Envelope> = _events.asSharedFlow()

    private class Credential(val address: RelayAddress, val token: String)

    private val lock = Any()
    private var credential: Credential? = null
    private var foreground = false
    private var socket: WebSocket? = null
    private var generation = 0
    private var attempt = 0
    private var retryJob: Job? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Envelope>>()
    private val ids = AtomicLong()

    /** Sets or clears the device credential; a new credential also clears [ConnectionState.Revoked]. */
    fun setCredential(address: RelayAddress?, token: String?) = synchronized(lock) {
        credential = if (address != null && token != null) Credential(address, token) else null
        closeLocked()
        attempt = 0
        _state.value = ConnectionState.Idle
        reconcileLocked()
    }

    override fun close() = setCredential(null, null)

    override fun setForeground(value: Boolean) = synchronized(lock) {
        if (foreground == value) return@synchronized
        foreground = value
        if (!value) {
            closeLocked()
            attempt = 0
            if (_state.value != ConnectionState.Revoked) _state.value = ConnectionState.Idle
        }
        reconcileLocked()
    }

    private fun reconcileLocked() {
        val cred = credential ?: return
        if (!foreground || _state.value == ConnectionState.Revoked) return
        if (socket != null || retryJob != null) return
        val gen = ++generation
        _state.value = ConnectionState.Connecting
        val request = Request.Builder()
            .url(cred.address.appSocketUrl)
            .header("Authorization", "Bearer ${cred.token}")
            .build()
        socket = client.newWebSocket(request, Listener(gen))
    }

    private fun closeLocked() {
        generation++
        retryJob?.cancel()
        retryJob = null
        socket?.close(1000, null)
        socket = null
        failPending()
    }

    private fun failPending() {
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(NotConnectedException()) }
    }

    private fun onDropped(dropped: WebSocket, revoked: Boolean) = synchronized(lock) {
        // onClosing, onClosed and onFailure may all fire; only the first one for the live socket counts.
        if (dropped !== socket) return@synchronized
        socket = null
        val gen = generation
        failPending()
        if (revoked) {
            _state.value = ConnectionState.Revoked
            return@synchronized
        }
        if (credential == null || !foreground) {
            _state.value = ConnectionState.Idle
            return@synchronized
        }
        val wait = reconnectDelaysMillis[attempt.coerceAtMost(reconnectDelaysMillis.lastIndex)]
        attempt++
        _state.value = ConnectionState.Waiting(attempt, now() + wait)
        retryJob = scope.launch {
            delay(wait)
            synchronized(lock) {
                if (gen != generation) return@synchronized
                retryJob = null
                reconcileLocked()
            }
        }
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
            if (gen != generation) return@synchronized
            attempt = 0
            _state.value = ConnectionState.Connected
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val env = runCatching { Envelope.decode(text) }.getOrNull() ?: return
            when (env.kind) {
                Kind.RESPONSE -> env.id?.let { pending.remove(it) }?.complete(env)
                Kind.EVENT -> _events.tryEmit(env)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            onDropped(webSocket, revoked = code == CloseCode.REVOKED)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
            onDropped(webSocket, revoked = code == CloseCode.REVOKED)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            onDropped(webSocket, revoked = response?.code == 401)
    }

    override suspend fun request(method: String, machine: String?, body: JsonElement?): JsonElement {
        val id = "a-${ids.incrementAndGet()}"
        val deferred = CompletableDeferred<Envelope>()
        val ws = synchronized(lock) {
            if (_state.value != ConnectionState.Connected) throw NotConnectedException()
            pending[id] = deferred
            socket ?: throw NotConnectedException()
        }
        val env = Envelope(kind = Kind.REQUEST, id = id, method = method, machine = machine, body = body)
        if (!ws.send(env.encode())) {
            pending.remove(id)
            throw NotConnectedException()
        }
        val response = try {
            withTimeout(requestTimeoutMillis) { deferred.await() }
        } finally {
            pending.remove(id)
        }
        response.error?.let { throw RemoteException(it.code, it.message) }
        return response.body ?: JsonNull
    }
}

/** Connects while the app is visible and disconnects when it goes to the background (design D10). */
class ForegroundBinder(private val target: ForegroundAware) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) = target.setForeground(true)
    override fun onStop(owner: LifecycleOwner) = target.setForeground(false)
}
