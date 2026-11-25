package clauderc.app.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import clauderc.app.data.Fleet
import clauderc.app.net.ConnectionState
import clauderc.app.net.Relay
import clauderc.app.net.RemoteException
import clauderc.app.net.call
import clauderc.app.protocol.Commit
import clauderc.app.protocol.DirBusyResponse
import clauderc.app.protocol.DirRequest
import clauderc.app.protocol.DirsRecentResponse
import clauderc.app.protocol.Empty
import clauderc.app.protocol.ErrorCode
import clauderc.app.protocol.GitBranchesResponse
import clauderc.app.protocol.GitCommitRequest
import clauderc.app.protocol.GitCommitResponse
import clauderc.app.protocol.GitLogRequest
import clauderc.app.protocol.GitLogResponse
import clauderc.app.protocol.GitPathRequest
import clauderc.app.protocol.GitPushRequest
import clauderc.app.protocol.GitStatusResponse
import clauderc.app.protocol.GitSwitchRequest
import clauderc.app.protocol.Method
import clauderc.app.protocol.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A write operation, kept so it can run after the user confirms. */
sealed interface GitOp {
    val verb: String
    data class Stage(val path: String) : GitOp { override val verb = "暂存" }
    data class Unstage(val path: String) : GitOp { override val verb = "取消暂存" }
    data class StageAll(val paths: List<String>) : GitOp { override val verb = "全部暂存" }
    data class UnstageAll(val paths: List<String>) : GitOp { override val verb = "全部取消暂存" }
    data class Commit(val message: String) : GitOp { override val verb = "提交" }
    data object Pull : GitOp { override val verb = "拉取" }
    data class Push(val setUpstream: Boolean = false, val branch: String = "") : GitOp { override val verb = "推送" }
    data class Switch(val branch: String) : GitOp { override val verb = "切换分支" }
}

sealed interface GitConfirm {
    /** Where the operation runs: the directory shown when the user tapped, whatever the page shows now. */
    val dir: String
    /** A session is working in this directory (git-workspace spec: write protection), or [unsure]: dir.busy failed. */
    data class Busy(override val dir: String, val op: GitOp, val sessionName: String?, val unsure: Boolean = false) : GitConfirm
    /** git.push answered needsConfirmation: the branch has no upstream yet. */
    data class Upstream(override val dir: String, val branch: String) : GitConfirm
}

/** The failure sheet: a short title and explanation, plus git's raw output. */
data class GitFailure(val title: String, val text: String?, val command: String?, val output: String)

enum class GitTab { Log, Status }

data class GitUiState(
    val dir: String? = null,
    val dirs: List<String> = emptyList(),
    val tab: GitTab = GitTab.Log,
    val loading: Boolean = false,
    val notRepo: Boolean = false,
    val status: GitStatusResponse? = null,
    val commits: List<Commit> = emptyList(),
    val hasMore: Boolean = false,
    val page: Int = 0,
    val loadingMore: Boolean = false,
    /** The last page did not arrive: the end of the timeline offers a retry instead of a spinner. */
    val moreFailed: Boolean = false,
    val branches: GitBranchesResponse? = null,
    /** Name of a session working in the directory, or null. */
    val busySession: String? = null,
    val message: String = "",
    /** A write is under way, from the tap through dir.busy to git's answer; no other write starts meanwhile. */
    val running: Boolean = false,
    val confirm: GitConfirm? = null,
    val failure: GitFailure? = null,
) {
    val canCommit get() = message.isNotBlank() && status?.staged?.isNotEmpty() == true && !running
}

