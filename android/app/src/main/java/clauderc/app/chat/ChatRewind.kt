package clauderc.app.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import clauderc.app.data.UiStyle
import clauderc.app.protocol.ChatItem
import clauderc.app.protocol.ItemKind
import clauderc.app.protocol.RewindRestore
import clauderc.app.ui.components.ClaudercSheet
import clauderc.app.ui.components.MenuItem
import clauderc.app.ui.components.SheetHead
import clauderc.app.ui.components.TextAction
import clauderc.app.ui.components.TextTone
import androidx.compose.ui.draw.clip
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.Symbols
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

// Rewinding from a message (round 9): the user's message slides left as a whole row and lays bare a
// rewind button; the button opens a sheet that quotes the message and offers the ways back.

/** How far a message slides open: the room of the rewind button. */
private val RewindWidth = 60.dp

/**
 * The message slid open, by seq, which sliding another closes; whether a message can slide now; and
 * what opening the sheet for one does. Null where nothing rewinds, as on a subagent's page.
 */
@Stable
internal class RewindHub(val ask: (ChatItem) -> Unit) {
    var open by mutableStateOf<Long?>(null)
    var enabled by mutableStateOf(true)
}

internal val LocalRewind = staticCompositionLocalOf<RewindHub?> { null }

/**
 * The user's message [item] as a row that slides left when [LocalRewind] lets it and the message is in
 * the transcript, its button coming in from the right edge beside what the message marked with
 * [rewindBand]. Only the sideways overflow is clipped.
 */
@Composable
internal fun RewindRow(item: ChatItem, content: @Composable BoxScope.(Modifier) -> Unit) {
    val hub = LocalRewind.current
    if (hub == null || item.uuid == null) return Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { content(Modifier) }
    val width = with(LocalDensity.current) { RewindWidth.toPx() }
    val slide = remember { Animatable(0f) }
    var pulled by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val enter = Motion.enter<Float>()
    LaunchedEffect(hub.open) { if (hub.open != item.seq && slide.value != 0f) slide.animateTo(0f, enter) }
    LaunchedEffect(hub.enabled) { if (!hub.enabled && slide.value != 0f) slide.animateTo(0f, enter) }
    val drag = rememberDraggableState { d ->
        pulled = (pulled + d).coerceAtMost(0f)
        // Past the button's width the row drags with a third of the finger's travel.
        val x = if (pulled < -width) -width + (pulled + width) / 3 else pulled
        // At once, not queued: a snap left waiting would stop the settle that letting go starts.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { slide.snapTo(x) }
    }
    val ask = {
        hub.open = null
        hub.ask(item)
    }
    Layout(
        content = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { content(Modifier.graphicsLayer { translationX = slide.value }) }
            val out by remember { derivedStateOf { slide.value < 0f } }
            if (out) RewindButton(ask, { (-slide.value / width).coerceIn(0f, 1f) }, Modifier.graphicsLayer { translationX = width + slide.value })
        },
        modifier = Modifier
            .fillMaxWidth()
            .drawWithContent { clipRect(top = -size.height, bottom = size.height * 2) { this@drawWithContent.drawContent() } }
            .draggable(
                drag,
                Orientation.Horizontal,
                enabled = hub.enabled,
                onDragStarted = {
                    pulled = slide.value
                    hub.open = item.seq
                },
                onDragStopped = { v ->
                    val open = slide.value < -width / 2 || v < -1000f
                    if (!open && hub.open == item.seq) hub.open = null
                    slide.animateTo(if (open) -width else 0f, enter)
                },
            )
            .semantics { if (hub.enabled) customActions = listOf(CustomAccessibilityAction("回溯") { ask(); true }) }
            .testTag("userRow:${item.seq}"),
    ) { parts, constraints ->
        val row = parts[0].measure(constraints)
        // Read only while the button shows: the stretch the message marked with rewindBand, or the whole row.
        val top = if (parts.size > 1) row[BandTop].takeUnless { it == AlignmentLine.Unspecified } ?: 0 else 0
        val bottom = if (parts.size > 1) row[BandBottom].takeUnless { it == AlignmentLine.Unspecified } ?: row.height else 0
        val button = parts.getOrNull(1)?.measure(Constraints.fixed(RewindWidth.roundToPx(), (bottom - top).coerceAtLeast(0)))
        layout(row.width, row.height) {
            row.place(0, 0)
            button?.place(row.width - button.width, top)
        }
    }
}

