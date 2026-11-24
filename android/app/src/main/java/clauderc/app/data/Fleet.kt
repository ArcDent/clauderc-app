package clauderc.app.data

import clauderc.app.net.Relay
import clauderc.app.net.call
import clauderc.app.protocol.Empty
import clauderc.app.protocol.Envelope
import clauderc.app.protocol.Machine
import clauderc.app.protocol.MachinesListResponse
import clauderc.app.protocol.Method
import clauderc.app.protocol.ProtocolJson
import clauderc.app.protocol.SessionList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Machines and their session lists, shared by every screen. While connected it
 * keeps a sessions.subscribe open on each online machine, so the machine list
 * can show counts and the nav bar can mark pending sessions.
 */
class Fleet(private val relay: Relay, private val scope: CoroutineScope) {
    private val _machines = MutableStateFlow<List<Machine>?>(null)
    /** Null until the first machines.list answer arrives. */
    val machines: StateFlow<List<Machine>?> = _machines.asStateFlow()

    private val _machinesFailed = MutableStateFlow(false)
    /** True when the first machines.list failed and no machine list has arrived yet. */
    val machinesFailed: StateFlow<Boolean> = _machinesFailed.asStateFlow()

    private val _sessions = MutableStateFlow<Map<String, SessionList>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionList>> = _sessions.asStateFlow()

    private val reloads = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val lock = Any()

    /** Highest SessionList.rev applied per machine; reset when a machine reconnects. Guarded by [lock]. */
    private val sessionRevs = HashMap<String, Long>()

    /**
     * The machine.status events that came while machines.list was on its way, or
     * null when none is. They are laid over its answer: the relay may have built
     * the answer before them, and answers and events reach here on different threads.
     */
    private var arrived: MutableList<Machine>? = null

    init {
        // A relay (re)connected, one was added or removed, or the user tapped retry: list the machines again.
        // The collector runs loadMachines one at a time.
        scope.launch { merge(relay.changes, reloads).collect { loadMachines() } }
        scope.launch { relay.events.collect(::onEvent) }
    }

    /** Asks for machines.list again, e.g. from the retry button after the first fetch failed. */
    fun retryMachines() {
        reloads.tryEmit(Unit)
    }

    private suspend fun loadMachines() {
        _machinesFailed.value = false
        synchronized(lock) { arrived = mutableListOf() }
        val list = runCatching {
            relay.call(Method.MACHINES_LIST, null, Empty(), Empty.serializer(), MachinesListResponse.serializer())
        }.getOrNull()
        val machines = synchronized(lock) {
            val seen = arrived.orEmpty()
            arrived = null
            if (list == null) {
                if (_machines.value == null) _machinesFailed.value = true
                return
            }
            _machinesFailed.value = false
            val prevById = _machines.value.orEmpty().associateBy { it.id }
            val merged = seen.fold(list.machines, List<Machine>::updated)
            _machines.value = merged
            val known = merged.mapTo(HashSet()) { it.id }
            sessionRevs.keys.retainAll(known)
            for (m in merged) {
                val prev = prevById[m.id]
                val connChanged = m.connId != null && prev?.connId != null && m.connId != prev.connId
                if (!m.online || (prev != null && !prev.online && m.online) || connChanged) {
                    sessionRevs.remove(m.id)
                }
            }
            _sessions.update { cur -> cur.filterKeys { it in known } }
            merged
        }
        machines.filter { it.online }.forEach { m -> scope.launch { refresh(m.id) } }
    }

    private fun onEvent(env: Envelope) {
        val body = env.body ?: return
        when (env.method) {
            Method.MACHINE_STATUS -> {
                val m = runCatching { ProtocolJson.decodeFromJsonElement(Machine.serializer(), body) }.getOrNull() ?: return
                synchronized(lock) {
                    val prev = _machines.value?.firstOrNull { it.id == m.id }
                    arrived?.add(m)
                    _machinesFailed.value = false
                    _machines.update { it.orEmpty().updated(m) }
                    val connChanged = m.connId != null && prev?.connId != null && m.connId != prev.connId
                    if (!m.online || (prev != null && !prev.online && m.online) || connChanged) {
                        sessionRevs.remove(m.id)
                    }
                }
                // A machine that comes back lost its subscriptions together with its connection.
                if (m.online) scope.launch { refresh(m.id) }
            }
            Method.SESSIONS_CHANGED -> {
                val machine = env.machine ?: return
                val list = runCatching { ProtocolJson.decodeFromJsonElement(SessionList.serializer(), body) }.getOrNull() ?: return
                synchronized(lock) { applySessionsLocked(machine, list) }
            }
        }
    }

    /** Subscribes (again) and replaces the cached list; false when the request failed. */
    suspend fun refresh(machineId: String): Boolean {
        val list = runCatching {
            relay.call(Method.SESSIONS_SUBSCRIBE, machineId, Empty(), Empty.serializer(), SessionList.serializer())
        }.getOrNull() ?: return false
        synchronized(lock) {
            val known = _machines.value
            if (known != null && known.none { it.id == machineId }) return false
            applySessionsLocked(machineId, list)
        }
        return true
    }

    private fun applySessionsLocked(machineId: String, list: SessionList) {
        val known = _machines.value
        if (known != null && known.none { it.id == machineId }) return
        val rev = list.rev
        val cur = sessionRevs[machineId] ?: 0L
        if (rev != null && rev > 0L) {
            if (rev < cur) return
            sessionRevs[machineId] = rev
        }
        _sessions.update { it + (machineId to list) }
    }

    fun machine(id: String): Machine? = _machines.value?.firstOrNull { it.id == id }
}

/** This list with [m] in place of the machine with its id, or added at the end. */
internal fun List<Machine>.updated(m: Machine): List<Machine> =
    if (any { it.id == m.id }) map { if (it.id == m.id) m else it } else this + m
