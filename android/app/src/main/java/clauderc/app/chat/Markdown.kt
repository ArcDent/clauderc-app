package clauderc.app.chat

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

enum class RunKind { TEXT, CODE, BOLD, LINK, MATH }

/**
 * A stretch of a block's text as it shows, its Markdown marks taken off; a link's [url] is where it goes.
 * A formula's [text] is as written, delimiters and all, which is what copying it gives; [tex] is inside them.
 */
data class MdRun(val kind: RunKind, val text: String, val url: String? = null, val bold: Boolean = false)

/**
 * A reply cut into the blocks the chat draws (demo.html mdBlocks): CommonMark finds the blocks,
 * and the text inside them only knows `code`, **bold** and links.
 */
sealed interface MdBlock {
    /** A paragraph; a single line break inside it stays a line break. */
    data class Paragraph(val runs: List<MdRun>) : MdBlock

    /** A heading, level 1 to 3; deeper ones are drawn as 3. */
    data class Heading(val level: Int, val runs: List<MdRun>) : MdBlock

    data class Quote(val blocks: List<MdBlock>) : MdBlock

    /** A list whose items are the blocks inside them, a nested list or code among them; [start] numbers an ordered one. */
    data class ItemList(val ordered: Boolean, val start: Int, val items: List<List<MdBlock>>) : MdBlock

    /** Fenced or indented code as written; [lang] is the fence's first word, possibly empty. */
    data class Code(val lang: String, val code: String) : MdBlock

    /** A table: the header's cells, then each row's. */
    data class Table(val head: List<List<MdRun>>, val rows: List<List<List<MdRun>>>) : MdBlock

    /** A formula on lines of its own, `$$…$$` or `\[…\]`: [tex] inside the delimiters, [written] with them. */
    data class Math(val tex: String, val written: String) : MdBlock
}

/** A formula run's LaTeX, without the delimiters it was written with. */
val MdRun.tex: String
    get() = when {
        text.startsWith("$$") -> text.removeSurrounding("$$")
        text.startsWith("\\(") -> text.removePrefix("\\(").removeSuffix("\\)")
        text.startsWith("\\[") -> text.removePrefix("\\[").removeSuffix("\\]")
        else -> text.removeSurrounding("$")
    }.trim()

// Block spans tell where each block's lines are in the reply; inline spans give each table cell's.
private val parser = Parser.builder()
    .extensions(listOf(TablesExtension.create()))
    .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
    .build()

private const val PipeInMath = '\uE000'
private const val FENCE = """^(?<quote>(?: {0,3}>[ \t]?)*)(?<indent> {0,3})(?<item>(?:[-+*]|\d{1,9}[.)])[ \t]+)?(?<fence>`{3,}+[^`\r\n]*|~{3,}+[^\r\n]*)$"""
private val fenceLine = Regex("(?m)$FENCE")
private const val ENVIRONMENTS = "aligned|align\\*?|gather(?:ed)?|equation\\*?|cases|[pbBvV]?matrix|array"
private const val BARE_ENVIRONMENT = """^\\begin\{(?:$ENVIRONMENTS)\}"""
internal val mathEnvironment = Regex("""\\\\|\\(begin|end)(?![A-Za-z])(?:\s*\{([^{}]*)\})?""")
private val endOfLine = Regex("""[ \t]*(?=\r|\n|$)""")

private fun displayMath(multiline: Boolean): String {
    fun body(close: String): String {
        val beforeClose = """(?:(?!$close)[\s\S])"""
        return if (multiline) """(?:(?!$close)[^\r\n])*+\R$beforeClose*+""" else "$beforeClose++"
    }
    val dollar = """\$\$"""
    val bracket = """\\\]"""
    return """(?<display>$dollar(?<dollars>${body(dollar)})$dollar|\\\[(?<bracket>${body(bracket)})$bracket)[ \t]*$"""
}

private val blockMath = Regex("(?m)^[ \\t]*${displayMath(multiline = false)}")
// A display may sit up to three spaces in, as CommonMark allows a block; four start indented code.
private val sourceToken = Regex("(?m)$FENCE|^ {0,3}${displayMath(multiline = true)}|(?<environment>$BARE_ENVIRONMENT)|$MARKS")

