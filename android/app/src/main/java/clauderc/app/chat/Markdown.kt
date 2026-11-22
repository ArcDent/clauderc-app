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
data class MdRun(val kind: RunKind, val text: String, val url: String? = null)

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
        else -> text.removeSurrounding("$")
    }.trim()

// Block spans tell where each block's lines are in the reply; inline spans give each table cell's.
private val parser = Parser.builder()
    .extensions(listOf(TablesExtension.create()))
    .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
    .build()

/** [text] as blocks. A fence that is never closed runs to the end, as CommonMark has it, so a reply typing out shows its code as code. */
fun mdBlocks(text: String): List<MdBlock> = blocksIn(parser.parse(text), text)

private fun blocksIn(parent: Node, src: String): List<MdBlock> = children(parent).flatMap { n ->
    if (n is Paragraph) paragraph(lines(n, src)) else listOfNotNull(blockOf(n, src))
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
    for (m in blockMath.findAll(text)) {
        prose(text.substring(at, m.range.first).trimEnd('\n'))
        val tex = (m.groups[2] ?: m.groups[3])!!.value
        out += MdBlock.Math(tex.trim(), m.groups[1]!!.value)
        at = m.range.last + 1
    }
    prose(text.substring(at).trimStart('\n'))
    return out
}

private val blockMath = Regex("""(?m)^[ \t]*(\$\$((?:(?!\$\$)[\s\S])+?)\$\$|\\\[((?:(?!\\\])[\s\S])+?)\\\])[ \t]*$""")

private fun blockOf(n: Node, src: String): MdBlock? = when (n) {
    is Heading -> MdBlock.Heading(minOf(n.level, 3), mdRuns(headingText(n, src)))
    is BlockQuote -> MdBlock.Quote(blocksIn(n, src))
    is BulletList -> MdBlock.ItemList(false, 1, children(n).map { blocksIn(it, src) })
    is OrderedList -> MdBlock.ItemList(true, n.markerStartNumber ?: 1, children(n).map { blocksIn(it, src) })
    is FencedCodeBlock -> MdBlock.Code(n.info.orEmpty().trim().substringBefore(' '), n.literal.removeSuffix("\n"))
    is IndentedCodeBlock -> MdBlock.Code("", n.literal.removeSuffix("\n"))
    is TableBlock -> MdBlock.Table(
        children(n).filterIsInstance<TableHead>().flatMap(::children).firstOrNull()?.let { cells(it, src) }.orEmpty(),
        children(n).filterIsInstance<TableBody>().flatMap(::children).map { cells(it, src) },
    )
    // HTML is shown as written; a rule and a link definition have nothing to show.
    is HtmlBlock -> MdBlock.Paragraph(listOf(MdRun(RunKind.TEXT, n.literal.trimEnd())))
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
fun mdRuns(s: String): List<MdRun> = runs(s, inlineToken, true, ::unescape)

/** The user's own message as runs: only its links, the rest as typed (demo.html meHtml). */
fun linkRuns(s: String): List<MdRun> = runs(s, linkToken, false) { it }

/** Splits [s] at [re]'s matches; [marks] says [re] also finds code, formulas and bold. */
private fun runs(s: String, re: Regex, marks: Boolean, plain: (String) -> String): List<MdRun> {
    val out = mutableListOf<MdRun>()
    fun text(t: String) {
        if (t.isEmpty()) return
        val last = out.lastOrNull()
        if (last?.kind == RunKind.TEXT) out[out.size - 1] = last.copy(text = last.text + plain(t)) else out += MdRun(RunKind.TEXT, plain(t))
    }
    var at = 0
    for (m in re.findAll(s)) {
        text(s.substring(at, m.range.first))
        val g = m.groups
        val code = if (marks) g["code"] else null
        val math = if (marks) g["math"] else null
        val bold = if (marks) g["bold"] else null
        val label = g["ltext"]
        when {
            code != null -> out += MdRun(RunKind.CODE, code.value)
            math != null -> out += MdRun(RunKind.MATH, math.value)
            bold != null -> out += MdRun(RunKind.BOLD, plain(bold.value))
            label != null -> out += MdRun(RunKind.LINK, plain(label.value), g["lurl"]!!.value)
            else -> {
                val bare = g["bare"]!!.value
                val url = trimUrl(bare)
                out += MdRun(RunKind.LINK, url, url)
                text(bare.substring(url.length))
            }
        }
        at = m.range.last + 1
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

// A single-dollar formula keeps prices and shell variables out the way Pandoc does: no space just inside
// either dollar, no digit right after the closing one, and an escaped dollar opens nothing. So
// "$5 到 $10", "$PATH" and "$ x $" stay text.
private const val MATH = """\$\$[^$\n]+?\$\$|\\\(.+?\\\)|(?<![\\$])\$[^\s$](?:[^$\n]*?[^\s$\\])?\$(?![\d$])"""
private val inlineToken = Regex("`(?<code>[^`\\n]+)`|(?<math>$MATH)|\\*\\*(?<bold>[^*\\n]+)\\*\\*|$LINK")
private val linkToken = Regex(LINK)
private val escaped = Regex("""\\([!-/:-@\[-`{-~])""")

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
