package clauderc.app.protocol

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** Mirrors internal/protocol/chat.go. */
object ChatLimits {
    const val MAX_IMAGE_BYTES = 10L shl 20
    const val MAX_IMAGES_TOTAL_BYTES = 11L shl 20
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

    /**
     * Modes offered in the phone picker. BYPASS only succeeds when the session was started with
     * bypass enabled; otherwise the agent returns an error.
     */
    val switchable = listOf(DEFAULT, ACCEPT_EDITS, PLAN, AUTO, BYPASS)
}

object PendingKind {
    const val PERMISSION = "permission"
    const val QUESTION = "question"
    /** A Claude Code dialog such as the MCP server approval: one single-choice question, answered with one pick. */
    const val DIALOG = "dialog"
}

object Decision {
    const val ALLOW = "allow"
    const val DENY = "deny"
}

/**
 * An attached picture on chat.send or a user message thumbnail on chat.open / chat.items; [data] is base64.
 * When [id] is set, [data] is a thumbnail and chat.image fetches the original by [id].
 */
@Serializable
data class Image(
    val mediaType: String,
    val data: String,
    val id: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long? = null,
)

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
    /** A user message's uuid in the transcript, which chat.rewind names; absent while it is queued. */
    val uuid: String? = null,
)

@Serializable
data class ToolCall(
    val toolUseId: String,
    val toolName: String,
    val summary: String = "",
    val input: JsonElement = JsonNull,
    val result: ToolResult? = null,
    /** Set for an Agent call: the subagent it started. */
    val agent: Subagent? = null,
)

/**
 * A subagent a session started with the Agent tool. [id] names its transcript, which
 * chat.open with it as agentId opens; null until Claude Code reports it. [running] is
 * false once it finished, failed or was stopped.
 */
@Serializable
data class Subagent(
    val id: String? = null,
    val type: String = "",
    val description: String = "",
    val running: Boolean = false,
    @Serializable(with = InstantSerializer::class) val startedAt: Instant = Instant.EPOCH,
)

/**
 * [patch] is set for Edit, MultiEdit and Write (a new file is one hunk of added lines);
 * [lines] for Read of a text file, how many lines it read. [truncated] marks a result
 * whose text or diff was cut to 8 KB; chat.toolOutput returns all of it. [images] are
 * the pictures in the result, such as a Read of a PNG.
 */
@Serializable
data class ToolResult(
    val content: String = "",
    val isError: Boolean = false,
    val patch: List<Hunk>? = null,
    val lines: Int? = null,
    val truncated: Boolean? = null,
    val images: List<ToolImage>? = null,
)

/**
 * A picture in a tool result, or one Claude sent with `clauderc show`, which has its file [name]:
 * [thumb], base64, is at most 360 pixels on its longest side, or null for a format the agent
 * cannot read, such as WebP; chat.image fetches the picture by [id].
 */
@Serializable
data class ToolImage(
    val id: String,
    val mediaType: String,
    val name: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long = 0,
    val thumb: String? = null,
)

/** Asks for a picture of a tool result by its id; one of a subagent's tool results when [agentId] is set. */
@Serializable
data class ChatImageRequest(val sessionId: String, val id: String, val agentId: String? = null)

/** The picture as it was; [data] is base64. */
@Serializable
data class ChatImageResponse(val mediaType: String, val data: String)

/** Asks for the whole result of one tool call; the response body is a [ToolResult]. */
@Serializable
data class ChatToolOutputRequest(val sessionId: String, val toolUseId: String)

/** One diff block as in Claude Code's structuredPatch; each line starts with " ", "-" or "+". */
@Serializable
data class Hunk(
    val oldStart: Int = 0,
    val oldLines: Int = 0,
    val newStart: Int = 0,
    val newLines: Int = 0,
    val lines: List<String> = emptyList(),
)

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
data class ChatOpenRequest(
    val sessionId: String,
    val before: Long? = null,
    /** Opens the session's subagent with this [Subagent.id] instead, read only. */
    val agentId: String? = null,
)

/** chat.close; [agentId] closes a subagent's chat, [subId] names the subscription returned by chat.open. */
@Serializable
data class ChatCloseRequest(val sessionId: String, val agentId: String? = null, val subId: String? = null)

