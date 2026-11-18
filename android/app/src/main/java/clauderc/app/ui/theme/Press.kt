package clauderc.app.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.LocalContentColor
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
import clauderc.app.data.UiStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Press feedback of every clickable, in place of Material's ripple (the demo's 「叠层加缩小」):
// a flat layer of the element's content color covers it the moment the press lands and fades out
// in durS on release; small controls also shrink with pressShrink. Inside scrolling content Compose
// reports the press 100ms late, once it is sure the finger is not scrolling.
private const val PressAlpha = 0.12f
private const val PressScale = 0.96f

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

    // A quick tap releases before the layer could grow, so it shows at full strength and fades from there.
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
 * Moves the whole element while [source] is pressed, the way the style presses: 格栅 shrinks it and
 * springs it back, 清玻 spreads it like a drop of liquid and lets it overshoot once on release, 贴纸
 * sinks it [PushDepth] onto its hard shadow (a [sticker] surface with the same source drops the
 * shadow in step). Goes before clip and background so they move too; the clickable after them gets
 * the same [source].
 */
fun Modifier.pressShrink(source: InteractionSource): Modifier = this then PressShrinkElement(source)

/** How far 贴纸 sinks a pressed element: as far as its buttons' hard shadow reaches. */
val PushDepth = 3.dp

private data class PressShrinkElement(val source: InteractionSource) : ModifierNodeElement<PressShrinkNode>() {
    override fun create() = PressShrinkNode(source)
    override fun update(node: PressShrinkNode) = node.follow(source)
}

private class PressShrinkNode(private var source: InteractionSource) :
    Modifier.Node(), LayoutModifierNode, CompositionLocalConsumerModifierNode {
    private val scaleX = Animatable(1f)
    private val scaleY = Animatable(1f)
    private val push = Animatable(0f)
    private var job: Job? = null

    override fun onAttach() {
        job = coroutineScope.onPresses(
            source,
            onPress = {
                when (currentValueOf(LocalUiStyle)) {
                    UiStyle.Grid -> both({ scaleX.animateTo(PressScale, Motion.pressIn()) }, { scaleY.animateTo(PressScale, Motion.pressIn()) })
                    UiStyle.Clear -> both({ scaleX.animateTo(Motion.LensWide, Motion.lensIn()) }, { scaleY.animateTo(Motion.LensFlat, Motion.lensIn()) })
                    UiStyle.Sticker -> push.animateTo(1f, Motion.push())
                }
            },
            onRelease = {
                when (currentValueOf(LocalUiStyle)) {
                    UiStyle.Grid -> both({ scaleX.animateTo(1f, Motion.pressOut()) }, { scaleY.animateTo(1f, Motion.pressOut()) })
                    UiStyle.Clear -> both({ scaleX.animateTo(1f, Motion.lensOut(Motion.LensBackWide)) }, { scaleY.animateTo(1f, Motion.lensOut(Motion.LensBackFlat)) })
                    UiStyle.Sticker -> push.animateTo(0f, Motion.push())
                }
                // A style change between press and release leaves nothing behind.
                if (push.value != 0f) push.snapTo(0f)
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
        val depth = PushDepth.toPx()
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = this@PressShrinkNode.scaleX.value
                scaleY = this@PressShrinkNode.scaleY.value
                translationY = push.value * depth
            }
        }
    }
}

private suspend fun both(a: suspend () -> Unit, b: suspend () -> Unit) = coroutineScope {
    launch { a() }
    launch { b() }
}
