package clauderc.app.protocol

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Mirrors internal/protocol/envelope.go. Only version 1 is accepted. */
const val PROTOCOL_VERSION = 1

/**
 * Shared JSON settings. Fields that Go marks `omitempty` are nullable with a
 * null default here, so encoding drops them just like Go does; unknown keys
 * and unknown enum values from a newer agent are tolerated.
 */
val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}

object Kind {
    const val REQUEST = "req"
    const val RESPONSE = "res"
    const val EVENT = "evt"
}

@Serializable
data class Envelope(
    val v: Int = PROTOCOL_VERSION,
    val kind: String,
    val id: String? = null,
    val method: String? = null,
    val machine: String? = null,
    val client: String? = null,
    val body: JsonElement? = null,
    val error: ErrorBody? = null,
) {
    companion object {
        /** Decodes one WebSocket text frame; rejects other versions and kinds. */
        fun decode(text: String): Envelope {
            val env = ProtocolJson.decodeFromString(serializer(), text)
            require(env.v == PROTOCOL_VERSION) { "unsupported protocol version ${env.v}" }
            require(env.kind == Kind.REQUEST || env.kind == Kind.RESPONSE || env.kind == Kind.EVENT) {
                "unknown kind ${env.kind}"
            }
            return env
        }
    }

    fun encode(): String = ProtocolJson.encodeToString(serializer(), this)
}

/** The failure part of a response, and the body of failed relay HTTP calls. */
@Serializable
data class ErrorBody(val code: String, val message: String = "")

/** Error codes from envelope.go; kept as strings so unknown codes survive. */
object ErrorCode {
    const val UNAUTHENTICATED = "unauthenticated"
    const val MACHINE_OFFLINE = "machineOffline"
    const val UNKNOWN_METHOD = "unknownMethod"
    const val INVALID_PARAMS = "invalidParams"
    const val SESSION_NOT_FOUND = "sessionNotFound"
    const val SESSION_RUNNING = "sessionRunning"
    const val PENDING_CHANGED = "pendingChanged"
    const val NOT_DELIVERED = "notDelivered"
    const val DIR_NOT_FOUND = "dirNotFound"
    const val NOT_GIT_REPO = "notGitRepo"
    const val NEEDS_CONFIRMATION = "needsConfirmation"
    const val GIT_FAILED = "gitFailed"
    const val GIT_CREDENTIALS_MISSING = "gitCredentialsMissing"
    const val TIMEOUT = "timeout"
    const val INTERNAL = "internal"
    const val PAIR_CODE_EXPIRED = "pairCodeExpired"
    const val PAIR_CODE_INVALID = "pairCodeInvalid"
    const val RATE_LIMITED = "rateLimited"
    const val DEVICE_NOT_FOUND = "deviceNotFound"
}

/** Method names from methods.go. */
object Method {
    const val MACHINES_LIST = "machines.list"
    const val MACHINE_STATUS = "machine.status"
    const val CLIENT_GONE = "client.gone"

    const val SESSIONS_SUBSCRIBE = "sessions.subscribe"
    const val SESSIONS_UNSUBSCRIBE = "sessions.unsubscribe"
    const val SESSIONS_CHANGED = "sessions.changed"

    const val CHAT_OPEN = "chat.open"
    const val CHAT_CLOSE = "chat.close"
    const val CHAT_SEND = "chat.send"
    const val CHAT_ANSWER = "chat.answer"
    const val CHAT_INTERRUPT = "chat.interrupt"
    const val CHAT_MODELS = "chat.models"
    const val CHAT_SET_MODEL = "chat.setModel"
    const val CHAT_SET_PERMISSION_MODE = "chat.setPermissionMode"
    const val CHAT_COMMANDS = "chat.commands"
    const val CHAT_ITEMS = "chat.items"
    const val CHAT_PENDING = "chat.pending"
    const val CHAT_STATE = "chat.state"

    const val SESSION_CREATE = "session.create"
    const val SESSION_STOP = "session.stop"
    const val SESSION_REMOVE = "session.remove"
    const val DIRS_RECENT = "dirs.recent"
    const val USAGE_GET = "usage.get"

    const val GIT_STATUS = "git.status"
    const val GIT_LOG = "git.log"
    const val GIT_SHOW = "git.show"
    const val GIT_DIFF = "git.diff"
    const val GIT_BRANCHES = "git.branches"
    const val GIT_STAGE = "git.stage"
    const val GIT_UNSTAGE = "git.unstage"
    const val GIT_COMMIT = "git.commit"
    const val GIT_PULL = "git.pull"
    const val GIT_PUSH = "git.push"
    const val GIT_SWITCH = "git.switch"
    const val DIR_BUSY = "dir.busy"
}

/**
 * Times are sent as UTC whole seconds with a `Z`, but may carry an offset and
 * fractions. Instant.parse on API 29 and 30 only accepts `Z`, so parse through
 * OffsetDateTime.
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("clauderc.Instant", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): Instant = parseInstant(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeString(DateTimeFormatter.ISO_INSTANT.format(value))
}

fun parseInstant(text: String): Instant = OffsetDateTime.parse(text).toInstant()

/** Body of requests and responses that carry no data. */
@Serializable
class Empty {
    override fun equals(other: Any?) = other is Empty
    override fun hashCode() = 0
}
