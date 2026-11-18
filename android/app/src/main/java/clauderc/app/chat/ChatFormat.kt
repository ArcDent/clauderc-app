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
import clauderc.app.protocol.ChatItem
import clauderc.app.protocol.Command
import clauderc.app.protocol.PermissionMode
import clauderc.app.protocol.ToolCall
import clauderc.app.protocol.ToolImage
import clauderc.app.protocol.ToolResult
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

/** Whether a tool row is a message this session sent to another one; the agent sends it as a tool row named SendMessage. */
fun isSendMessage(tool: ToolCall): Boolean = tool.toolName == "SendMessage"

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
/** Whether [text] is one of the panel commands among [commands], with nothing after it. */
fun isPanelCommand(commands: List<Command>, text: String): Boolean =
    commands.any { it.panel == true && "/" + it.name == text.trim() }

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

enum class RowKind { SAME, ADD, DEL, GAP }

/** One line of a tool row's diff view; [line] is the old line number for a deleted line, the new one otherwise. */
data class PatchRow(val kind: RowKind, val line: Int = 0, val text: String = "")

/**
 * The diff view of a tool result, as Claude Code shows it: an Edit's hunks with a gap row between
 * them, a Write's lines all added, a Read's lines as read. Null for any other result, which keeps
 * its plain detail.
 */
fun patchRows(result: ToolResult?): List<PatchRow>? {
    result ?: return null
    result.patch?.takeIf { it.isNotEmpty() }?.let { hunks ->
        val rows = mutableListOf<PatchRow>()
        hunks.forEachIndexed { i, h ->
            if (i > 0) rows += PatchRow(RowKind.GAP)
            var old = h.oldStart
            var new = h.newStart
            for (l in h.lines) {
                val text = l.drop(1)
                rows += when (l.firstOrNull()) {
                    '-' -> PatchRow(RowKind.DEL, old++, text)
                    '+' -> PatchRow(RowKind.ADD, new++, text)
                    else -> PatchRow(RowKind.SAME, new++, text).also { old++ }
                }
            }
        }
        return rows
    }
    if (result.lines == null) return null
    // A Read result is "<line>\t<text>" per line, older Claude Code wrote "<line>→<text>";
    // what follows the numbered lines, such as a system reminder, is not part of the file.
    val rows = result.content.lineSequence().map { readLine.matchEntire(it) }.takeWhile { it != null }
        .map { PatchRow(RowKind.SAME, it!!.groupValues[1].toInt(), it.groupValues[2]) }.toList()
    return rows.ifEmpty { null }
}

private val readLine = Regex("""^\s*(\d+)[\t→](.*)$""")

/**
 * How many lines the diff view has in all, gap rows not counted, or null when that is not known:
 * a diff cut to 8 KB that is not all added lines.
 */
fun patchTotal(result: ToolResult, rows: List<PatchRow>): Int? = when {
    result.lines != null -> result.lines
    result.truncated != true -> rows.count { it.kind != RowKind.GAP }
    result.patch.orEmpty().all { it.oldLines == 0 } -> result.patch.orEmpty().sumOf { it.newLines }
    else -> null
}

/** The figures at the end of a diff tool row: lines added and deleted, or lines read; null when not known. */
data class PatchStat(val added: Int = 0, val deleted: Int = 0, val read: Int? = null)

fun patchStat(result: ToolResult, rows: List<PatchRow>): PatchStat? = when {
    result.lines != null -> PatchStat(read = result.lines)
    result.truncated != true -> PatchStat(rows.count { it.kind == RowKind.ADD }, rows.count { it.kind == RowKind.DEL })
    else -> patchTotal(result, rows)?.let { PatchStat(added = it) }
}

/**
 * Whether a tool call joins a merged row: a search, a read or a command; not a slash command,
 * `clauderc show` or a call whose result has pictures, which are shown under it.
 */
fun isMergeable(tool: ToolCall?): Boolean = when {
    tool == null || isCommand(tool) || !tool.result?.images.isNullOrEmpty() -> false
    tool.toolName == "Grep" || tool.toolName == "Glob" || tool.toolName == "Read" -> true
    tool.toolName == "Bash" -> !runsShow(tool)
    else -> false
}

/** Whether a command runs `clauderc show`, however it is written: by name, by full path, after a `cd`. */
private fun runsShow(tool: ToolCall): Boolean = tool.input.stringField("command").orEmpty().contains("clauderc show ")

