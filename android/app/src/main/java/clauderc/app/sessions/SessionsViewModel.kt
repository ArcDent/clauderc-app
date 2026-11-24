package clauderc.app.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import clauderc.app.data.DraftStore
import clauderc.app.data.Fleet
import clauderc.app.data.ProfileNameStore
import clauderc.app.git.GitOp
import clauderc.app.git.failureOf
import clauderc.app.net.Relay
import clauderc.app.net.RemoteException
import clauderc.app.net.call
import clauderc.app.protocol.DirBusyResponse
import clauderc.app.protocol.DirRequest
import clauderc.app.protocol.Empty
import clauderc.app.protocol.GitBranchesResponse
import clauderc.app.protocol.GitSwitchRequest
import clauderc.app.protocol.Machine
import clauderc.app.protocol.Method
import clauderc.app.protocol.Profile
import clauderc.app.protocol.ProfileKind
import clauderc.app.protocol.Session
import clauderc.app.protocol.SessionIdRequest
import clauderc.app.protocol.SessionList
import clauderc.app.protocol.SessionState
import clauderc.app.protocol.UsageResponse
import clauderc.app.ui.baseName
import clauderc.app.ui.profileTitle
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    /** Working directories folded inside the group. */
    val foldedDirs: Set<String> = emptySet(),
    /** Ids of the sessions whose forks are folded under them. */
    val foldedForks: Set<String> = emptySet(),
    /** Directory to the branch its rows are narrowed to; kept in memory only. */
    val branchFilters: Map<String, String> = emptyMap(),
    /** Directory to its branches as git.branches last answered. */
    val branches: Map<String, GitBranchesResponse> = emptyMap(),
) {
    /** The second level of the session page: the sessions by working directory. */
    val dirs: List<DirGroup> = dirGroups(sessions, foldedDirs, foldedForks, branchFilters, branches)

    val canCreate get() = profile.kind != ProfileKind.Other
    val canRename get() = profile.kind != ProfileKind.Other
    val pending get() = sessions.count { it.state == SessionState.Pending }
}

/** One working directory inside a profile group. */
data class DirGroup(
    /** The directory the sessions are listed under: for a session in a git worktree, the checkout it came from. */
    val cwd: String,
    /** The folder name, with its parent when another folder in the group has the same name. */
    val title: String,
    val sessions: List<Session>,
    val collapsed: Boolean = false,
    /** Ids of the sessions whose forks are folded under them. */
    val foldedForks: Set<String> = emptySet(),
    /** The branch the rows are narrowed to; null shows every branch. */
    val branchFilter: String? = null,
    /** The directory's branches as git.branches last answered; null before the first answer. */
    val branches: GitBranchesResponse? = null,
) {
    /** The sessions on [branchFilter], worktree sessions included; all of them without a filter. */
    val shown: List<Session> = if (branchFilter == null) sessions else sessions.filter { it.branch == branchFilter }

    val pending get() = shown.count { it.state == SessionState.Pending }

    /** The rows in order: each fork right under the session it came from. */
    val rows: List<SessionNode> = forkTree(shown, foldedForks)

    /**
     * The branch checked out in the directory: git's answer, else the branch of the latest session
     * working in the checkout itself. Null outside a git repository.
     */
    val currentBranch: String? = branches?.current?.takeIf { it.isNotEmpty() }
        ?: sessions.filter { it.worktree == null && it.branch != null }.maxByOrNull { it.lastActiveAt }?.branch

    /** What the title shows: the branch the rows are narrowed to, else the current one. */
    val branch: String? get() = branchFilter ?: currentBranch

    /** The sessions in the checkout itself are on more than one branch, so each row names its own. */
    val rowsShowBranch: Boolean = sessions.filter { it.worktree == null }.mapNotNull { it.branch }.toSet().size > 1

    /**
     * Branches sessions are on that git no longer lists, deleted since, in the order of the sessions. Empty
     * until git answered, and when it could not: then every branch would look deleted. A detached HEAD is no branch.
     */
    val goneBranches: List<String> = branches?.takeIf { it.current.isNotEmpty() || it.local.isNotEmpty() }
        ?.let { b -> sessions.mapNotNull { it.branch }.distinct().filter { it != "HEAD" && it !in b.local } }
        .orEmpty()
}

/** A row of a directory: a session and the forks hung under it, which fold away when [forksCollapsed]. */
data class SessionNode(val session: Session, val forks: List<Session> = emptyList(), val forksCollapsed: Boolean = false)

/** The directory a session is listed under: a worktree session's original checkout. */
fun listedDir(s: Session): String = s.dir ?: s.cwd