private fun fenceEnd(s: String, open: MatchResult): Int {
    val quoteDepth = open.groups["quote"]!!.value.count { it == '>' }
    val quotes = "(?: {0,3}>[ \\t]?){$quoteDepth}"
    val item = open.groups["item"]?.value
    val indent = if (item == null) 0 else open.groups["indent"]!!.value.length + item.length
    val mark = open.groups["fence"]!!.value
    val ticks = mark.takeWhile { it == mark[0] }
    val close = "$quotes {$indent,${indent + 3}}${ticks.first()}{${ticks.length},}[ \\t]*$"
    val dedent = if (quoteDepth > 0 || item != null) "|^(?!$quotes {$indent}|$quotes[ \\t]*$)" else ""
    val end = Regex("(?m)^$close$dedent").find(s, open.range.last + 1) ?: return s.length
    return end.range.last + 1
}

private fun bareMath(s: String, open: MatchResult): MdBlock.Math? {
    val stack = ArrayDeque<String>()
    for (m in mathEnvironment.findAll(s, open.range.first)) {
        val kind = m.groups[1]?.value ?: continue
        val name = m.groups[2]?.value?.trim() ?: return null
        if (kind == "begin") stack.addLast(name)
        else if (stack.removeLastOrNull() != name) return null
        if (stack.isEmpty()) {
            val end = endOfLine.matchAt(s, m.range.last + 1) ?: return null
            val written = s.substring(open.range.first, end.range.last + 1)
            return MdBlock.Math(written, written)
        }
    }
    return null
}

/** [text] as blocks, with the reply's top-level math taken out before CommonMark splits the lines. */
fun mdBlocks(text: String): List<MdBlock> = buildList {
    val pipe = if (PipeInMath !in text) PipeInMath else ('\uE001'..'\uF8FF').firstOrNull { it !in text }
    var start = 0
    var at = 0
    var masked: StringBuilder? = null
    fun prose(end: Int) {
        if (end <= start) return
        val original = text.substring(start, end)
        val source = masked?.substring(start, end) ?: original
        addAll(blocksIn(parser.parse(source), original, pipe))
    }
    while (at < text.length) {
        val m = sourceToken.find(text, at) ?: break
        at = m.range.last + 1
        when {
            m.groups["fence"] != null -> at = fenceEnd(text, m)
            m.groups["ticks"] != null -> codeClose(text, m)?.let { at = it.range.last + 1 }
            m.groups["display"] != null || m.groups["environment"] != null -> {
                // Markdown markers and blank lines inside a display belong to TeX, not to Markdown.
                val block = if (m.groups["display"] != null) m.toMath() else bareMath(text, m)
                if (block != null) {
                    val next = m.range.first + block.written.length
                    if (fenceLine.containsMatchIn(block.written)) {
                        at = m.range.first + 1
                        continue
                    }
                    prose(m.range.first)
                    add(block)
                    start = maxOf(at, next)
                    at = start
                }
            }
            m.groups["math"] != null && pipe != null -> {
                if (!isMath(m.value)) {
                    at = m.range.first + 1
                    continue
                }
                // Protect table separators without shifting spans; reads of the spans use the original pipes.
                for (i in m.range) if (text[i] == '|') {
                    val source = masked ?: StringBuilder(text).also { masked = it }
                    source.setCharAt(i, pipe)
                }
            }
        }
    }
    prose(text.length)
}

private fun blocksIn(parent: Node, src: String, pipe: Char?): List<MdBlock> = children(parent).flatMap { n ->
    if (n is Paragraph) paragraph(lines(n, src)) else listOfNotNull(blockOf(n, src, pipe))
}

/**
 * A paragraph's text, cut where a formula takes lines of its own: one opening with `$$` or `\[` at a
 * line's start and closing at a line's end. Replies often put it right under a line of text, so it
 * shares that text's paragraph.
 */
private fun paragraph(text: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    fun prose(t: String) {
        if (t.isNotBlank()) out += MdBlock.Paragraph(mdRuns(t))
    }
    var at = 0
    val code = inlineMarks(text).filter { it.kind == RunKind.CODE }.map { it.range }.iterator()
    var span = if (code.hasNext()) code.next() else null
    for (m in blockMath.findAll(text)) {
        while (span != null && span.last < m.range.first) span = if (code.hasNext()) code.next() else null
        if (span != null && span.first <= m.range.last) continue
        prose(text.substring(at, m.range.first).trim('\n'))
        out += m.toMath()
        at = m.range.last + 1
    }
    prose(text.substring(at).trimStart('\n'))
    return out
}