/**
 * The items as the chat list draws them: two or more mergeable tool calls in a row become one
 * entry, as the CLI merges them; anything else, a reply, an edit or a write, ends the run.
 */
fun chatRuns(items: List<ChatItem>): List<List<ChatItem>> {
    val runs = mutableListOf<MutableList<ChatItem>>()
    for (item in items) {
        val run = runs.lastOrNull()
        if (run != null && isMergeable(item.tool) && isMergeable(run.last().tool)) run += item else runs += mutableListOf(item)
    }
    return runs
}

/** A merged row's counts, as the CLI words them: 「搜索 1 个模式，读取 2 个文件，列出 1 个目录，运行 1 条命令」. */
fun groupSummary(tools: List<ToolCall>): String {
    val n = IntArray(groupWords.size)
    for (t in tools) {
        n[
            when {
                t.toolName == "Grep" || t.toolName == "Glob" -> 0
                t.toolName == "Read" -> 1
                listing.containsMatchIn(t.input.stringField("command").orEmpty().trimStart()) -> 2
                else -> 3
            },
        ]++
    }
    return groupWords.indices.filter { n[it] > 0 }.joinToString("，") { "${groupWords[it].first} ${n[it]} ${groupWords[it].second}" }
}

private val groupWords = listOf("搜索" to "个模式", "读取" to "个文件", "列出" to "个目录", "运行" to "条命令")
private val listing = Regex("""^ls(\s|$)""")

/** Whether a finished tool call is `clauderc show`: its pictures stand for it, without the command. */
fun isShow(tool: ToolCall): Boolean = tool.toolName == "Bash" && runsShow(tool) && !tool.result?.images.isNullOrEmpty()

/** 「PNG」 for image/png and so on. */
fun imageType(mediaType: String): String = when (mediaType) {
    "image/png" -> "PNG"
    "image/jpeg" -> "JPEG"
    "image/gif" -> "GIF"
    "image/webp" -> "WebP"
    else -> mediaType.substringAfter('/').uppercase()
}

/** 「212 KB」, 「1.2 MB」. */
fun byteSize(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${(n + 512) / 1024} KB"
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", n / (1024.0 * 1024.0))
}

/** The line under a picture's name in the viewer: 「720 × 1560 · PNG · 212 KB」, without the size when it is not known. */
fun imageLabel(img: ToolImage): String = listOfNotNull(
    if ((img.width ?: 0) > 0 && (img.height ?: 0) > 0) "${img.width} × ${img.height}" else null,
    imageType(img.mediaType).ifEmpty { null },
    if (img.size > 0) byteSize(img.size) else null,
).joinToString(" · ")

/** The thumbnail's size in pixels: the picture's, scaled to 360 on its longest side; null when not known. */
fun thumbPixels(img: ToolImage): Pair<Int, Int>? {
    val w = img.width ?: return null
    val h = img.height ?: return null
    if (w <= 0 || h <= 0) return null
    val edge = maxOf(w, h)
    return if (edge <= THUMB_EDGE) w to h else maxOf(1, w * THUMB_EDGE / edge) to maxOf(1, h * THUMB_EDGE / edge)
}

private const val THUMB_EDGE = 360

/** Edits and writes show their diff when they come in; reads and the rest start folded. */
fun opensByDefault(result: ToolResult?): Boolean = result?.patch?.isNotEmpty() == true

/**
 * Splits off the status lines a background session writes on their own line for Claude Code's
 * job list, "result:", "needs input:" or "failed:", outside fenced code. Returns the reply
 * without them, blank lines around them squeezed, and the lines as written, in order.
 */
fun splitMarkers(text: String): Pair<String, List<String>> {
    if (markerPrefixes.none { text.contains(it) }) return text to emptyList()
    val kept = mutableListOf<String>()
    val marks = mutableListOf<String>()
    var fenced = false
    for (line in text.lines()) {
        val t = line.trim()
        if (t.startsWith("```")) fenced = !fenced
        if (!fenced && markerPrefixes.any { t.startsWith(it) }) marks += t else kept += line
    }
    if (marks.isEmpty()) return text to emptyList()
    return kept.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim() to marks
}

private val markerPrefixes = listOf("result:", "needs input:", "failed:")

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
