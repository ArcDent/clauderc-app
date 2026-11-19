package clauderc.app.protocol

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors internal/protocol/sessions.go. */
@Serializable
enum class SessionState {
    @SerialName("working") Working,
    @SerialName("idle") Idle,
    @SerialName("pending") Pending,
    @SerialName("stopped") Stopped,
}

@Serializable
enum class ProfileKind {
    @SerialName("default") Default,
    @SerialName("endpoint") Endpoint,
    @SerialName("other") Other,
}

/** For endpoint profiles the id is the API host itself. */
@Serializable
data class Profile(val id: String, val kind: ProfileKind = ProfileKind.Other)

@Serializable
data class Session(
    val id: String,
    val name: String = "",
    val cwd: String = "",
    val state: SessionState = SessionState.Idle,
    val profileId: String = "",
    @Serializable(with = InstantSerializer::class) val lastActiveAt: Instant,
    /** How full the context window was after the last reply; null before the first. */
    val context: SessionContext? = null,
    /** Claude Code's own reading of the session, passed through: working, blocked, done or failed; null when none. Blocked while [state] is not pending means it asked for a reply. */
    val jobState: String? = null,
    /** The one-line progress Claude Code keeps beside [jobState]; null when none. */
    val detail: String? = null,
    /** Subagents of a running session that still run, in start order; null when none. */
    val agents: List<Subagent>? = null,
    /** Directory the session is listed under: a worktree session's original checkout. Null from older agents; use [cwd]. */
    val dir: String? = null,
    /** Git branch of the session's last transcript entry; null outside a git repository. */
    val branch: String? = null,
    /** Set while the session works in a git worktree. */
    val worktree: SessionWorktree? = null,
    /** Short id of the session this one was forked from with /fork; null when not a fork. */
    val forkOf: String? = null,
)

/** The git worktree a session works in; git methods take [path] as cwd. */
@Serializable
data class SessionWorktree(val name: String, val path: String)

@Serializable
data class SessionContext(val usedTokens: Int, val windowTokens: Int)

@Serializable
data class SessionList(
    val sessions: List<Session> = emptyList(),
    val profiles: List<Profile> = emptyList(),
)

@Serializable
data class SessionCreateRequest(
    val profileId: String,
    val cwd: String,
    val name: String? = null,
    val prompt: String? = null,
    /** Say yes to Claude Code's trust prompt for [cwd] first when it is untrusted; null leaves it out. */
    val trust: Boolean? = null,
)

@Serializable
data class SessionCreateResponse(val sessionId: String)

@Serializable
data class SessionIdRequest(val sessionId: String)

@Serializable
data class DirsRecentResponse(val dirs: List<String> = emptyList())
