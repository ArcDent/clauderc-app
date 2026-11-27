package clauderc.app.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import clauderc.app.data.UiStyle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

enum class PressRow { Page, Panel, Choice, Compact, CompactPanel, Agent, Timeline, Danger, Title }

/** The default indication is for rows; a supplied fill is for an individual control. */
data class PressIndication(
    val color: Color = Color.Unspecified,
    val row: PressRow = PressRow.Page,
    val corner: Dp = 0.dp,
    val kind: PressKind = PressKind.Still,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressLayerNode(interactionSource, this)
}

private abstract class PressNode(private var source: InteractionSource) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private var observer: kotlinx.coroutines.Job? = null
    protected val progress = Animatable(0f)
    protected val style get() = currentValueOf(LocalUiStyle)
    abstract val kind: PressKind
    protected open val transform = false

    override fun onAttach() = followPresses()

    fun follow(next: InteractionSource) {
        if (source == next) return
        source = next
        if (isAttached) followPresses()
    }

    private fun followPresses() {
        observer?.cancel()
        // A lazily attached indication must subscribe before clickable emits the first press.
        observer = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val active = mutableSetOf<PressInteraction.Press>()
            source.interactions.mapNotNull {
                when (it) {
                    is PressInteraction.Press -> active.add(it)
                    is PressInteraction.Release -> active.remove(it.press)
                    is PressInteraction.Cancel -> active.remove(it.press)
                    else -> return@mapNotNull null
                }
                active.isNotEmpty()
            }.distinctUntilChanged().collectLatest { pressed ->
                progress.animateTo(if (pressed) 1f else 0f, Motion.press(style, kind, pressed, transform))
            }
        }
    }
}

private class PressLayerNode(source: InteractionSource, private val indication: PressIndication) : PressNode(source) {
    override val kind get() = indication.kind

    override fun ContentDrawScope.draw() {
        val p = progress.value
        if (p <= 0f) { drawContent(); return }
        if (indication.color != Color.Unspecified) {
            val color = indication.color
            drawRoundRect(color.copy(alpha = color.alpha * p), cornerRadius = CornerRadius(indication.corner.toPx()))
            drawContent()
            return
        }
        val c = currentValueOf(LocalColors)
        val row = indication.row
        val plate = style == UiStyle.Plate
        val compact = row == PressRow.Choice || row == PressRow.Compact || row == PressRow.CompactPanel || row == PressRow.Agent
        val panel = row == PressRow.Choice || row == PressRow.Panel || row == PressRow.CompactPanel
        val choice = row == PressRow.Choice
        val inset = when (style) {
            UiStyle.Tile -> 6.dp
            UiStyle.Ink -> if (panel) 6.dp else 8.dp
            UiStyle.Clear -> 12.dp
            UiStyle.Plate -> 0.dp
        }.let { if (row == PressRow.Title || choice) 0.dp else it }.toPx().coerceAtMost(size.width / 2)
        val radius = when {
            plate -> 0.dp
            row == PressRow.Title -> if (style == UiStyle.Tile) 10.dp else 12.dp
            compact -> if (style == UiStyle.Ink) 12.dp else 10.dp
            style == UiStyle.Ink -> 16.dp
            else -> 14.dp
        }.toPx()
        val fg = if (row == PressRow.Danger) c.danger else c.fg
        val fill = when (style) {
            UiStyle.Tile -> if (choice) lerp(c.surface, c.fg, 0.08f) else c.surface
            UiStyle.Ink -> if (panel || row == PressRow.Title) c.surface2 else c.card
            UiStyle.Clear -> if (panel || row == PressRow.Title) lerp(c.lens, c.fg, 0.08f) else c.surface
            UiStyle.Plate -> lerp(c.bg, fg, if (row == PressRow.Danger) 0.16f else 0.14f)
        }
        // Choice rows start their radio/checkbox at x=0 inside a padded container; bleed symmetrically into the gutter.
        val bleed = if (choice) (if (plate) 6.dp else 8.dp).toPx() else 0f
        val left = if (choice) -bleed else inset
        val right = if (choice) size.width + bleed else size.width - inset
        drawRoundRect(fill.copy(alpha = fill.alpha * p), Offset(left, 0f), Size((right - left).coerceAtLeast(0f), size.height), CornerRadius(radius))
        if (plate) {
            val width = (if (row == PressRow.Agent || row == PressRow.Title) 2.dp else 3.dp).toPx()
            val x = if (row == PressRow.Timeline) size.width - width else left
            drawRect(fg.copy(alpha = p), Offset(x, 0f), Size(width, size.height))
            if (row != PressRow.Timeline && row != PressRow.Title) {
                if (choice) {
                    translate(left = 4.dp.toPx() * p) { this@draw.drawContent() }
                } else {
                    clipRect { translate(left = 4.dp.toPx() * p) { this@draw.drawContent() } }
                }
                return
            }
        }
        drawContent()
    }
}