class GitViewModel(
    private val machineId: String,
    initialDir: String?,
    private val relay: Relay,
    private val fleet: Fleet,
    private val onDirChosen: (String) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(GitUiState(dir = initialDir))
    val state: StateFlow<GitUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Counts refreshes: only the latest one lands, so a late answer for the directory left behind is dropped. */
    private var refreshes = 0

    /** Counts timeline replacements: a page asked for an older timeline is dropped. */
    private var timelines = 0

    /** True once dirs.recent answered; stays false while disconnected so reconnecting fetches it. */
    private var loadedDirs = false
    private var loadingDirs = false

    /** True once the current directory's status and log loaded (or it was confirmed not a repo). */
    private var loadedRepo = false

    init {
        viewModelScope.launch {
            combine(relay.stateOf(machineId), fleet.machines) { conn, machines ->
                conn == ConnectionState.Connected && machines?.firstOrNull { it.id == machineId }?.online != false
            }.distinctUntilChanged().collect { online ->
                if (!online) return@collect
                if (!loadedDirs && !loadingDirs) loadDirs()
                if (_state.value.dir != null && !loadedRepo && !_state.value.loading) refresh()
            }
        }
    }

    private fun loadDirs() {
        loadingDirs = true
        viewModelScope.launch {
            try {
                val dirs = relay.call(Method.DIRS_RECENT, machineId, Empty(), Empty.serializer(), DirsRecentResponse.serializer()).dirs
                loadedDirs = true
                _state.update { it.copy(dirs = dirs) }
                if (_state.value.dir == null) {
                    dirs.firstOrNull()?.let { setDir(it) }
                }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
            } finally {
                loadingDirs = false
            }
        }
    }

    fun setDir(dir: String) {
        timelines++
        loadedRepo = false
        _state.update {
            it.copy(dir = dir, commits = emptyList(), status = null, branches = null, busySession = null, notRepo = false, page = 0, hasMore = false, loadingMore = false, moreFailed = false)
        }
        onDirChosen(dir)
        refresh()
    }

    fun setTab(tab: GitTab) = _state.update { it.copy(tab = tab) }
    fun setMessage(text: String) = _state.update { it.copy(message = text) }
    fun dismissFailure() = _state.update { it.copy(failure = null) }
    fun dismissConfirm() = _state.update { it.copy(confirm = null) }

    fun refresh() {
        val dir = _state.value.dir ?: return
        val n = ++refreshes
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val (status, log) = coroutineScope {
                    val status = async { relay.call(Method.GIT_STATUS, machineId, DirRequest(dir), DirRequest.serializer(), GitStatusResponse.serializer()) }
                    val log = async { relay.call(Method.GIT_LOG, machineId, GitLogRequest(dir, 0), GitLogRequest.serializer(), GitLogResponse.serializer()) }
                    status.await() to log.await()
                }
                if (n != refreshes) return@launch
                loadedRepo = true
                // A page still on its way belongs to the timeline replaced here: it is dropped and asked again.
                timelines++
                val resume = _state.value.loadingMore
                _state.update {
                    it.copy(loading = false, notRepo = false, status = status, commits = log.commits.distinctBy { c -> c.hash }, hasMore = log.hasMore, page = 0, loadingMore = false, moreFailed = false)
                }
                if (resume) loadMore()
            } catch (e: RemoteException) {
                if (n != refreshes) return@launch
                if (e.code == ErrorCode.NOT_GIT_REPO) loadedRepo = true
                _state.update { it.copy(loading = false, notRepo = e.code == ErrorCode.NOT_GIT_REPO) }
                if (e.code != ErrorCode.NOT_GIT_REPO) _messages.send(e.message ?: "读不到 git 状态")
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (n != refreshes) return@launch
                _state.update { it.copy(loading = false) }
            }
            _state.update { if (it.dir == dir) it.copy(busySession = busySessionName(dir)) else it }
        }
    }

    /** Asks for the next page; after a failure the same call is the retry. */
    fun loadMore() {
        val s = _state.value
        val dir = s.dir ?: return
        if (!s.hasMore || s.loadingMore) return
        val timeline = timelines
        _state.update { it.copy(loadingMore = true, moreFailed = false) }
        viewModelScope.launch {
            val next = s.page + 1
            val log = runCatching {
                relay.call(Method.GIT_LOG, machineId, GitLogRequest(dir, next), GitLogRequest.serializer(), GitLogResponse.serializer())
            }.getOrNull()
            if (timeline != timelines) return@launch
            _state.update {
                if (log == null) {
                    it.copy(loadingMore = false, moreFailed = true)
                } else {
                    // Pages count from the newest commit, so a commit made between two pages repeats at the seam.
                    val shown = it.commits.mapTo(HashSet()) { c -> c.hash }
                    it.copy(loadingMore = false, commits = it.commits + log.commits.filter { c -> shown.add(c.hash) }, hasMore = log.hasMore, page = next)
                }
            }
        }
    }

    fun loadBranches() {
        val dir = _state.value.dir ?: return
        viewModelScope.launch {
            runCatching {
                relay.call(Method.GIT_BRANCHES, machineId, DirRequest(dir), DirRequest.serializer(), GitBranchesResponse.serializer())
            }.onSuccess { b -> _state.update { if (it.dir == dir) it.copy(branches = b) else it } }
        }
    }

    /** Runs a write operation, asking first when a session is working in the directory or dir.busy fails. */
    fun request(op: GitOp) {
        if ((op is GitOp.StageAll && op.paths.isEmpty()) || (op is GitOp.UnstageAll && op.paths.isEmpty())) return
        val before = _state.getAndUpdate { if (it.dir == null || it.running) it else it.copy(running = true) }
        val dir = before.dir ?: return
        if (before.running) return
        val queued = if (op is GitOp.Push && op.branch.isEmpty()) op.copy(branch = before.status?.branch.orEmpty()) else op
        viewModelScope.launch {
            // Null when the check failed, the phone's own request timeout included.
            val busy = runCatching {
                relay.call(Method.DIR_BUSY, machineId, DirRequest(dir), DirRequest.serializer(), DirBusyResponse.serializer()).busy
            }.getOrNull()
            if (busy == false) {
                run(queued, dir)
            } else {
                _state.update { it.copy(running = false, confirm = GitConfirm.Busy(dir, queued, busySessionName(dir), unsure = busy == null)) }
            }
        }
    }

    /** The user confirmed the dialog. */
    fun confirm() {
        val before = _state.getAndUpdate { if (it.confirm == null || it.running) it else it.copy(confirm = null, running = true) }
        if (before.running) return
        when (val c = before.confirm) {
            is GitConfirm.Busy -> run(c.op, c.dir)
            is GitConfirm.Upstream -> run(GitOp.Push(setUpstream = true, branch = c.branch), c.dir)
            null -> Unit
        }
    }

    private fun run(op: GitOp, dir: String) {
        _state.update { it.copy(running = true, confirm = null) }
        viewModelScope.launch {
            try {
                when (op) {
                    is GitOp.Stage -> relay.call(Method.GIT_STAGE, machineId, GitPathRequest(dir, op.path), GitPathRequest.serializer(), Empty.serializer())
                    is GitOp.Unstage -> relay.call(Method.GIT_UNSTAGE, machineId, GitPathRequest(dir, op.path), GitPathRequest.serializer(), Empty.serializer())
                    is GitOp.StageAll -> {
                        for (path in op.paths) {
                            relay.call(Method.GIT_STAGE, machineId, GitPathRequest(dir, path), GitPathRequest.serializer(), Empty.serializer())
                        }
                        _messages.send("已全部暂存")
                    }
                    is GitOp.UnstageAll -> {
                        for (path in op.paths) {
                            relay.call(Method.GIT_UNSTAGE, machineId, GitPathRequest(dir, path), GitPathRequest.serializer(), Empty.serializer())
                        }
                        _messages.send("已全部取消暂存")
                    }
                    is GitOp.Commit -> {
                        val res = relay.call(Method.GIT_COMMIT, machineId, GitCommitRequest(dir, op.message.trim()), GitCommitRequest.serializer(), GitCommitResponse.serializer())
                        _state.update { if (it.dir == dir) it.copy(message = "") else it }
                        _messages.send("已提交 ${res.hash.take(7)}")
                    }
                    GitOp.Pull -> {
                        relay.call(Method.GIT_PULL, machineId, DirRequest(dir), DirRequest.serializer(), Empty.serializer())
                        _messages.send("已拉取")
                    }
                    is GitOp.Push -> {
                        relay.call(Method.GIT_PUSH, machineId, GitPushRequest(dir, op.setUpstream), GitPushRequest.serializer(), Empty.serializer())
                        _messages.send("已推送")
                    }
                    is GitOp.Switch -> {
                        relay.call(Method.GIT_SWITCH, machineId, GitSwitchRequest(dir, op.branch), GitSwitchRequest.serializer(), Empty.serializer())
                        _messages.send("已切换到 ${op.branch}")
                    }
                }
            } catch (e: RemoteException) {
                if (op is GitOp.Push && e.code == ErrorCode.NEEDS_CONFIRMATION) {
                    _state.update { it.copy(running = false, confirm = GitConfirm.Upstream(dir, op.branch.ifEmpty { if (it.dir == dir) it.status?.branch.orEmpty() else "" })) }
                    return@launch
                }
                _state.update { it.copy(failure = failureOf(op, e.code, e.message.orEmpty())) }
            } catch (e: Exception) {
                _state.update { it.copy(failure = GitFailure("${op.verb}失败", null, command(op), e.message.orEmpty())) }
            }
            _state.update { it.copy(running = false) }
            if (_state.value.dir == dir) refresh()
        }
    }

    private fun busySessionName(dir: String): String? {
        val sessions = fleet.sessions.value[machineId]?.sessions.orEmpty()
        val s = sessions.firstOrNull { it.state == SessionState.Working && (it.cwd == dir || it.cwd.startsWith(dir.trimEnd('/') + "/")) }
        return s?.let { clauderc.app.sessions.display(it) }
    }
}

