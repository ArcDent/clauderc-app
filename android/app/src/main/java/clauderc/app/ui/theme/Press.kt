package clauderc.app.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import clauderc.app.data.UiStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// Press feedback of every clickable, in place of Material's ripple:
// a flat layer of the element's content color covers it the moment the press lands and fades out
// in durS on release; small controls also animate their physical press via pressShrink.
private const val PressAlpha = 0.12f

private val PlateEasePress get() = Motion.plateEasePress

/**
 * ClaudercTheme installs `PressIndication()`, which takes LocalContentColor. Solid buttons pass
 * their own text color as [color], so the layer still shows on a background of the body text color.
 */
data class PressIndication(val color: Color = Color.Unspecified) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressLayerNode(interactionSource, color)
}

/** Runs [onPress] and [onRelease] for each press of [source]; release covers cancel too. */
internal fun CoroutineScope.onPresses(source: InteractionSource, onPress: suspend () -> Unit, onRelease: suspend () -> Unit): Job =
    launch {
        source.interactions.collect {
            when (it) {
                is PressInteraction.Press -> launch { onPress() }
                is PressInteraction.Release, is PressInteraction.Cancel -> launch { onRelease() }
            }
        }
    }

private class PressLayerNode(private val source: InteractionSource, private val color: Color) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val alpha = Animatable(0f)

    override fun onAttach() {
        coroutineScope.onPresses(source, onPress = { alpha.snapTo(PressAlpha) }, onRelease = { alpha.animateTo(0f, Motion.fade()) })
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val a = alpha.value
        if (a > 0f) drawRect(color.takeOrElse { currentValueOf(LocalContentColor) }.copy(alpha = a))
    }
}

/**
 * Differentiates tactile press tiers across styles (design/ui-mockup/demo.html):
 * - 印版: mechanical letterpress sink (1dp..2dp translation, no squishy scale).
 * - 清玻: liquid drop spread (104% x 93%) with one overshoot on release.
 * - 素卡: crisp soft scale (97% / 95%) springing back.
 * - 浮墨: elastic floating pill/orb scale (96% / 91%) springing back.
 */
enum class PressKind(val tileScale: Float, val inkScale: Float, val plateSinkDp: Float) {
    Plain(0.97f, 0.96f, 1f),
    Button(0.96f, 0.95f, 1.5f),
    Primary(0.96f, 0.95f, 2f),
    Send(0.92f, 0.90f, 1.5f),
    Icon(0.92f, 0.90f, 1.5f),
    Card(0.985f, 0.98f, 1f),
    Still(1f, 1f, 0f),
}

/**
 * Animates the element while [source] is pressed according to the active [UiStyle].
 * Goes before clip and background so they move together; clickable receives the same [source].
 */
fun Modifier.pressShrink(source: InteractionSource, kind: PressKind = PressKind.Plain): Modifier =
    this then PressShrinkElement(source, kind)

/** Pressed progress 0..1 moving crisply on press and springing back on release. */
@Composable
fun pressProgress(source: InteractionSource): State<Float> {
    val pressed by source.collectIsPressedAsState()
    return animateFloatAsState(if (pressed) 1f else 0f, Motion.push(), label = "pressProgress")
}

/**
 * How far [source] is pressed in, 0 to 1: snaps to 1 the moment the press lands, and eases back in durS on release.
 */
@Composable
fun snapPressed(source: InteractionSource, easing: Easing): State<Float> {
    val pressed by source.collectIsPressedAsState()
    val p = remember { Animatable(0f) }
    val back = Clauderc.motion.durS
    LaunchedEffect(pressed) { if (pressed) p.snapTo(1f) else p.animateTo(0f, tween(back, easing = easing)) }
    return p.asState()
}

private data class PressShrinkElement(val source: InteractionSource, val kind: PressKind) : ModifierNodeElement<PressShrinkNode>() {
    override fun create() = PressShrinkNode(source, kind)
    override fun update(node: PressShrinkNode) {
        node.kind = kind
        node.follow(source)
    }
}

private class PressShrinkNode(private var source: InteractionSource, var kind: PressKind) :
    Modifier.Node(), LayoutModifierNode, CompositionLocalConsumerModifierNode {
    private val scaleX = Animatable(1f)
    private val scaleY = Animatable(1f)
    private val sink = Animatable(0f)
    private var job: Job? = null

    override fun onAttach() {
        job = coroutineScope.onPresses(
            source,
            onPress = {
                if (kind == PressKind.Still) return@onPresses
                when (currentValueOf(LocalUiStyle)) {
                    UiStyle.Clear -> both({ scaleX.animateTo(Motion.LensWide, Motion.lensIn()) }, { scaleY.animateTo(Motion.LensFlat, Motion.lensIn()) })
                    UiStyle.Plate -> sink.animateTo(1f, tween(110, easing = PlateEasePress))
                    UiStyle.Tile -> both({ scaleX.animateTo(kind.tileScale, Motion.pressIn()) }, { scaleY.animateTo(kind.tileScale, Motion.pressIn()) })
                    UiStyle.Ink -> both({ scaleX.animateTo(kind.inkScale, Motion.pressIn()) }, { scaleY.animateTo(kind.inkScale, Motion.pressIn()) })
                }
            },
            onRelease = {
                if (kind == PressKind.Still) return@onPresses
                when (currentValueOf(LocalUiStyle)) {
                    UiStyle.Clear -> both({ scaleX.animateTo(1f, Motion.lensOut(Motion.LensBackWide)) }, { scaleY.animateTo(1f, Motion.lensOut(Motion.LensBackFlat)) })
                    UiStyle.Plate -> sink.animateTo(0f, tween(150, easing = PlateEasePress))
                    UiStyle.Tile, UiStyle.Ink -> both({ scaleX.animateTo(1f, Motion.pressOut()) }, { scaleY.animateTo(1f, Motion.pressOut()) })
                }
                if (sink.value != 0f) sink.snapTo(0f)
                if (scaleX.value != 1f || scaleY.value != 1f) both({ scaleX.snapTo(1f) }, { scaleY.snapTo(1f) })
            },
        )
    }

    fun follow(next: InteractionSource) {
        if (next == source) return
        source = next
        if (isAttached) {
            job?.cancel()
            onAttach()
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = this@PressShrinkNode.scaleX.value
                scaleY = this@PressShrinkNode.scaleY.value
                val d = sink.value * kind.plateSinkDp.dp.toPx()
                translationX = d
                translationY = d
            }
        }
    }
}

private suspend fun both(a: suspend () -> Unit, b: suspend () -> Unit) = coroutineScope {
    launch { a() }
    launch { b() }
}