/** Go embeds ChatStatus, so state, model and permissionMode sit at the top level. */
@Serializable
data class ChatOpenResponse(
    val items: List<ChatItem> = emptyList(),
    val hasMore: Boolean = false,
    val state: SessionState = SessionState.Idle,
    val model: String = "",
    val permissionMode: String = "",
    val pending: Pending? = null,
    /** True while the session is blocked on a panel or dialog the agent does not know. */
    val unknownDialog: Boolean? = null,
    /** Claude Code's status line while it works, e.g. 「Whirring… (20s · ↓ 2.4k tokens)」. */
    val activity: String? = null,
    /** The reply drawn on the computer's screen so far, not yet an item. */
    val partial: String? = null,
    /** Set while the session waits out a plan usage limit whose reset time is known. */
    val limit: Limit? = null,
    /** The session's Claude Code task list, oldest task first; absent when it has none. */
    val tasks: List<ChatTask>? = null,
    val rev: Long? = null,
    val subId: String? = null,
)

/** Where a task of Claude Code's task list stands. */
object TaskStatus {
    const val PENDING = "pending"
    const val IN_PROGRESS = "in_progress"
    const val COMPLETED = "completed"
}

/** One task of the session's task list; [activeForm] is what the status line says while it is in progress. */
@Serializable
data class ChatTask(val id: String, val subject: String, val activeForm: String? = null, val status: String)

/** chat.tasks: the session's task list whenever it changed, whole; empty once Claude Code cleared it. */
@Serializable
data class ChatTasksEvent(val sessionId: String, val tasks: List<ChatTask>, val rev: Long? = null)

/** The plan usage limit a session ran into, as in Claude Code's 「You've hit your 5-hour limit · resets 10:40pm」. */
@Serializable
data class Limit(
    /** 「fiveHour」 or 「weekly」, as in usage.get; null for another limit. */
    val window: String? = null,
    /** Claude Code's message. */
    val text: String = "",
    @Serializable(with = InstantSerializer::class) val resetsAt: Instant = Instant.EPOCH,
    /** Set while the agent is to send a message by itself once the limit is lifted. */
    val autoResume: AutoResume? = null,
)

/** A message the agent sends once the limit is lifted; [at] is a minute after the reset. */
@Serializable
data class AutoResume(
    val text: String,
    @Serializable(with = InstantSerializer::class) val at: Instant,
)

/** chat.autoResume: turns the message sent once the session's limit is lifted on, with [text], or off. */
@Serializable
data class ChatAutoResumeRequest(val sessionId: String, val on: Boolean, val text: String? = null)

/** The limit as it is after chat.autoResume; null when the session no longer waits on one. */
@Serializable
data class ChatAutoResumeResponse(val limit: Limit? = null)

/** What chat.rewind takes back, as Claude Code's /rewind panel offers it. */
object RewindRestore {
    const val BOTH = "both"
    const val CONVERSATION = "conversation"
    const val CODE = "code"
}

/** chat.rewind: back to before the user message [uuid], taking back what [restore] names. */
@Serializable
data class ChatRewindRequest(val sessionId: String, val uuid: String, val restore: String)

/** Answers a rewind that happened; [notice] says what went wrong after it, such as the computer's input box left holding the message. */
@Serializable
data class ChatRewindResponse(val notice: String? = null)

@Serializable
data class ChatSendRequest(
    val sessionId: String,
    val text: String,
    val images: List<Image>? = null,
    val clientReqId: String? = null,
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
data class Command(
    val name: String,
    val description: String = "",
    /** True for a built-in that opens a panel: sent as is, then the terminal view opens. */
    val panel: Boolean? = null,
)

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
data class ChatItemsEvent(
    val sessionId: String,
    val items: List<ChatItem> = emptyList(),
    /** Set for a subagent's chat. */
    val agentId: String? = null,
    val rev: Long? = null,
    /** True after /clear when the session switched to a fresh transcript: replace shown items. */
    val reset: Boolean? = null,
)

@Serializable
data class ChatPendingEvent(val sessionId: String, val pending: Pending? = null, val rev: Long? = null)

@Serializable
data class ChatStateEvent(
    val sessionId: String,
    val state: SessionState = SessionState.Idle,
    val model: String = "",
    val permissionMode: String = "",
    /** Set for a subagent's chat: working while it runs, idle once it ended. */
    val agentId: String? = null,
    /** True while the session is blocked on a panel or dialog the agent does not know. */
    val unknownDialog: Boolean? = null,
    /** Claude Code's status line while it works, e.g. 「Whirring… (20s · ↓ 2.4k tokens)」. */
    val activity: String? = null,
    /** The reply drawn on the computer's screen so far, not yet an item. */
    val partial: String? = null,
    /** Set while the session waits out a plan usage limit whose reset time is known. */
    val limit: Limit? = null,
    val rev: Long? = null,
)