/**
 * Hangs each fork under the session it came from, keeping the order of [sessions] at both levels. A
 * fork of a fork goes under the first session of the chain; a fork whose source is not in [sessions]
 * is a row of its own.
 */
fun forkTree(sessions: List<Session>, folded: Set<String> = emptySet()): List<SessionNode> {
    val byId = sessions.associateBy { it.id }
    fun root(s: Session): Session {
        var r = s
        val seen = mutableSetOf(s.id)
        while (true) {
            val p = r.forkOf?.let(byId::get) ?: return r
            // A loop has no first session; each of its sessions stays a row of its own.
            if (!seen.add(p.id)) return s
            r = p
        }
    }
    val roots = sessions.associate { it.id to root(it).id }
    val forks = sessions.filter { roots[it.id] != it.id }.groupBy { roots.getValue(it.id) }
    return sessions.filter { roots[it.id] == it.id }.map { s ->
        val kids = forks[s.id].orEmpty()
        SessionNode(s, kids, kids.isNotEmpty() && s.id in folded)
    }
}

/**
 * Splits a group's [sessions] by the directory they are listed under, keeping their order inside
 * each. Directories with a session waiting for the user come first, then the most recently active.
 */
fun dirGroups(
    sessions: List<Session>,
    folded: Set<String> = emptySet(),
    foldedForks: Set<String> = emptySet(),
    branchFilters: Map<String, String> = emptyMap(),
    branches: Map<String, GitBranchesResponse> = emptyMap(),
): List<DirGroup> {
    val byDir = sessions.groupBy(::listedDir)
    val sameName = byDir.keys.groupingBy { baseName(it) }.eachCount()
    return byDir.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, List<Session>>> { (_, list) -> list.any { it.state == SessionState.Pending } }
                .thenByDescending { (_, list) -> list.maxOf { it.lastActiveAt } },
        )
        .map { (cwd, list) ->
            val name = baseName(cwd)
            val parent = baseName(cwd.trimEnd('/').substringBeforeLast('/', ""))
            val title = if ((sameName[name] ?: 0) > 1 && parent.isNotEmpty()) "$parent/$name" else name
            DirGroup(cwd, title, list, cwd in folded, foldedForks, branchFilters[cwd], branches[cwd])
        }
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
    /** The plan usage of the machine's Claude login; shown only when available. */
    val usage: UsageResponse? = null,
    /** A branch switch waiting for the user, because a session is working in the directory. */
    val switchAsk: BranchSwitch? = null,
    /** Session id to the text typed into its composer and not sent yet. */
    val drafts: Map<String, String> = emptyMap(),
    /** Session ids whose session.stop is still in flight; they offer no 「移出」 meanwhile. */
    val stopping: Set<String> = emptySet(),
    /** Session ids whose session.remove is still in flight or waiting for the list to drop them. */
    val removing: Set<String> = emptySet(),
    /** Increments whenever a session.remove fails, so a row folded away in the UI opens back up. */
    val removeFailed: Int = 0,
)

/** A switch of [dir] to [branch]; [sessionName] is a session working there, named in the question; [unsure] when dir.busy failed. */
data class BranchSwitch(val dir: String, val title: String, val branch: String, val sessionName: String?, val unsure: Boolean = false)

/** What the user set on this phone for the machine's groups. */
private data class GroupSettings(
    val names: Map<String, String>,
    val folded: Set<String>,
    val foldedDirs: Map<String, Set<String>>,
    val foldedForks: Set<String>,
    val drafts: Map<String, String>,
)