private fun MatchResult.toMath() = MdBlock.Math((groups["dollars"] ?: groups["bracket"])!!.value.trim(), groups["display"]!!.value)

private fun String.restorePipes(pipe: Char?) = if (pipe == null) this else replace(pipe, '|')

private fun blockOf(n: Node, src: String, pipe: Char?): MdBlock? = when (n) {
    is Heading -> MdBlock.Heading(minOf(n.level, 3), mdRuns(headingText(n, src)))
    is BlockQuote -> MdBlock.Quote(blocksIn(n, src, pipe))
    is BulletList -> MdBlock.ItemList(false, 1, children(n).map { blocksIn(it, src, pipe) })
    is OrderedList -> MdBlock.ItemList(true, n.markerStartNumber ?: 1, children(n).map { blocksIn(it, src, pipe) })
    is FencedCodeBlock -> MdBlock.Code(n.info.orEmpty().trim().substringBefore(' '), n.literal.restorePipes(pipe).removeSuffix("\n"))
    is IndentedCodeBlock -> MdBlock.Code("", n.literal.restorePipes(pipe).removeSuffix("\n"))
    is TableBlock -> MdBlock.Table(
        children(n).filterIsInstance<TableHead>().flatMap(::children).firstOrNull()?.let { cells(it, src) }.orEmpty(),
        children(n).filterIsInstance<TableBody>().flatMap(::children).map { cells(it, src) },
    )
    // HTML is shown as written; a rule and a link definition have nothing to show.
    is HtmlBlock -> MdBlock.Paragraph(listOf(MdRun(RunKind.TEXT, n.literal.restorePipes(pipe).trimEnd())))
    else -> null
}

private fun children(n: Node): List<Node> = generateSequence(n.firstChild) { it.next }.toList()

/** A block's lines as written, without what its list or quote puts in front of them. */
private fun lines(n: Node, src: String): String =
    n.sourceSpans.joinToString("\n") { src.substring(it.inputIndex, it.inputIndex + it.length).trimEnd() }

/** An ATX heading's text without its #s; a setext heading's lines without the underline. */
private fun headingText(n: Heading, src: String): String {
    val written = lines(n, src).lines()
    val first = written.first().trimStart()
    return if (first.startsWith("#")) first.replace(atxOpen, "").replace(atxClose, "").trim()
    else written.dropLast(1).joinToString("\n") { it.trim() }
}

private val atxOpen = Regex("""^#{1,6}(\s+|$)""")
private val atxClose = Regex("""(^|\s+)#+$""")

private fun cells(row: Node, src: String): List<List<MdRun>> = children(row).map { mdRuns(lines(it, src).trim()) }

/**
 * A block's text as runs: `code`, formulas, **bold**, links and plain text, backslash escapes taken off
 * outside code and formulas. A link is [text](url) or a bare http(s) address; nothing else counts as
 * Markdown inside a block, so snake_case and __init__ stay as written, and paths and bare domains are
 * not links. A formula is `\(…\)`, `$$…$$` or `$…$`; see [MATH] for which dollars count.
 */
fun mdRuns(s: String): List<MdRun> = runs(s, inlineToken)

/** The user's own message as runs: only its links, the rest as typed (demo.html meHtml). */
fun linkRuns(s: String): List<MdRun> = runs(s, linkToken)

private fun runs(s: String, re: Regex, bold: Boolean = false): List<MdRun> {
    val out = mutableListOf<MdRun>()
    val plainKind = if (bold) RunKind.BOLD else RunKind.TEXT
    fun plain(s: String) = if (re === linkToken) s else unescape(s)
    fun text(t: String) {
        if (t.isEmpty()) return
        val last = out.lastOrNull()
        if (last?.kind == plainKind) {
            out[out.size - 1] = last.copy(text = last.text + plain(t))
        } else out += MdRun(plainKind, plain(t))
    }
    var at = 0
    var end = 0
    for (mark in inlineMarks(s, re)) {
        val m = mark.match
        if (m.range.first < end) continue
        end = mark.range.last + 1
        val token = when (mark.kind) {
            RunKind.CODE -> listOf(MdRun(RunKind.CODE, codeText(s.substring(m.range.last + 1, mark.close))))
            RunKind.MATH -> listOf(MdRun(RunKind.MATH, m.value, bold = bold))
            RunKind.BOLD -> {
                val close = inlineMarks(s, boldToken, end).firstOrNull { it.kind == RunKind.BOLD } ?: continue
                val inner = s.substring(end, close.range.first)
                if (inner.isEmpty() || '\n' in inner || '\r' in inner) continue
                end = close.range.last + 1
                runs(inner, marksToken, bold = true)
            }
            RunKind.LINK -> m.groups["ltext"]?.let { listOf(MdRun(RunKind.LINK, plain(it.value), m.groups["lurl"]!!.value)) } ?: run {
                val bare = m.groups["bare"]!!.value
                val url = trimUrl(bare)
                end -= bare.length - url.length
                listOf(MdRun(RunKind.LINK, url, url))
            }
            else -> continue
        }
        text(s.substring(at, m.range.first))
        out += token
        at = end
    }
    text(s.substring(at))
    return out
}

