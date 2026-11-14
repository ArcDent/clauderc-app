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
)

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
)

@Serializable
data class SessionCreateResponse(val sessionId: String)

@Serializable
data class SessionIdRequest(val sessionId: String)

@Serializable
data class DirsRecentResponse(val dirs: List<String> = emptyList())