class SessionsViewModel(
    private val machineId: String,
    private val fleet: Fleet,
    private val relay: Relay,
    private val names: ProfileNameStore,
    /** The drafts the rows show in place of their second line; null shows none. */
    drafts: DraftStore? = null,
) : ViewModel() {
    private val refreshing = MutableStateFlow(false)
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Stops and removals already shown while the agent is still on them; null means removed. */
    private val pendingActs = MutableStateFlow<Map<String, SessionState?>>(emptyMap())

    /** Counts refused removals so the UI restores a row that folded away before session.remove failed. */
    private val removeFailed = MutableStateFlow(0)

    private val usage = MutableStateFlow<UsageResponse?>(null)

    private val groupSettings = combine(
        names.names(machineId),
        names.collapsed(machineId),
        names.collapsedDirs(machineId),
        names.collapsedForks(machineId),
        drafts?.drafts(machineId) ?: flowOf(emptyMap()),
        ::GroupSettings,
    )
    private data class ActivityState(val refreshing: Boolean, val acts: Map<String, SessionState?>, val usage: UsageResponse?, val removeFailed: Int)
    private val activity = combine(refreshing, pendingActs, usage, removeFailed, ::ActivityState)

    /** Profile id to directory to the branch its rows are narrowed to; only in memory, so a restart shows every branch. */
    private val branchFilters = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())

    /** Directory to its branches as git.branches last answered. */
    private val branches = MutableStateFlow<Map<String, GitBranchesResponse>>(emptyMap())

    private val switchAsk = MutableStateFlow<BranchSwitch?>(null)
    private val git = combine(branchFilters, branches, switchAsk, ::Triple)

    val state: StateFlow<SessionsUiState> = combine(fleet.machines, fleet.sessions, groupSettings, activity, git) { machines, sessions, settings, act, (filters, known, ask) ->
        SessionsUiState(
            machine = machines?.firstOrNull { it.id == machineId },
            groups = sessions[machineId]?.let { list ->
                groupSessions(list.withActs(act.acts), settings.names).map {
                    it.copy(
                        collapsed = it.profile.id in settings.folded,
                        foldedDirs = settings.foldedDirs[it.profile.id].orEmpty(),
                        foldedForks = settings.foldedForks,
                        branchFilters = filters[it.profile.id].orEmpty(),
                        branches = known,
                    )
                }
            },
            refreshing = act.refreshing,
            usage = act.usage,
            switchAsk = ask,
            drafts = settings.drafts,
            stopping = act.acts.filterValues { it == SessionState.Stopped }.keys,
            removing = act.acts.filterValues { it == null }.keys,
            removeFailed = act.removeFailed,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionsUiState())

    /** Directories whose branches have been asked for since the page opened or was last refreshed. */
    private val asked = mutableSetOf<String>()

    /** Per-directory generation counter so an older git.branches answer never overwrites a newer one. */
    private val branchGen = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    /** True while a branch switch (dir.busy through git.switch) is in flight; ignores a second tap. */
    private val switching = MutableStateFlow(false)

    init {
        loadUsage()
        // Each directory in a git repository reads its branches once, when it first shows up.
        viewModelScope.launch {
            fleet.sessions.map { gitDirs(it[machineId]) }.distinctUntilChanged().collect { dirs ->
                dirs.filter { asked.add(it) }.forEach(::loadBranches)
            }
        }
    }

    private fun gitDirs(list: SessionList?): Set<String> =
        list?.sessions.orEmpty().filter { it.branch != null }.map(::listedDir).toSet()

    /** Reads [dir]'s branches. A failed read leaves an empty answer, so the sheet stops waiting and the title falls back on the sessions. */
    private fun loadBranches(dir: String) {
        val gen = branchGen.computeIfAbsent(dir) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
        viewModelScope.launch {
            val b = try {
                relay.call(Method.GIT_BRANCHES, machineId, DirRequest(dir), DirRequest.serializer(), GitBranchesResponse.serializer())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (branchGen[dir]?.get() != gen) return@launch
                branches.value[dir] ?: GitBranchesResponse()
            }
            if (branchGen[dir]?.get() != gen) return@launch
            branches.update { it + (dir to b) }
        }
    }

    /** The branch sheet opens: read the branches again, as they may have changed on the machine. */
    fun openBranches(dir: DirGroup) = loadBranches(dir.cwd)

    /** Narrows a directory's rows to [branch]; null shows every branch again. */
    fun filterBranch(group: SessionGroup, dir: DirGroup, branch: String?) {
        branchFilters.update { all ->
            val mine = all[group.profile.id].orEmpty().let { if (branch == null) it - dir.cwd else it + (dir.cwd to branch) }
            all + (group.profile.id to mine)
        }
    }

    /** Switches the directory to [branch] with git.switch, asking first when a session is working there or dir.busy fails, as the git page does. */
    fun switchBranch(dir: DirGroup, branch: String) {
        if (!switching.compareAndSet(false, true)) return
        viewModelScope.launch {
            // Null when the check failed, the phone's own request timeout included.
            val busy = try {
                relay.call(Method.DIR_BUSY, machineId, DirRequest(dir.cwd), DirRequest.serializer(), DirBusyResponse.serializer()).busy
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) {
                    null
                } else {
                    switching.value = false
                    throw e
                }
            } catch (_: Exception) {
                null
            }
            if (busy == false) {
                runSwitch(dir.cwd, branch)
            } else {
                switching.value = false
                // The agent counts only the sessions in the checkout itself; one in a worktree is not in the way.
                val name = dir.sessions.firstOrNull { it.state == SessionState.Working && it.worktree == null }?.let(::display)
                switchAsk.value = BranchSwitch(dir.cwd, dir.title, branch, name, unsure = busy == null)
            }
        }
    }

    fun confirmSwitch() {
        if (!switching.compareAndSet(false, true)) return
        val ask = switchAsk.value
        if (ask == null) {
            switching.value = false
            return
        }
        switchAsk.value = null
        runSwitch(ask.dir, ask.branch)
    }

    fun dismissSwitch() {
        switchAsk.value = null
    }

    private fun runSwitch(dir: String, branch: String) {
        viewModelScope.launch {
            try {
                relay.call(Method.GIT_SWITCH, machineId, GitSwitchRequest(dir, branch), GitSwitchRequest.serializer(), Empty.serializer())
                _messages.send("已切换到 $branch")
            } catch (e: RemoteException) {
                val f = failureOf(GitOp.Switch(branch), e.code, e.message.orEmpty())
                _messages.send(e.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let { "${f.title}：${it.trim()}" } ?: f.title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("切换分支失败：${e.message}")
            } finally {
                switching.value = false
            }
            loadBranches(dir)
        }
    }

    /** Reads the plan usage; when the read fails, what is shown stays. */
    private fun loadUsage() {
        viewModelScope.launch {
            try {
                usage.value = relay.call(Method.USAGE_GET, machineId, Empty(), Empty.serializer(), UsageResponse.serializer())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        loadUsage()
        asked.clear()
        gitDirs(fleet.sessions.value[machineId]).forEach { asked += it; loadBranches(it) }
        viewModelScope.launch {
            if (!fleet.refresh(machineId)) _messages.send("刷新失败，检查目标机是否在线")
            refreshing.value = false
        }
    }

    /** Folds or unfolds a group; the choice is kept on the phone. */
    fun toggle(group: SessionGroup) {
        viewModelScope.launch { names.setCollapsed(machineId, group.profile.id, !group.collapsed) }
    }

    /** Folds or unfolds one working directory of a group; kept on the phone like the group's own fold. */
    fun toggleDir(group: SessionGroup, dir: DirGroup) {
        viewModelScope.launch { names.setDirCollapsed(machineId, group.profile.id, dir.cwd, !dir.collapsed) }
    }

    /** Folds or unfolds the forks under a session; kept on the phone like the directory folds. */
    fun toggleForks(node: SessionNode) {
        viewModelScope.launch { names.setForksCollapsed(machineId, node.session.id, !node.forksCollapsed) }
    }

    fun rename(profileId: String, name: String?) {
        viewModelScope.launch { names.rename(machineId, profileId, name) }
    }

    fun stop(session: Session) = act(Method.SESSION_STOP, session, SessionState.Stopped, "已停止「${display(session)}」")

    fun remove(session: Session) = act(Method.SESSION_REMOVE, session, null, null)

    /**
     * Shows the result at once ([shown] null hides the row) and takes it back if the agent refuses. A removed
     * row stays hidden until a list without it arrives, at most [REMOVED_HOLD_MILLIS]: a list read before the
     * removal may still bring it back meanwhile.
     */
    private fun act(method: String, session: Session, shown: SessionState?, done: String?) {
        pendingActs.update { it + (session.id to shown) }
        viewModelScope.launch {
            val ok = try {
                relay.call(method, machineId, SessionIdRequest(session.id), SessionIdRequest.serializer(), Empty.serializer())
                fleet.refresh(machineId)
                done?.let { _messages.send(it) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemoteException) {
                _messages.send(e.message ?: "操作失败")
                false
            } catch (e: Exception) {
                _messages.send("操作失败：${e.message}")
                false
            }
            if (ok && shown == null) {
                withTimeoutOrNull(REMOVED_HOLD_MILLIS) {
                    fleet.sessions.first { lists -> lists[machineId]?.sessions?.none { it.id == session.id } ?: true }
                }
            }
            pendingActs.update { it - session.id }
            if (!ok && shown == null) removeFailed.update { it + 1 }
        }
    }
}

/** How long a removed row stays hidden while the lists that arrive still hold it. */
const val REMOVED_HOLD_MILLIS = 30_000L

/** The list as it will be once the pending stops and removals are done. */
private fun SessionList.withActs(acts: Map<String, SessionState?>): SessionList =
    if (acts.isEmpty()) this
    else copy(sessions = sessions.mapNotNull { s -> if (s.id in acts) acts[s.id]?.let { s.copy(state = it) } else s })

fun display(s: Session): String = s.name.ifBlank { clauderc.app.ui.baseName(s.cwd).ifBlank { s.id } }