/** The git command line from design D9, shown above the raw output; null where D9 does not fix one. */
fun command(op: GitOp): String? = when (op) {
    GitOp.Pull -> "git pull --ff-only"
    is GitOp.Push -> if (op.setUpstream) "git push -u origin ${op.branch}".trimEnd() else "git push"
    is GitOp.Switch -> "git switch ${op.branch}"
    else -> null
}

/** The title of the question asked before writing while a session works in the directory; [unsure] when dir.busy failed. */
fun busyTitle(unsure: Boolean): String = if (unsure) "不确定 claude 是否在这个目录工作" else "claude 正在这个目录工作"

/**
 * The sentence of the question asked before [verb] in [place] while [session], or some session, works there;
 * [unsure] when dir.busy failed and nobody knows.
 */
fun busyText(session: String?, unsure: Boolean, place: String, verb: String): String =
    if (unsure) "没能确认 $place 里有没有会话在改代码，现在${verb}可能带上写到一半的改动。"
    else "${session?.let { "「$it」" } ?: "有会话"}正在改 $place 里的代码，现在${verb}可能带上它写到一半的改动。"

/** Explains common git failures in a sentence; the raw output is always shown too. */
fun failureOf(op: GitOp, code: String, output: String): GitFailure {
    val cmd = command(op)
    return when {
        code == ErrorCode.GIT_CREDENTIALS_MISSING -> GitFailure("目标机缺少 git 凭证", "请在电脑上配好 SSH 密钥或凭证助手。\n手机上不会索要密码。", cmd, output)
        code == ErrorCode.TIMEOUT -> GitFailure("${op.verb}超时", "git 30 秒内没有完成，可能是网络不通。", cmd, output)
        op is GitOp.Pull && output.contains("fast-forward", ignoreCase = true) ->
            GitFailure("无法快进", "本地和远端各有对方没有的提交。\n不会自动合并或变基，本地分支没有变化。", cmd, output)
        op is GitOp.Push && output.contains("rejected") -> GitFailure("推送被拒绝", "远端有本地没有的提交，先拉取再推送。", cmd, output)
        op is GitOp.Switch && output.contains("overwritten") ->
            GitFailure("切换到 ${op.branch} 失败", "会覆盖下面这些文件的未提交改动，工作目录没有变化。", cmd, output)
        else -> GitFailure("${op.verb}失败", null, cmd, output)
    }
}