/** A bare address without the punctuation stuck to its end, and without a closing bracket it does not open. */
private fun trimUrl(u: String): String {
    var end = u.length
    while (end > 0) {
        val c = u[end - 1]
        if (c in ".,;:!?'\"") end--
        else if (c == ')' && u.take(end).count { it == ')' } > u.take(end).count { it == '(' }) end--
        else break
    }
    return u.take(end)
}

// Compiled once: replies are cut into blocks each time one scrolls into view. A [text](url) address may
// hold brackets in pairs, as CommonMark allows; a bare one stops at Chinese text and full-width
// punctuation, which in a reply is the sentence going on.
private const val LINK = """\[(?<ltext>[^\]\n]+)\]\((?<lurl>(?:[^()\s]|\([^()\s]*\))+)\)|(?<bare>https?://[^\s<>"'`\u3000-\u303f\u4e00-\u9fff\uff00-\uffef]+)"""

// A dollar before a name or number can be the next opener, not a close; CJK prose is not single-dollar TeX.
private const val MATH = """\$\$[^$\r\n]++\$\$|\\\(.+?\\\)|\\\[.+?\\\]|(?<!\$)\$(?!\s)(?:\\[^\r\n]|[^\\$\r\n])++(?<!\s)\$(?![\w$])"""
private const val ESCAPED = """\\([!-/:-@\[-`{-~])"""
private const val MARKS = """(?<ticks>`++)|(?<math>$MATH)|$ESCAPED"""
private const val BOLD = """(?<bold>\*\*)"""
private val inlineToken = Regex("$MARKS|$BOLD|$LINK")
private val boldToken = Regex("$MARKS|$BOLD")
private val marksToken = Regex(MARKS)
private val linkToken = Regex(LINK)
private val escaped = Regex(ESCAPED)
private val ticks = Regex("`++")
private val codeBoundary = Regex("""(?:\r\n?+|\n)[ \t]*(?:\r\n?|\n)""")
private val codeNewline = Regex("""\r\n?|\n""")
private val cjk = Regex("""[\p{sc=Han}\u3000-\u303f\uff00-\uffef]""")
private val texPart = Regex("""\\text\{|\\[^\r\n]|[{}]|${cjk.pattern}""")

private fun isMath(written: String): Boolean {
    if (!written.startsWith('$') || written.startsWith("$$") || !cjk.containsMatchIn(written)) return true
    var depth = 0
    for (m in texPart.findAll(written)) when (val part = m.value) {
        "\\text{" -> depth++
        "{" -> if (depth > 0) depth++ else Unit
        "}" -> if (depth > 0) depth-- else Unit
        else -> if (depth == 0 && cjk.matches(part)) return false
    }
    return true
}

private fun codeClose(s: String, open: MatchResult): MatchResult? {
    val at = open.range.last + 1
    val close = ticks.findAll(s, at).firstOrNull { it.value.length == open.value.length } ?: return null
    val code = s.substring(at, close.range.last + 1)
    val newline = codeNewline.find(code) ?: return close
    return close.takeUnless {
        codeBoundary.containsMatchIn(code) || fenceLine.find(s, at + newline.range.last + 1)?.range?.first?.let { it <= close.range.first } == true
    }
}

private fun codeText(s: String): String {
    val text = s.replace(codeNewline, " ")
    return if (text.startsWith(' ') && text.endsWith(' ') && text.any { it != ' ' }) text.substring(1, text.length - 1) else text
}

private data class InlineMark(val match: MatchResult, val kind: RunKind, val range: IntRange = match.range, val close: Int = 0)

