package clauderc.app.net

import clauderc.app.data.Credential
import clauderc.app.data.updated
import clauderc.app.protocol.Empty
import clauderc.app.protocol.Envelope
import clauderc.app.protocol.ErrorCode
import clauderc.app.protocol.Machine
import clauderc.app.protocol.MachinesListResponse
import clauderc.app.protocol.Method
import clauderc.app.protocol.ProtocolJson
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.flow.update
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
    private class InflightRead(val relay: String, val machine: String, val failed: CompletableDeferred<Nothing>)

    private val lock = Any()
    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    private val inflightReads = mutableListOf<InflightRead>()
    private var foreground = false

    /** Machine id to the relay that listed it. */
    private val owners = ConcurrentHashMap<String, String>()

    /** Bumps whenever [owners] changes, so [stateOf] re-evaluates once a machine's relay is known. */
    private val ownerVersion = MutableStateFlow(0L)

    /** The last machines.list answer of each relay. */
    private val lists = ConcurrentHashMap<String, List<Machine>>()

    /**
     * For each machines.list on its way, its relay and the machine.status events that
     * relay sent meanwhile, laid over the answer as in [clauderc.app.data.Fleet]. Guarded by [lock].
     */
    private val arriving = mutableListOf<Pair<String, MutableList<Machine>>>()

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
        var ownersChanged = false
        synchronized(lock) {
            val old = entries.value
            val next = LinkedHashMap<String, Entry>()
            for (c in list) {
                val kept = old[c.relay]?.takeIf { it.credential == c }
                next[c.relay] = kept ?: start(c) ?: continue
            }
            old.forEach { (key, e) -> if (next[key] !== e) removed += e }
            entries.value = next
            for (e in removed) {
                if (next[e.credential.relay] == null) {
                    lists.remove(e.credential.relay)
                    if (owners.values.removeAll { it == e.credential.relay }) ownersChanged = true
                }
            }
        }
        for (e in removed) {
            e.job.cancel()
            e.line.close()
        }
        if (ownersChanged) ownerVersion.update { it + 1 }
        _changes.tryEmit(Unit)
    }

    private fun start(c: Credential): Entry? {
        val line = open(c) ?: return null
        val host = runCatching { RelayAddress.parse(c.relay).host }.getOrDefault(c.relay)
        lateinit var entry: Entry
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
                if (!track(entry, env)) return@collect
                _events.emit(env)
            }
        }
        entry = Entry(c, host, line, job)
        line.setForeground(foreground)
        return entry
    }

    /** Learns which relay a machine belongs to from the events it sends; returns false if [entry] was removed. */
    private fun track(entry: Entry, env: Envelope): Boolean {
        val relay = entry.credential.relay
        var ownersChanged = false
        val toFail = mutableListOf<CompletableDeferred<Nothing>>()
        synchronized(lock) {
            if (entries.value[relay] !== entry) return false
            when (env.method) {
                Method.MACHINE_STATUS -> {
                    val m = env.body?.let { runCatching { ProtocolJson.decodeFromJsonElement(Machine.serializer(), it) }.getOrNull() } ?: return true
                    if (owners.put(m.id, relay) != relay) ownersChanged = true
                    lists[relay] = lists[relay].orEmpty().updated(m)
                    arriving.forEach { (r, seen) -> if (r == relay) seen += m }
                    if (!m.online) {
                        val it = inflightReads.iterator()
                        while (it.hasNext()) {
                            val r = it.next()
                            if (r.relay == relay && r.machine == m.id) {
                                toFail += r.failed
                                it.remove()
                            }
                        }
                    }
                }
                Method.SESSIONS_CHANGED -> env.machine?.let {
                    if (owners.put(it, relay) != relay) ownersChanged = true
                }
            }
        }
        if (toFail.isNotEmpty()) {
            val offline = RemoteException(ErrorCode.MACHINE_OFFLINE, "目标机离线")
            toFail.forEach { it.completeExceptionally(offline) }
        }
        if (ownersChanged) ownerVersion.update { it + 1 }
        return true
    }

    override fun setForeground(value: Boolean) {
        val lines = synchronized(lock) {
            foreground = value
            entries.value.values.map { it.line }
        }
        lines.forEach { it.setForeground(value) }
    }

    /** Proactively closes and redials every live relay connection after a system network switch. */
    fun onNetworkChanged() {
        val lines = synchronized(lock) { entries.value.values.map { it.line } }
        lines.forEach { it.onNetworkChanged() }
    }

    override fun stateOf(machine: String): Flow<ConnectionState> = combine(links, ownerVersion) { list, _ ->
        val owner = owners[machine]
        when {
            list.isEmpty() -> ConnectionState.Idle
            owner != null -> list.firstOrNull { it.relay == owner }?.state ?: ConnectionState.Connecting
            list.size == 1 -> list[0].state
            // More than one relay is paired and this machine's relay is not known yet: wait for machines.list.
            else -> if (list.any { it.state == ConnectionState.Connected || it.state == ConnectionState.Connecting }) ConnectionState.Connecting else overall(list)
        }
    }.distinctUntilChanged()

    override suspend fun request(method: String, machine: String?, body: JsonElement?, timeoutMillis: Long?): JsonElement {
        if (machine == null && method == Method.MACHINES_LIST) return listAll()
        val map = entries.value
        val entry = machine?.let { owners[it] }?.let { map[it] } ?: map.values.singleOrNull() ?: throw NotConnectedException()
        if (machine == null || !Method.isReadOnly(method)) {
            return entry.line.request(method, machine, body, timeoutMillis)
        }
        val failed = CompletableDeferred<Nothing>()
        val inflight = InflightRead(entry.credential.relay, machine, failed)
        synchronized(lock) { inflightReads += inflight }
        return try {
            coroutineScope {
                val call = async { entry.line.request(method, machine, body, timeoutMillis) }
                val watcher = launch {
                    try {
                        failed.await()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    call.await()
                } finally {
                    watcher.cancel()
                }
            }
        } catch (e: CancellationException) {
            if (failed.isCompleted) failed.getCompletionExceptionOrNull()?.let { throw it }
            throw e
        } finally {
            synchronized(lock) { inflightReads.remove(inflight) }
        }
    }

    /** Asks every relay for its machines; a relay that cannot answer keeps its last list. */
    private suspend fun listAll(): JsonElement {
        val map = entries.value
        val answered = coroutineScope {
            map.values.map { e ->
                async {
                    val window = e.credential.relay to mutableListOf<Machine>()
                    synchronized(lock) { arriving += window }
                    val res = runCatching {
                        e.line.call(Method.MACHINES_LIST, null, Empty(), Empty.serializer(), MachinesListResponse.serializer())
                    }.getOrNull()
                    var changed = false
                    var kept = false
                    synchronized(lock) {
                        arriving.removeAll { it === window }
                        if (res != null && entries.value[e.credential.relay] === e) {
                            kept = true
                            val merged = window.second.fold(res.machines, List<Machine>::updated)
                            lists[e.credential.relay] = merged
                            merged.forEach { if (owners.put(it.id, e.credential.relay) != e.credential.relay) changed = true }
                        }
                    }
                    if (changed) ownerVersion.update { it + 1 }
                    kept
                }
            }.awaitAll()
        }
        val live = synchronized(lock) {
            val cur = entries.value
            cur.keys to cur.keys.flatMap { lists[it].orEmpty() }
        }
        if (live.first.isNotEmpty() && answered.none { it } && live.first.none { lists.containsKey(it) }) throw NotConnectedException()
        return ProtocolJson.encodeToJsonElement(MachinesListResponse.serializer(), MachinesListResponse(live.second))
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
