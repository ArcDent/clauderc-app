package clauderc.app.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import clauderc.app.data.Fleet
import clauderc.app.data.ProfileNameStore
import clauderc.app.net.Relay
import clauderc.app.net.RemoteException
import clauderc.app.net.call
import clauderc.app.protocol.Empty
import clauderc.app.protocol.Machine
import clauderc.app.protocol.Method
import clauderc.app.protocol.Profile
import clauderc.app.protocol.ProfileKind
import clauderc.app.protocol.Session
import clauderc.app.protocol.SessionIdRequest
import clauderc.app.protocol.SessionList
import clauderc.app.protocol.SessionState
import clauderc.app.ui.profileTitle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionGroup(
    val profile: Profile,
    val title: String,
    /** True when the title is the raw API host, shown in mono. */
    val titleIsHost: Boolean,
    /** The host shown after a custom name, or the reason for the 「其他」 group. */
    val note: String?,
    val sessions: List<Session>,
    /** Folded on the session page: only the header shows, with the counts. */
    val collapsed: Boolean = false,
) {
    val canCreate get() = profile.kind != ProfileKind.Other
    val canRename get() = profile.kind != ProfileKind.Other
    val pending get() = sessions.count { it.state == SessionState.Pending }
    /** The most recent session, whose launch flags a new session reuses (design D8). */
    val template get() = sessions.maxByOrNull { it.lastActiveAt }
}

/** Groups sessions by profile in the agent's order, pending first, then most recent first. */
fun groupSessions(list: SessionList, names: Map<String, String>): List<SessionGroup> {
    val profiles = list.profiles.toMutableList()
    val known = profiles.map { it.id }.toSet()
    // Sessions that point at an unknown profile still need a home: the 「其他」 group.
    val other = profiles.firstOrNull { it.kind == ProfileKind.Other } ?: Profile("other", ProfileKind.Other)
    if (list.sessions.any { it.profileId !in known } && other !in profiles) profiles += other
    val ordered = profiles.filter { it.kind != ProfileKind.Other } + profiles.filter { it.kind == ProfileKind.Other }
    return ordered.map { p ->
        val members = list.sessions.filter { s -> s.profileId == p.id || (p == other && s.profileId !in known) }
        val custom = names[p.id]
        SessionGroup(
            profile = p,
            title = profileTitle(p, custom),
            titleIsHost = custom == null && p.kind == ProfileKind.Endpoint,
            note = when {
                p.kind == ProfileKind.Other -> "读不到启动参数"
                custom != null && p.kind == ProfileKind.Endpoint -> p.id
                else -> null
            },
            sessions = members.sortedWith(
                compareByDescending<Session> { it.state == SessionState.Pending }.thenByDescending { it.lastActiveAt },
            ),
        )
    }
}

data class SessionsUiState(
    val machine: Machine? = null,
    /** Null until the machine's first session list arrives. */
    val groups: List<SessionGroup>? = null,
    val refreshing: Boolean = false,
)

class SessionsViewModel(
    private val machineId: String,
    private val fleet: Fleet,
    private val relay: Relay,
    private val names: ProfileNameStore,
) : ViewModel() {
    private val refreshing = MutableStateFlow(false)
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Stops and removals already shown while the agent is still on them; null means removed. */
    private val pendingActs = MutableStateFlow<Map<String, SessionState?>>(emptyMap())

    private val groupSettings = combine(names.names(machineId), names.collapsed(machineId)) { custom, folded -> custom to folded }

    val state: StateFlow<SessionsUiState> = combine(fleet.machines, fleet.sessions, groupSettings, refreshing, pendingActs) { machines, sessions, (custom, folded), busy, acts ->
        SessionsUiState(
            machine = machines?.firstOrNull { it.id == machineId },
            groups = sessions[machineId]?.let { list -> groupSessions(list.withActs(acts), custom).map { it.copy(collapsed = it.profile.id in folded) } },
            refreshing = busy,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionsUiState())

    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            if (!fleet.refresh(machineId)) _messages.send("刷新失败，检查目标机是否在线")
            refreshing.value = false
        }
    }

    /** Folds or unfolds a group; the choice is kept on the phone. */
    fun toggle(group: SessionGroup) {
        viewModelScope.launch { names.setCollapsed(machineId, group.profile.id, !group.collapsed) }
    }

    fun rename(profileId: String, name: String?) {
        viewModelScope.launch { names.rename(machineId, profileId, name) }
    }

    fun stop(session: Session) = act(Method.SESSION_STOP, session, SessionState.Stopped, "已停止「${display(session)}」")

    fun remove(session: Session) = act(Method.SESSION_REMOVE, session, null, null)

    /** Shows the result at once ([shown] null hides the row) and takes it back if the agent refuses. */
    private fun act(method: String, session: Session, shown: SessionState?, done: String?) {
        pendingActs.update { it + (session.id to shown) }
        viewModelScope.launch {
            try {
                relay.call(method, machineId, SessionIdRequest(session.id), SessionIdRequest.serializer(), Empty.serializer())
                fleet.refresh(machineId)
                done?.let { _messages.send(it) }
            } catch (e: RemoteException) {
                _messages.send(e.message ?: "操作失败")
            } catch (e: Exception) {
                _messages.send("操作失败：${e.message}")
            } finally {
                pendingActs.update { it - session.id }
            }
        }
    }
}

/** The list as it will be once the pending stops and removals are done. */
private fun SessionList.withActs(acts: Map<String, SessionState?>): SessionList =
    if (acts.isEmpty()) this
    else copy(sessions = sessions.mapNotNull { s -> if (s.id in acts) acts[s.id]?.let { s.copy(state = it) } else s })

fun display(s: Session): String = s.name.ifBlank { clauderc.app.ui.baseName(s.cwd).ifBlank { s.id } }