enum class PressKind(
    val tileScale: Float, val inkScale: Float, val clearX: Float, val clearY: Float,
    val plateSinkDp: Float, val plateSinkY: Float = plateSinkDp,
) {
    Plain(0.96f, 0.95f, 1.04f, 0.93f, 1f),
    Button(0.96f, 0.95f, 1.04f, 0.93f, 1.5f),
    Primary(0.96f, 0.95f, 1.04f, 0.93f, 2.5f),
    Stamp(0.96f, 0.95f, 1.04f, 0.93f, 2f),
    Icon(0.92f, 0.91f, 1.05f, 0.92f, 1f),
    Stage(0.92f, 0.91f, 1.05f, 0.92f, 1.5f),
    SmallButton(0.96f, 0.95f, 1.04f, 0.93f, 1f),
    Key(0.96f, 0.95f, 1.04f, 0.93f, 0f, 2.5f),
    Card(0.975f, 0.97f, 1.02f, 0.96f, 2f),
    Preview(0.96f, 0.95f, 1.03f, 0.94f, 1.5f),
    Swatch(0.90f, 0.88f, 1.08f, 0.90f, 1f),
    ToolImage(0.98f, 0.975f, 1.02f, 0.96f, 1.5f),
    Attachment(0.96f, 0.95f, 1.04f, 0.94f, 1f),
    Chip(0.95f, 0.94f, 1.04f, 0.93f, 1f),
    Segment(0.96f, 0.95f, 1.04f, 0.93f, 0f, 1.5f),
    Switch(0.95f, 0.94f, 1.05f, 0.92f, 1f),
    Title(1f, 0.94f, 1.02f, 0.96f, 1f),
    Branch(0.96f, 0.95f, 1.04f, 0.94f, 1f),
    Nav(0.94f, 0.92f, 1.05f, 0.92f, 0f, 1.5f),
    NavLens(1f, 1f, 1.05f, 0.94f, 0f),
    SegmentLens(1f, 1f, 1.04f, 0.94f, 0f),
    Attach(0.91f, 0.90f, 1.06f, 0.90f, 1f),
    Send(0.90f, 0.88f, 1.06f, 0.90f, 2f),
    Pending(0.97f, 0.96f, 1.03f, 0.94f, 2.5f),
    Badge(0.92f, 0.91f, 1.05f, 0.92f, 2.5f),
    Capsule(0.98f, 0.975f, 1.02f, 0.95f, 1.5f),
    DarkIcon(0.92f, 0.92f, 0.92f, 0.92f, 1f),
    Still(1f, 1f, 1f, 1f, 0f),
}

/** Drawing the pose leaves layout, focus bounds, and the pointer target at rest. */
fun Modifier.pressShrink(source: InteractionSource, kind: PressKind = PressKind.Plain): Modifier =
    if (kind == PressKind.Still) this else this then PressShrinkElement(source, kind)

@Composable
fun pressProgress(source: InteractionSource, kind: PressKind = PressKind.Button, sweep: Boolean = false, transform: Boolean = false): State<Float> {
    val pressed by source.collectIsPressedAsState()
    return animateFloatAsState(if (pressed) 1f else 0f, Motion.press(Clauderc.style, kind, pressed, transform, sweep), label = "pressProgress")
}

@Composable
fun Modifier.pressClickable(
    kind: PressKind,
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return pressShrink(source, kind).clickable(source, controlPress(kind), enabled, onClickLabel, role, onClick)
}

