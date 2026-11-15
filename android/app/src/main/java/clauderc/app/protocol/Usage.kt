package clauderc.app.protocol

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors internal/protocol/usage.go. */
@Serializable
data class UsageWindow(
    /** Share of the limit used, 0–100. */
    val utilization: Double,
    @Serializable(with = InstantSerializer::class) val resetsAt: Instant? = null,
)

@Serializable
enum class UsageStale {
    @SerialName("expired") Expired,
    @SerialName("failed") Failed,
}

/** The 5-hour and weekly limits of the machine's Claude login; nothing to show unless [available]. */
@Serializable
data class UsageResponse(
    val available: Boolean = false,
    val fiveHour: UsageWindow? = null,
    val sevenDay: UsageWindow? = null,
    @Serializable(with = InstantSerializer::class) val fetchedAt: Instant? = null,
    /** Set when the latest read failed; the windows, if any, are the last ones read. */
    val stale: UsageStale? = null,
)
