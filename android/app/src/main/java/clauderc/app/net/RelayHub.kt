package clauderc.app.net

import clauderc.app.data.Credential
import clauderc.app.protocol.Empty
import clauderc.app.protocol.Envelope
import clauderc.app.protocol.Machine
import clauderc.app.protocol.MachinesListResponse
import clauderc.app.protocol.Method
import clauderc.app.protocol.ProtocolJson
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/** One paired relay as the machine page lists it. */
data class RelayLink(val relay: String, val host: String, val state: ConnectionState)

/**
 * Every relay this phone is paired with, one [RelayLine] each. A request goes to
 * the relay that listed its machine; machines.list asks all of them and merges the
 * answers, keeping the last list of a relay that is reconnecting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayHub(
    private val scope: CoroutineScope,
    /** Opens the connection for a credential, or returns null when its address is unusable. */
    private val open: (Credential) -> RelayLine?,
) : Relay, ForegroundAware {
    private class Entry(val credential: Credential, val host: String, val line: RelayLine, val job: Job)

    private val lock = Any()
    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    private var foreground = false

    /** Machine id to the relay that listed it. */
    private val owners = ConcurrentHashMap<String, String>()

    /** The last machines.list answer of each relay. */
    private val lists = ConcurrentHashMap<String, List<Machine>>()

    private val _events = MutableSharedFlow<Envelope>(extraBufferCapacity = 1024)
    override val events: SharedFlow<Envelope> = _events.asSharedFlow()

    private val _changes = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 16)
    override val changes: Flow<Unit> = _changes.asSharedFlow()

    private val _revoked = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Relay keys whose device token was refused; the app drops those credentials. */
    val revoked: SharedFlow<String> = _revoked.asSharedFlow()

    val links: StateFlow<List<RelayLink>> = entries
        .flatMapLatest { map ->
            if (map.isEmpty()) flowOf(emptyList())
            else combine(map.values.map { e -> e.line.state.map { RelayLink(e.credential.relay, e.host, it) } }) { it.toList() }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val state: StateFlow<ConnectionState> = links.map(::overall).stateIn(scope, SharingStarted.Eagerly, ConnectionState.Idle)

    /** Opens lines for new credentials and closes those whose credential is gone. */
    fun setCredentials(list: List<Credential>) {
        val removed = mutableListOf<Entry>()
        synchronized(lock) {
            val old = entries.value
            val next = LinkedHashMap<String, Entry>()
            for (c in list) {
                val kept = old[c.relay]?.takeIf { it.credential == c }
                next[c.relay] = kept ?: start(c) ?: continue
            }
            old.forEach { (key, e) -> if (next[key] !== e) removed += e }
            entries.value = next
        }
        for (e in removed) {
            e.job.cancel()
            e.line.close()
            lists.remove(e.credential.relay)
            owners.values.removeAll { it == e.credential.relay }
        }
        _changes.tryEmit(Unit)
    }

    private fun start(c: Credential): Entry? {
        val line = open(c) ?: return null
        val host = runCatching { RelayAddress.parse(c.relay).host }.getOrDefault(c.relay)
        val job = scope.launch {
            launch {
                line.state.collect { s ->
                    when (s) {
                        ConnectionState.Connected -> _changes.emit(Unit)
                        ConnectionState.Revoked -> _revoked.emit(c.relay)
                        else -> Unit
                    }
                }
            }
            line.events.collect { env ->
                track(c.relay, env)
                _events.emit(env)
            }
        }
        line.setForeground(foreground)
        return Entry(c, host, line, job)
    }

    /** Learns which relay a machine belongs to from the events it sends. */
    private fun track(relay: String, env: Envelope) {
        when (env.method) {
            Method.MACHINE_STATUS -> {
                val m = env.body?.let { runCatching { ProtocolJson.decodeFromJsonElement(Machine.serializer(), it) }.getOrNull() } ?: return
                owners[m.id] = relay
                lists[relay] = lists[relay].orEmpty().filter { it.id != m.id } + m
            }
            Method.SESSIONS_CHANGED -> env.machine?.let { owners[it] = relay }
        }
    }

    override fun setForeground(value: Boolean) {
        val lines = synchronized(lock) {
            foreground = value
            entries.value.values.map { it.line }
        }
        lines.forEach { it.setForeground(value) }
    }

    override fun stateOf(machine: String): Flow<ConnectionState> = links
        .map { list -> owners[machine]?.let { r -> list.firstOrNull { it.relay == r }?.state } ?: overall(list) }
        .distinctUntilChanged()

    override suspend fun request(method: String, machine: String?, body: JsonElement?, timeoutMillis: Long?): JsonElement {
        if (machine == null && method == Method.MACHINES_LIST) return listAll()
        val map = entries.value
        val entry = machine?.let { owners[it] }?.let { map[it] } ?: map.values.singleOrNull() ?: throw NotConnectedException()
        return entry.line.request(method, machine, body, timeoutMillis)
    }

    /** Asks every relay for its machines; a relay that cannot answer keeps its last list. */
    private suspend fun listAll(): JsonElement {
        val map = entries.value
        val answered = coroutineScope {
            map.values.map { e ->
                async {
                    val res = runCatching {
                        e.line.call(Method.MACHINES_LIST, null, Empty(), Empty.serializer(), MachinesListResponse.serializer())
                    }.getOrNull() ?: return@async false
                    lists[e.credential.relay] = res.machines
                    res.machines.forEach { owners[it.id] = e.credential.relay }
                    true
                }
            }.awaitAll()
        }
        if (map.isNotEmpty() && answered.none { it } && map.keys.none { lists.containsKey(it) }) throw NotConnectedException()
        val merged = map.keys.flatMap { lists[it].orEmpty() }
        return ProtocolJson.encodeToJsonElement(MachinesListResponse.serializer(), MachinesListResponse(merged))
    }

    private companion object {
        /** Connected when any relay is; otherwise the most hopeful state of the rest. */
        fun overall(list: List<RelayLink>): ConnectionState = when {
            list.isEmpty() -> ConnectionState.Idle
            list.any { it.state == ConnectionState.Connected } -> ConnectionState.Connected
            else -> list.firstOrNull { it.state == ConnectionState.Connecting }?.state
                ?: list.firstOrNull { it.state is ConnectionState.Waiting }?.state
                ?: list.first().state
        }
    }
}
