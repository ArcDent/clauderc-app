package clauderc.app.testing

import clauderc.app.net.ConnectionState
import clauderc.app.net.RelayLine
import clauderc.app.net.RemoteException
import clauderc.app.protocol.Envelope
import clauderc.app.protocol.Kind
import clauderc.app.protocol.ProtocolJson
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A scripted relay: answers by method name and records every request. */
class FakeRelay(initial: ConnectionState = ConnectionState.Connected) : RelayLine {
    data class Sent(val method: String, val machine: String?, val body: JsonElement?, val timeoutMillis: Long? = null)

    override val state = MutableStateFlow(initial)
    override val events = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)

    val requests = CopyOnWriteArrayList<Sent>()
    private val handlers = mutableMapOf<String, suspend (Sent) -> JsonElement>()

    /** Answers [method] with [handler], which may suspend to keep the request outstanding. */
    fun on(method: String, handler: suspend (Sent) -> JsonElement) {
        handlers[method] = handler
    }

    fun <T> respond(method: String, serializer: KSerializer<T>, value: T) =
        on(method) { ProtocolJson.encodeToJsonElement(serializer, value) }

    fun fail(method: String, code: String, message: String = code) =
        on(method) { throw RemoteException(code, message) }

    fun sent(method: String) = requests.filter { it.method == method }

    var foreground: Boolean? = null
    var closed = false

    override fun setForeground(value: Boolean) {
        foreground = value
    }

    override fun close() {
        closed = true
    }

    fun <T> event(method: String, machine: String?, serializer: KSerializer<T>, value: T) {
        check(events.tryEmit(Envelope(kind = Kind.EVENT, method = method, machine = machine, body = ProtocolJson.encodeToJsonElement(serializer, value))))
    }

    override suspend fun request(method: String, machine: String?, body: JsonElement?, timeoutMillis: Long?): JsonElement {
        val sent = Sent(method, machine, body, timeoutMillis)
        requests += sent
        return handlers[method]?.invoke(sent) ?: JsonObject(emptyMap())
    }
}

/** Decodes a recorded request body. */
fun <T> FakeRelay.Sent.decode(serializer: KSerializer<T>): T = ProtocolJson.decodeFromJsonElement(serializer, body!!)