private fun inlineMarks(s: String, re: Regex = inlineToken, start: Int = 0): Sequence<InlineMark> = sequence {
    var at = start
    while (at < s.length) {
        val m = re.find(s, at) ?: break
        at = m.range.last + 1
        if (re === linkToken) yield(InlineMark(m, RunKind.LINK))
        else when {
            m.groups["ticks"] != null -> codeClose(s, m)?.let {
                at = it.range.last + 1
                yield(InlineMark(m, RunKind.CODE, m.range.first until at, it.range.first))
            }
            m.groups["math"] != null -> if (isMath(m.value)) yield(InlineMark(m, RunKind.MATH)) else at = m.range.first + 1
            re !== marksToken && m.groups["bold"] != null -> yield(InlineMark(m, RunKind.BOLD))
            re === inlineToken && (m.groups["ltext"] != null || m.groups["bare"] != null) -> yield(InlineMark(m, RunKind.LINK))
        }
    }
}

private fun unescape(s: String): String = if ('\\' in s) s.replace(escaped, "$1") else s

/** How many characters [this] shows. */
fun List<MdRun>.shownLength(): Int = sumOf { it.text.length }

/** How many characters a block shows; typing out spends them block by block (demo.html blockLen). */
fun MdBlock.shownLength(): Int = when (this) {
    is MdBlock.Paragraph -> runs.shownLength()
    is MdBlock.Heading -> runs.shownLength()
    is MdBlock.Quote -> blocks.sumOf { it.shownLength() }
    is MdBlock.ItemList -> items.sumOf { item -> item.sumOf { it.shownLength() } }
    is MdBlock.Code -> code.length
    is MdBlock.Table -> (rows + listOf(head)).sumOf { row -> row.sumOf { it.shownLength() } }
    is MdBlock.Math -> written.length
}

private val plainListMarker = Regex("""(?m)^[ \t]*(?:-|\d+\.)[ \t]+""")

// The screen draws a table with box-drawing rules that its written Markdown has no counterpart for.
private fun Char.counts() = !isWhitespace() && this !in '─'..'╿'

internal fun carryShown(old: List<MdBlock>, shown: Int, new: List<MdBlock>): Int {
    // Match visible content across line wraps; list decorations are not part of the typing offset.
    fun walk(
        blocks: List<MdBlock>, stripMarkers: Boolean,
        text: (String, Boolean) -> Boolean, table: (Int) -> Boolean,
    ): Boolean {
        fun runs(runs: List<MdRun>, plain: Boolean = false): Boolean {
            var lineStart = true
            return runs.any { run ->
                val written = run.text
                val content = if (plain && run.kind == RunKind.TEXT) {
                    plainListMarker.replace(written) { marker ->
                        if (marker.range.first == 0 && !lineStart) marker.value else " ".repeat(marker.value.length)
                    }
                } else written
                if (written.isNotEmpty()) lineStart = written.endsWith('\n')
                text(content, run.kind == RunKind.MATH)
            }
        }
        return blocks.any { block ->
            when (block) {
                is MdBlock.Paragraph -> runs(block.runs, plain = stripMarkers)
                is MdBlock.Heading -> runs(block.runs)
                is MdBlock.Quote -> walk(block.blocks, stripMarkers, text, table)
                is MdBlock.ItemList -> block.items.any { walk(it, stripMarkers, text, table) }
                is MdBlock.Code -> text(block.code, false)
                is MdBlock.Math -> text(block.written, true)
                is MdBlock.Table -> table(block.shownLength())
            }
        }
    }

    if (shown == 0) return 0
    var left = shown
    var count = 0
    walk(old, stripMarkers = true, text = { written, whole ->
        val end = minOf(left, written.length)
        if (!whole || end == written.length) {
            for (i in 0 until end) if (written[i].counts()) count++
        }
        left -= end
        left == 0
    }, table = { length ->
        count += length
        left -= length
        left <= 0
    })
    if (count == 0) return 0

    var position = 0
    walk(new, stripMarkers = false, text = { written, whole ->
        if (whole) {
            position += written.length
            count -= written.count { it.counts() }
        } else {
            for (c in written) {
                position++
                if (c.counts() && --count == 0) break
            }
        }
        count <= 0
    }, table = { length ->
        position += length
        count -= length
        count <= 0
    })
    return position
}
