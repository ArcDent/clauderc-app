package clauderc.app.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import clauderc.app.protocol.Command
import clauderc.app.protocol.PermissionMode
import clauderc.app.protocol.ToolCall
import clauderc.app.ui.theme.MonoFamily
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** claude-opus-5-5 -> Opus 5.5; anything unfamiliar is shown as is. */
fun modelLabel(id: String): String {
    val m = Regex("""^claude-([a-z]+)-(\d+)(?:-(\d{1,2}))?(?:-\d{8})?(\[1m])?$""").matchEntire(id) ?: return id
    val (family, major, minor, long) = m.destructured
    val version = if (minor.isNotEmpty()) "$major.$minor" else major
    return family.replaceFirstChar { it.uppercase() } + " " + version + if (long.isNotEmpty()) " 1M" else ""
}

fun permissionModeLabel(mode: String): String = when (mode) {
    PermissionMode.DEFAULT -> "默认询问"
    PermissionMode.ACCEPT_EDITS -> "自动接受编辑"
    PermissionMode.PLAN -> "计划模式"
    PermissionMode.AUTO -> "自动模式"
    PermissionMode.BYPASS -> "跳过权限检查"
    PermissionMode.DONT_ASK -> "不询问"
    "" -> "权限模式"
    else -> mode
}

fun permissionModeHint(mode: String): String? = when (mode) {
    PermissionMode.DEFAULT -> "编辑和运行命令前都先问"
    PermissionMode.ACCEPT_EDITS -> "编辑直接执行，运行命令仍会问"
    PermissionMode.PLAN -> "只读分析，先给计划"
    else -> null
}

/**
 * Splits the agent's one-line summary into a grey verb and a mono argument,
 * e.g. 「运行 ls」. Summaries without a Chinese verb are shown whole as the argument.
 */
fun splitSummary(tool: ToolCall): Pair<String?, String> {
    val summary = tool.summary.ifBlank { tool.toolName }
    val first = summary.substringBefore(' ')
    val rest = summary.substringAfter(' ', "")
    return if (rest.isNotEmpty() && first.any { it.code > 0x2E7F }) first to rest else null to summary
}

/** Whether a tool row is a slash command the user ran; the agent sends those as tool rows named after the command. */
fun isCommand(tool: ToolCall): Boolean = tool.toolUseId.startsWith("command-")

/** Whether a failed tool result is the user's refusal rather than an error. */
fun isRefusal(tool: ToolCall): Boolean {
    val r = tool.result ?: return false
    return r.isError && (r.content.contains("doesn't want to proceed") || r.content.contains("rejected"))
}

/** The word the cursor is in when it starts with a slash, e.g. "/to" in "先看下 /to": its range in the text and what follows the slash. */
data class SlashWord(val start: Int, val end: Int, val query: String)

/** The slash word around [cursor], or null; a second slash, as in a path, means it is not a command. */
fun slashWord(text: String, cursor: Int): SlashWord? {
    if (cursor !in 1..text.length) return null
    var start = cursor
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    var end = cursor
    while (end < text.length && !text[end].isWhitespace()) end++
    if (start == cursor || text[start] != '/' || text.indexOf('/', start + 1) in start + 1 until end) return null
    return SlashWord(start, end, text.substring(start + 1, end))
}

/** The popup list for [word], or null to hide the popup; a slash word mid-message that matches nothing hides it too. */
fun filterCommands(commands: List<Command>, word: SlashWord?): List<Command>? {
    word ?: return null
    val q = word.query.lowercase()
    val hits = commands.filter { it.name.lowercase().contains(q) }
    return if (hits.isEmpty() && word.start > 0) null else hits
}

/** [value] with [word] replaced by "/[name] " and the cursor after the space. */
fun pickCommand(value: TextFieldValue, word: SlashWord, name: String): TextFieldValue {
    val before = value.text.substring(0, word.start) + "/$name "
    val rest = value.text.substring(word.end).let { if (it.firstOrNull()?.isWhitespace() == true) it.drop(1) else it }
    return TextFieldValue(before + rest, TextRange(before.length))
}

/** Whether a typed message runs /model, which the phone answers with its own model picker; the agent refuses it anyway. */
fun isModelCommand(text: String): Boolean =
    text.trim().split(Regex("\\s+")).first().equals("/model", ignoreCase = true)

fun JsonElement?.stringField(name: String): String? =
    ((this as? JsonObject)?.get(name) as? JsonPrimitive)?.contentOrNull

/** Heading and code block of the permission panel, per tool. */
fun permissionPrompt(toolName: String, input: JsonElement?): Pair<String, String> = when (toolName) {
    "Bash" -> "运行这条命令？" to (input.stringField("command") ?: prettyJson(input))
    "Edit", "MultiEdit", "Write", "NotebookEdit" -> "修改这个文件？" to (input.stringField("file_path") ?: input.stringField("notebook_path") ?: prettyJson(input))
    "WebFetch" -> "访问这个网址？" to (input.stringField("url") ?: prettyJson(input))
    else -> "允许使用 $toolName？" to prettyJson(input)
}

private val pretty = kotlinx.serialization.json.Json { prettyPrint = true }

fun prettyJson(e: JsonElement?): String = when (e) {
    null -> ""
    is JsonPrimitive -> e.contentOrNull ?: "null"
    else -> pretty.encodeToString(JsonElement.serializer(), e)
}

/** Key and value pairs for the expanded tool row: the input fields, then the output. */
fun toolDetail(tool: ToolCall): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    val input = tool.input
    when {
        tool.toolName == "Bash" && input.stringField("command") != null -> out += "命令" to input.stringField("command")!!
        input is JsonObject -> input.forEach { (k, v) -> out += k to (if (v is JsonPrimitive) v.jsonPrimitive.contentOrNull ?: "null" else prettyJson(v)) }
        input !is kotlinx.serialization.json.JsonNull -> out += "输入" to prettyJson(input)
    }
    tool.result?.let { out += "输出" to it.content.ifEmpty { "没有输出" } }
    return out
}

/**
 * A light Markdown pass for assistant text: `code`, **bold** and fenced
 * blocks. Anything else stays as written.
 */
fun richText(text: String, codeColor: Color): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in fence.findAll(text)) {
        inline(text.substring(at, m.range.first), codeColor)
        withStyle(SpanStyle(fontFamily = MonoFamily, fontSize = 0.86.em, color = codeColor)) { append(m.groupValues[1].trimEnd('\n')) }
        at = m.range.last + 1
    }
    inline(text.substring(at), codeColor)
}

// Compiled once: richText runs for every reply that scrolls into view.
private val fence = Regex("```[^\\n]*\\n([\\s\\S]*?)```")
private val token = Regex("`([^`\\n]+)`|\\*\\*([^*\\n]+)\\*\\*")

private fun AnnotatedString.Builder.inline(s: String, codeColor: Color) {
    var at = 0
    for (m in token.findAll(s)) {
        append(s.substring(at, m.range.first))
        if (m.groupValues[1].isNotEmpty()) {
            withStyle(SpanStyle(fontFamily = MonoFamily, fontSize = 0.86.em, color = codeColor)) { append(m.groupValues[1]) }
        } else {
            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(m.groupValues[2]) }
        }
        at = m.range.last + 1
    }
    append(s.substring(at))
}
