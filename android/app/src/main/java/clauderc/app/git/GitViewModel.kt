package clauderc.app.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import clauderc.app.data.Fleet
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
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A write operation, kept so it can run after the user confirms. */
sealed interface GitOp {
    val verb: String
    data class Stage(val path: String) : GitOp { override val verb = "暂存" }
    data class Unstage(val path: String) : GitOp { override val verb = "取消暂存" }
    data class Commit(val message: String) : GitOp { override val verb = "提交" }
    data object Pull : GitOp { override val verb = "拉取" }
    data class Push(val setUpstream: Boolean = false, val branch: String = "") : GitOp { override val verb = "推送" }
    data class Switch(val branch: String) : GitOp { override val verb = "切换分支" }
}

sealed interface GitConfirm {
    /** A session is working in this directory (git-workspace spec: write protection). */
    data class Busy(val op: GitOp, val sessionName: String?) : GitConfirm
    /** git.push answered needsConfirmation: the branch has no upstream yet. */
    data class Upstream(val branch: String) : GitConfirm
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
    val branches: GitBranchesResponse? = null,
    /** Name of a session working in the directory, or null. */
    val busySession: String? = null,
    val message: String = "",
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

    init {
        viewModelScope.launch {
            val dirs = runCatching {
                relay.call(Method.DIRS_RECENT, machineId, Empty(), Empty.serializer(), DirsRecentResponse.serializer()).dirs
            }.getOrDefault(emptyList())
            _state.update { it.copy(dirs = dirs) }
            if (_state.value.dir == null) {
                dirs.firstOrNull()?.let { setDir(it) }
            }
        }
        if (initialDir != null) refresh()
    }

    fun setDir(dir: String) {
        _state.update { it.copy(dir = dir, commits = emptyList(), status = null, branches = null, notRepo = false, page = 0, hasMore = false) }
        onDirChosen(dir)
        refresh()
    }

    fun setTab(tab: GitTab) = _state.update { it.copy(tab = tab) }
    fun setMessage(text: String) = _state.update { it.copy(message = text) }
    fun dismissFailure() = _state.update { it.copy(failure = null) }
    fun dismissConfirm() = _state.update { it.copy(confirm = null) }

    fun refresh() {
        val dir = _state.value.dir ?: return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val (status, log) = coroutineScope {
                    val status = async { relay.call(Method.GIT_STATUS, machineId, DirRequest(dir), DirRequest.serializer(), GitStatusResponse.serializer()) }
                    val log = async { relay.call(Method.GIT_LOG, machineId, GitLogRequest(dir, 0), GitLogRequest.serializer(), GitLogResponse.serializer()) }
                    status.await() to log.await()
                }
                _state.update { it.copy(loading = false, notRepo = false, status = status, commits = log.commits, hasMore = log.hasMore, page = 0) }
            } catch (e: RemoteException) {
                _state.update { it.copy(loading = false, notRepo = e.code == ErrorCode.NOT_GIT_REPO) }
                if (e.code != ErrorCode.NOT_GIT_REPO) _messages.send(e.message ?: "读不到 git 状态")
            } catch (e: Exception) {
                _state.update { it.copy(loading = false) }
            }
            _state.update { it.copy(busySession = busySessionName(dir)) }
        }
    }

    fun loadMore() {
        val s = _state.value
        val dir = s.dir ?: return
        if (!s.hasMore || s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val next = s.page + 1
            val log = runCatching {
                relay.call(Method.GIT_LOG, machineId, GitLogRequest(dir, next), GitLogRequest.serializer(), GitLogResponse.serializer())
            }.getOrNull()
            _state.update {
                if (log == null) it.copy(loadingMore = false)
                else it.copy(loadingMore = false, commits = it.commits + log.commits, hasMore = log.hasMore, page = next)
            }
        }
    }

    fun loadBranches() {
        val dir = _state.value.dir ?: return
        viewModelScope.launch {
            runCatching {
                relay.call(Method.GIT_BRANCHES, machineId, DirRequest(dir), DirRequest.serializer(), GitBranchesResponse.serializer())
            }.onSuccess { b -> _state.update { it.copy(branches = b) } }
        }
    }

    /** Runs a write operation, asking first when a session is working in the directory. */
    fun request(op: GitOp) {
        val dir = _state.value.dir ?: return
        viewModelScope.launch {
            val busy = runCatching {
                relay.call(Method.DIR_BUSY, machineId, DirRequest(dir), DirRequest.serializer(), DirBusyResponse.serializer()).busy
            }.getOrDefault(false)
            if (busy) {
                _state.update { it.copy(confirm = GitConfirm.Busy(op, busySessionName(dir))) }
            } else {
                run(op)
            }
        }
    }

    /** The user confirmed the dialog. */
    fun confirm() {
        when (val c = _state.value.confirm) {
            is GitConfirm.Busy -> run(c.op)
            is GitConfirm.Upstream -> run(GitOp.Push(setUpstream = true, branch = c.branch))
            null -> Unit
        }
        _state.update { it.copy(confirm = null) }
    }

    private fun run(op: GitOp) {
        val dir = _state.value.dir ?: return
        _state.update { it.copy(running = true, confirm = null) }
        viewModelScope.launch {
            try {
                when (op) {
                    is GitOp.Stage -> relay.call(Method.GIT_STAGE, machineId, GitPathRequest(dir, op.path), GitPathRequest.serializer(), Empty.serializer())
                    is GitOp.Unstage -> relay.call(Method.GIT_UNSTAGE, machineId, GitPathRequest(dir, op.path), GitPathRequest.serializer(), Empty.serializer())
                    is GitOp.Commit -> {
                        val res = relay.call(Method.GIT_COMMIT, machineId, GitCommitRequest(dir, op.message.trim()), GitCommitRequest.serializer(), GitCommitResponse.serializer())
                        _state.update { it.copy(message = "") }
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
                    _state.update { it.copy(running = false, confirm = GitConfirm.Upstream(it.status?.branch.orEmpty())) }
                    return@launch
                }
                _state.update { it.copy(failure = failureOf(op, e.code, e.message.orEmpty())) }
            } catch (e: Exception) {
                _state.update { it.copy(failure = GitFailure("${op.verb}失败", null, command(op), e.message.orEmpty())) }
            }
            _state.update { it.copy(running = false) }
            refresh()
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