@Composable
fun Modifier.listRowPress(
    row: PressRow = PressRow.Page,
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = clickable(null, PressIndication(row = row), enabled, onClickLabel, role, onClick)

enum class PressTone { Normal, Primary, Danger }

@Composable
fun controlPress(kind: PressKind, tone: PressTone = PressTone.Normal, fill: Color = Color.Unspecified, corner: Dp? = null): PressIndication {
    val ui = Clauderc.style
    val c = Clauderc.colors
    val icon = kind == PressKind.Icon || kind == PressKind.Badge
    val radius = corner ?: when {
        ui == UiStyle.Plate -> 0.dp
        icon || kind == PressKind.Branch -> 100.dp
        kind == PressKind.Title -> if (ui == UiStyle.Clear) 14.dp else 10.dp
        else -> 12.dp
    }
    val hit = if (fill != Color.Unspecified) fill else when (ui) {
        UiStyle.Tile -> when {
            tone == PressTone.Danger -> lerp(c.danger, c.fg, 0.14f)
            tone == PressTone.Primary -> lerp(c.accent, c.fg, 0.14f)
            kind == PressKind.Segment -> c.fg.copy(alpha = 0.08f)
            icon || kind in listOf(PressKind.Card, PressKind.ToolImage, PressKind.Title, PressKind.Capsule, PressKind.Branch, PressKind.Pending) -> c.surface
            else -> c.line
        }
        UiStyle.Ink -> when {
            tone == PressTone.Danger -> lerp(c.danger, c.bg, 0.16f)
            tone == PressTone.Primary -> lerp(c.inverse, c.bg, 0.14f)
            kind == PressKind.Segment -> c.fg.copy(alpha = 0.12f)
            icon || kind in listOf(PressKind.Stage, PressKind.Card, PressKind.Title, PressKind.Attachment, PressKind.Capsule, PressKind.Branch, PressKind.Pending) -> c.surface2
            else -> lerp(c.surface2, c.fg, 0.14f)
        }
        UiStyle.Clear -> when {
            kind == PressKind.Send -> lerp(c.fg, c.bg, 0.18f)
            kind == PressKind.Attach -> lerp(c.lens, c.fg, 0.08f)
            tone == PressTone.Danger -> lerp(c.danger, c.fg, 0.12f)
            tone == PressTone.Primary -> lerp(c.accent, c.fg, 0.12f)
            kind == PressKind.Segment -> c.fg.copy(alpha = 0.07f)
            kind == PressKind.Pending || kind == PressKind.Capsule -> lerp(c.pane, c.fg, 0.07f)
            icon || kind in listOf(PressKind.ToolImage, PressKind.Branch, PressKind.Title) -> c.surface
            else -> c.surface2
        }
        UiStyle.Plate -> lerp(c.bg, c.fg, 0.14f)
    }
    return PressIndication(hit, corner = radius, kind = kind)
}

private data class PressShrinkElement(val source: InteractionSource, val kind: PressKind) : ModifierNodeElement<PressShrinkNode>() {
    override fun create() = PressShrinkNode(source, kind)
    override fun update(node: PressShrinkNode) { node.kind = kind; node.follow(source) }
}

private class PressShrinkNode(source: InteractionSource, override var kind: PressKind) : PressNode(source) {
    override val transform = true
    override fun ContentDrawScope.draw() = drawPressPose(style, kind, progress.value) { this@draw.drawContent() }
}

fun Modifier.pressPose(style: UiStyle, kind: PressKind, progress: State<Float>) = drawWithContent {
    drawPressPose(style, kind, progress.value) { drawContent() }
}

private inline fun ContentDrawScope.drawPressPose(style: UiStyle, kind: PressKind, p: Float, content: () -> Unit) {
    if (p == 0f) { content(); return }
    if (style == UiStyle.Plate) {
        translate(kind.plateSinkDp.dp.toPx() * p, kind.plateSinkY.dp.toPx() * p) { content() }
    } else {
        val x = when (style) { UiStyle.Tile -> kind.tileScale; UiStyle.Ink -> kind.inkScale; else -> kind.clearX }
        val y = if (style == UiStyle.Clear) kind.clearY else x
        scale(1f + (x - 1f) * p, 1f + (y - 1f) * p) { content() }
    }
}