/** Where the stretch a rewind button lines up with starts and ends down its row (rewindBand). */
private val BandTop = HorizontalAlignmentLine(::min)
private val BandBottom = HorizontalAlignmentLine(::max)

/**
 * Marks what a user message's rewind button lines up with: its text bubble, or its pictures when it has
 * no text. The button takes that stretch of the row's height and centres its icon in it, so under a
 * message's pictures it faces the bubble, not the middle of the row.
 */
internal fun Modifier.rewindBand(): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    layout(p.width, p.height, mapOf(BandTop to 0, BandBottom to p.height)) { p.place(0, 0) }
}

/**
 * The rewind button, no word under it, as the style picked it (round 11), at [progress] of the slide
 * from 0 shut to 1 open: 贴纸 a 36dp card-white circle in an ink line, no hard shadow, growing from 0.6
 * as it fades in; 格栅 an empty square as tall as a one-line bubble in a thin grey line, only sliding
 * in with the row; 清玻 the bare icon, turning back 120° like a clock's hand as it fades in. A press only
 * changes colours, in durS, since the slide already drives the size and turn: 贴纸 fills with the accent
 * under an on-accent icon; 格栅 fills with the text colour under an icon in the page colour; 清玻's icon
 * turns the text colour on a round 10% tint of it.
 */
