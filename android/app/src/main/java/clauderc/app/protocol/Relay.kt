package clauderc.app.protocol

import java.time.Instant
import kotlinx.serialization.Serializable

/** Mirrors internal/protocol/relay.go. */
object RelayPath {
    const val APP_WS = "/ws/app"
    const val PAIR = "/api/pair"
}

/** WebSocket close codes agreed with the relay. */
object CloseCode {
    /** The device token was revoked; go back to pairing. */
    const val REVOKED = 1008
}

@Serializable
data class Machine(
    val id: String,
    val name: String,
    val online: Boolean = false,
    @Serializable(with = InstantSerializer::class) val lastSeenAt: Instant,
    /** Empty when unknown. */
    val agentVersion: String = "",
    val claudeVersion: String = "",
)

@Serializable
data class MachinesListResponse(val machines: List<Machine> = emptyList())

@Serializable
data class PairRequest(val code: String, val deviceName: String)

@Serializable
data class PairResponse(val deviceId: String, val token: String)
