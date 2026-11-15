package clauderc.app.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** Mirrors internal/protocol/chat.go. */
object ChatLimits {
    const val MAX_IMAGES = 5
    const val MAX_IMAGE_BYTES = 10L shl 20
    const val MAX_IMAGE_EDGE = 2048
    const val PAGE_SIZE = 50
}

object ItemKind {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val TOOL = "tool"
    /**
     * Grey text, not a bubble: a background task notification and the like as one centered line,
     * or, with a title, a message from another session under that title.
     */
    const val NOTICE = "notice"
    /** Where the user interrupted a turn; drawn as a centered 「已中断」. */
    const val INTERRUPTED = "interrupted"
    /** Replaces an item that is no longer shown, e.g. a queued message taken back on the computer. */
    const val REMOVED = "removed"
}

/** Claude Code's own permission-mode values, passed through as strings. */
object PermissionMode {
    const val DEFAULT = "default"
    const val ACCEPT_EDITS = "acceptEdits"
    const val PLAN = "plan"
    const val AUTO = "auto"
    const val BYPASS = "bypassPermissions"
    const val DONT_ASK = "dontAsk"

    /** The Shift+Tab cycle; only these can be switched to (design D7). */
    val switchable = listOf(DEFAULT, ACCEPT_EDITS, PLAN, AUTO)
}

object PendingKind {
    const val PERMISSION = "permission"
    const val QUESTION = "question"
}

object Decision {
    const val ALLOW = "allow"
    const val DENY = "deny"
}

/** An attached picture; [data] is base64. */
@Serializable
data class Image(val mediaType: String, val data: String)

/** One bubble or tool row; a later item with the same seq replaces the earlier one. */
@Serializable
data class ChatItem(
    val seq: Long,
    val kind: String,
    val text: String? = null,
    /** Set for a notice from another session, e.g. 「clauderc 发来」. */
    val title: String? = null,
    val images: List<Image>? = null,
    val tool: ToolCall? = null,
    /** True for a user message still waiting in Claude Code's queue. */
    val queued: Boolean? = null,
)

@Serializable
data class ToolCall(
    val toolUseId: String,
    val toolName: String,
    val summary: String = "",
    val input: JsonElement = JsonNull,
    val result: ToolResult? = null,
)

@Serializable
data class ToolResult(val content: String = "", val isError: Boolean = false)

@Serializable
data class Pending(
    val toolUseId: String,
    val kind: String,
    val toolName: String? = null,
    val input: JsonElement? = null,
    val questions: List<Question>? = null,
)

@Serializable
data class Question(
    val question: String,
    val header: String = "",
    val multiSelect: Boolean = false,
    val options: List<Option> = emptyList(),
)

@Serializable
data class Option(val label: String, val description: String = "")

@Serializable
data class ChatOpenRequest(val sessionId: String, val before: Long? = null)

/** Go embeds ChatStatus, so state, model and permissionMode sit at the top level. */
@Serializable
data class ChatOpenResponse(
    val items: List<ChatItem> = emptyList(),
    val hasMore: Boolean = false,
    val state: SessionState = SessionState.Idle,
    val model: String = "",
    val permissionMode: String = "",
    val pending: Pending? = null,
)

@Serializable
data class ChatSendRequest(
    val sessionId: String,
    val text: String,
    val images: List<Image>? = null,
)

/** [queued] is true when the message waits in Claude Code's queue until the current turn ends. */
@Serializable
data class ChatSendResponse(val queued: Boolean? = null)

@Serializable
data class Answer(val selected: List<Int>? = null, val custom: String? = null)

@Serializable
data class ChatAnswerRequest(
    val sessionId: String,
    val toolUseId: String,
    val decision: String? = null,
    val answers: List<Answer>? = null,
)

@Serializable
data class ChatSetModelRequest(val sessionId: String, val model: String)

@Serializable
data class ChatSetPermissionModeRequest(val sessionId: String, val mode: String)

@Serializable
data class Command(val name: String, val description: String = "")

@Serializable
data class ChatCommandsResponse(val commands: List<Command> = emptyList())

/** One entry of the /model picker; [id] is opaque and goes back as-is in chat.setModel. */
@Serializable
data class ModelOption(
    val id: String,
    val label: String = "",
    val description: String = "",
    val current: Boolean = false,
)

@Serializable
data class ChatModelsResponse(val models: List<ModelOption> = emptyList())

@Serializable
data class ChatItemsEvent(val sessionId: String, val items: List<ChatItem> = emptyList())

@Serializable
data class ChatPendingEvent(val sessionId: String, val pending: Pending? = null)

@Serializable
data class ChatStateEvent(
    val sessionId: String,
    val state: SessionState = SessionState.Idle,
    val model: String = "",
    val permissionMode: String = "",
)