@Composable
private fun RewindButton(onClick: () -> Unit, progress: () -> Float, modifier: Modifier) {
    val c = Clauderc.colors
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val on by animateFloatAsState(if (pressed) 1f else 0f, tween(Clauderc.motion.durS, easing = LinearEasing), label = "rewindPress")
    Box(
        modifier.clickable(press, null, onClick = onClick).semantics { contentDescription = "回溯" }.testTag("rewindAction"),
        contentAlignment = Alignment.Center,
    ) {
        when (Clauderc.style) {
            UiStyle.Tile, UiStyle.Ink -> {
                val fill = lerp(if (Clauderc.style == UiStyle.Ink) c.float else c.card, c.accent, on)
                Box(
                    Modifier
                        .graphicsLayer {
                            val p = progress()
                            alpha = p
                            scaleX = 0.6f + 0.4f * p
                            scaleY = scaleX
                        }
                        .size(36.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(fill)
                        .then(if (Clauderc.style == UiStyle.Ink) Modifier.border(1.dp, c.edge, androidx.compose.foundation.shape.CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Symbols.HistoryRegular, contentDescription = null, tint = lerp(c.fg, c.onAccent, on), modifier = Modifier.size(20.dp))
                }
            }
            UiStyle.Plate -> {
                val side = with(LocalDensity.current) { (15 * 1.55f).sp.toDp() } + 20.dp
                val square = Modifier.heightIn(max = side).fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true)
                Box(square.background(c.fg.copy(alpha = on)).border(1.5.dp, lerp(c.surface2, c.fg, on)), contentAlignment = Alignment.Center) {
                    Icon(Symbols.History, contentDescription = null, tint = lerp(c.fg2, c.bg, on), modifier = Modifier.size(20.dp))
                }
            }
            UiStyle.Clear -> Icon(
                Symbols.HistoryRegular,
                contentDescription = null,
                tint = lerp(c.fg2, c.fg, on),
                modifier = Modifier
                    .graphicsLayer {
                        val p = progress()
                        alpha = p
                        rotationZ = 120f * (1f - p)
                    }
                    .drawBehind { drawCircle(c.fg.copy(alpha = 0.1f * on), radius = size.minDimension / 2 + 8.dp.toPx()) }
                    .size(22.dp),
            )
        }
    }
}

/**
 * The files changed since [item], in its turn or any later one, as 「conn.go +1 −1」 in the order they
 * were changed: what restoring the code to before [item] takes back, as Claude Code's panel counts it.
 */
internal fun filesChangedSince(items: List<ChatItem>, item: ChatItem): List<String> {
    val after = items.dropWhile { it.seq != item.seq }.drop(1)
    val files = linkedMapOf<String, IntArray>()
    for (it in after) {
        val tool = it.tool ?: continue
        val hunks = tool.result?.patch ?: continue
        val path = tool.input.stringField("file_path") ?: tool.input.stringField("notebook_path") ?: continue
        val n = files.getOrPut(path.substringAfterLast('/')) { IntArray(2) }
        for (h in hunks) for (l in h.lines) {
            if (l.startsWith("+")) n[0]++ else if (l.startsWith("-")) n[1]++
        }
    }
    return files.map { (name, n) -> "$name +${n[0]} −${n[1]}" }
}

/**
 * The rewind sheet for [item]: what happens, the message quoted to three lines, and when [files] were
 * changed since it the three ways back with both taken by default, else the conversation alone.
 */
@Composable
internal fun RewindSheet(item: ChatItem, files: List<String>, onDismiss: () -> Unit, onRewind: (String) -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    var mode by remember { mutableStateOf(if (files.isEmpty()) RewindRestore.CONVERSATION else RewindRestore.BOTH) }
    val plate = Clauderc.style == UiStyle.Plate
    val (rule, ruleWidth) = when (Clauderc.style) {
        UiStyle.Clear -> c.surface2 to 2.dp
        UiStyle.Plate -> c.fg to 2.dp
        UiStyle.Tile, UiStyle.Ink -> c.accent to 3.dp
    }
    ClaudercSheet(onDismiss = onDismiss) {
        SheetHead(
            "回溯到这条消息之前",
            (if (files.isEmpty()) "这条消息之后没改过文件，只回对话。" else "选一种回去的方式。") + "对话回去后，这条消息填回输入框。",
        )
        Text(
            item.text.orEmpty(),
            Modifier
                .padding(start = sp.page, end = sp.page, bottom = sp.sp2)
                .drawBehind { drawRect(rule, size = Size(ruleWidth.toPx(), size.height)) }
                .padding(start = 12.dp)
                .testTag("rewindQuote"),
            color = c.fg2,
            fontSize = if (plate) 13.sp else 14.sp,
            fontFamily = if (plate) MonoFamily else null,
            lineHeight = 1.55.em,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (files.isNotEmpty()) {
            val names = files.joinToString("，")
            MenuItem("对话和代码都回去", onClick = { mode = RewindRestore.BOTH }, supporting = "$names 改回去", checked = mode == RewindRestore.BOTH)
            MenuItem("只回对话", onClick = { mode = RewindRestore.CONVERSATION }, supporting = "代码不动", checked = mode == RewindRestore.CONVERSATION)
            MenuItem("只回代码", onClick = { mode = RewindRestore.CODE }, supporting = "对话不动，$names 改回去", checked = mode == RewindRestore.CODE)
            Text(
                "文件整份恢复，之后别人的改动也会丢；Bash 改的文件不恢复",
                Modifier.padding(start = sp.page, end = sp.page, top = sp.sp3),
                fontSize = Clauderc.fontSizes.caption,
                color = c.fg3,
            )
        }
        Row(Modifier.fillMaxWidth().padding(start = sp.sp3, end = sp.sp3, top = sp.sp3), horizontalArrangement = Arrangement.End) {
            TextAction("取消", onClick = onDismiss, tone = TextTone.Quiet)
            TextAction("回溯", onClick = { onRewind(mode) })
        }
    }
}
