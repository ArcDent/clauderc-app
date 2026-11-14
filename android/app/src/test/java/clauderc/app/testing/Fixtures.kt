package clauderc.app.testing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import clauderc.app.data.ProfileNameStore
import clauderc.app.protocol.Machine
import clauderc.app.protocol.Profile
import clauderc.app.protocol.ProfileKind
import clauderc.app.protocol.Session
import clauderc.app.protocol.SessionList
import clauderc.app.protocol.SessionState
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope

val T0: Instant = Instant.parse("2026-11-12T09:30:00Z")

fun machine(id: String, name: String, online: Boolean = true, lastSeen: Instant = T0) =
    Machine(id, name, online, lastSeen, "0.1.0", "2.1.330")

fun session(id: String, name: String, state: SessionState, profile: String, minutesAgo: Long = 1, cwd: String = "/Users/me/github/$id") =
    Session(id, name, cwd, state, profile, T0.minusSeconds(minutesAgo * 60))

val sampleProfiles = listOf(
    Profile("default", ProfileKind.Default),
    Profile("api.anthropic.com", ProfileKind.Endpoint),
    Profile("anyrouter.top", ProfileKind.Endpoint),
    Profile("other", ProfileKind.Other),
)

/** The mockup's session list: two in 默认, two in 官方, none in anyrouter, one unreadable. */
val sampleSessions = SessionList(
    sessions = listOf(
        session("s-idle", "周报整理", SessionState.Idle, "default", minutesAgo = 18),
        session("s-work", "修复重连", SessionState.Working, "default", minutesAgo = 0),
        session("s-need", "clauderc 协议", SessionState.Pending, "default", minutesAgo = 2),
        session("s-q", "迁移脚本审查", SessionState.Pending, "api.anthropic.com", minutesAgo = 5),
        session("s-stop", "landing 页", SessionState.Stopped, "api.anthropic.com", minutesAgo = 60 * 24),
        session("s-other", "scratch", SessionState.Idle, "other", minutesAgo = 60 * 72),
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
