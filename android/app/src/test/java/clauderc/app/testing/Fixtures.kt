package clauderc.app.testing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import clauderc.app.data.ProfileNameStore
import clauderc.app.protocol.Machine
import clauderc.app.protocol.Profile
import clauderc.app.protocol.ProfileKind
import clauderc.app.protocol.Session
import clauderc.app.protocol.SessionContext
import clauderc.app.protocol.SessionList
import clauderc.app.protocol.SessionState
import clauderc.app.protocol.SessionWorktree
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope

val T0: Instant = Instant.parse("2026-11-12T09:30:00Z")

fun machine(id: String, name: String, online: Boolean = true, lastSeen: Instant = T0) =
    Machine(id, name, online, lastSeen, "0.1.0", "2.1.330")

fun session(id: String, name: String, state: SessionState, profile: String, minutesAgo: Long = 1, cwd: String = "/Users/me/github/$id", context: SessionContext? = null) =
    Session(id, name, cwd, state, profile, T0.minusSeconds(minutesAgo * 60), context)

val sampleProfiles = listOf(
    Profile("default", ProfileKind.Default),
    Profile("api.anthropic.com", ProfileKind.Endpoint),
    Profile("anyrouter.top", ProfileKind.Endpoint),
    Profile("other", ProfileKind.Other),
)

/** The mockup's session list: two in 默认, two in 官方, none in anyrouter, one unreadable. */
val sampleSessions = SessionList(
    sessions = listOf(
        session("s-idle", "周报整理", SessionState.Idle, "default", minutesAgo = 18, cwd = "/Users/me/github/clauderc-app", context = SessionContext(16_000, 200_000))
            .copy(jobState = "blocked", detail = "周报要按周一到周五拆开写吗？"),
        session("s-work", "修复重连", SessionState.Working, "default", minutesAgo = 0, cwd = "/Users/me/github/relay-svc", context = SessionContext(124_000, 200_000))
            .copy(jobState = "working", detail = "正在查断线后为什么没有自动重连"),
        session("s-need", "clauderc 协议", SessionState.Pending, "default", minutesAgo = 2, cwd = "/Users/me/github/clauderc-app", context = SessionContext(254_185, 1_000_000)),
        session("s-q", "迁移脚本审查", SessionState.Pending, "api.anthropic.com", minutesAgo = 5, cwd = "/Users/me/github/infra", context = SessionContext(330_000, 1_000_000)),
        session("s-stop", "landing 页", SessionState.Stopped, "api.anthropic.com", minutesAgo = 60 * 24, cwd = "/Users/me/github/site", context = SessionContext(142_000, 200_000))
            .copy(jobState = "done", detail = "landing 页已上线"),
        session("s-other", "scratch", SessionState.Idle, "other", minutesAgo = 60 * 72, cwd = "/Users/me/scratch"),
    ),
    profiles = sampleProfiles,
)

/**
 * The demo's round 5 list (design/ui-mockup/demo.html): in clauderc-app a session in a worktree and a
 * fork hung under 「clauderc 协议」; in relay-svc sessions on two branches and a fork whose source is gone.
 */
val branchSessions = SessionList(
    sessions = listOf(
        session("s1", "clauderc 协议", SessionState.Pending, "default", minutesAgo = 2, cwd = "/Users/me/github/clauderc-app", context = SessionContext(250_000, 1_000_000))
            .copy(branch = "dev", detail = "go vet ./... && go test ./internal/protocol/..."),
        session("s2", "修复重连", SessionState.Working, "default", minutesAgo = 3, cwd = "/Users/me/github/relay-svc", context = SessionContext(124_000, 200_000))
            .copy(branch = "fix-reconnect", jobState = "working", detail = "正在查断线后为什么没有自动重连"),
        session("s12", "工具视图重写", SessionState.Idle, "default", minutesAgo = 6, cwd = "/Users/me/github/clauderc-app/.claude/worktrees/tools-view", context = SessionContext(94_000, 200_000))
            .copy(dir = "/Users/me/github/clauderc-app", branch = "worktree-tools-view", worktree = SessionWorktree("tools-view", "/Users/me/github/clauderc-app/.claude/worktrees/tools-view")),
        session("s9", "README 截图", SessionState.Idle, "default", minutesAgo = 12, cwd = "/Users/me/github/clauderc-app", context = SessionContext(36_000, 200_000)).copy(branch = "dev"),
        session("s13", "clauderc 协议 ⑂ 补错误码测试", SessionState.Idle, "default", minutesAgo = 14, cwd = "/Users/me/github/clauderc-app", context = SessionContext(62_000, 200_000))
            .copy(branch = "dev", forkOf = "s1"),
        session("s10", "离线判定", SessionState.Pending, "default", minutesAgo = 60, cwd = "/Users/me/github/relay-svc", context = SessionContext(82_000, 200_000))
            .copy(branch = "main", detail = "停在一个对话框上"),
        session("s14", "排查日志 ⑂ 只看 relay 部分", SessionState.Idle, "default", minutesAgo = 120, cwd = "/Users/me/github/relay-svc", context = SessionContext(18_000, 200_000))
            .copy(branch = "main", forkOf = "gone0001"),
        session("s4", "迁移脚本审查", SessionState.Pending, "api.anthropic.com", minutesAgo = 5, cwd = "/Users/me/github/infra", context = SessionContext(330_000, 1_000_000)).copy(branch = "main"),
        session("s11", "部署脚本", SessionState.Idle, "api.anthropic.com", minutesAgo = 40, cwd = "/Users/me/github/infra", context = SessionContext(24_000, 200_000)).copy(branch = "main"),
    ),
    profiles = sampleProfiles,
)

fun profileNames(dir: File, scope: CoroutineScope) =
    ProfileNameStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "names.preferences_pb") }))

/** Waits in real time, for work that runs on real threads such as DataStore writes. */
suspend fun <T> eventually(block: suspend () -> T): T =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)) {
        kotlinx.coroutines.withTimeout(5_000) { block() }
    }
