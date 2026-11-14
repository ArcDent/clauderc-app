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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _sessions = MutableStateFlow<Map<String, SessionList>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionList>> = _sessions.asStateFlow()

    init {
        // A relay (re)connected, or one was added or removed: list the machines again.
        scope.launch { relay.changes.collect { loadMachines() } }
        scope.launch { relay.events.collect(::onEvent) }
    }

    private suspend fun loadMachines() {
        val list = runCatching {
            relay.call(Method.MACHINES_LIST, null, Empty(), Empty.serializer(), MachinesListResponse.serializer())
        }.getOrNull() ?: return
        _machines.value = list.machines
        list.machines.filter { it.online }.forEach { m -> scope.launch { refresh(m.id) } }
    }

    private fun onEvent(env: Envelope) {
        val body = env.body ?: return
        when (env.method) {
            Method.MACHINE_STATUS -> {
                val m = runCatching { ProtocolJson.decodeFromJsonElement(Machine.serializer(), body) }.getOrNull() ?: return
                _machines.update { old ->
                    val list = old.orEmpty()
                    if (list.any { it.id == m.id }) list.map { if (it.id == m.id) m else it } else list + m
                }
                // A machine that comes back lost its subscriptions together with its connection.
                if (m.online) scope.launch { refresh(m.id) }
            }
            Method.SESSIONS_CHANGED -> {
                val machine = env.machine ?: return
                val list = runCatching { ProtocolJson.decodeFromJsonElement(SessionList.serializer(), body) }.getOrNull() ?: return
                _sessions.update { it + (machine to list) }
            }
        }
    }

    /** Subscribes (again) and replaces the cached list; false when the request failed. */
    suspend fun refresh(machineId: String): Boolean {
        val list = runCatching {
            relay.call(Method.SESSIONS_SUBSCRIBE, machineId, Empty(), Empty.serializer(), SessionList.serializer())
        }.getOrNull() ?: return false
        _sessions.update { it + (machineId to list) }
        return true
    }

    fun machine(id: String): Machine? = _machines.value?.firstOrNull { it.id == id }
}
