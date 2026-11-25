package clauderc.app.ui.components

import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Provides the 0..1 title rule draw-in fraction for [SheetHead] inside a [PlateSlipPanel]. */
internal val LocalPlateSlipLine = staticCompositionLocalOf<(() -> Float)?> { null }

/**
 * Horizontal print-in / wipe-out clip shape shared by [PlateSlipPanel] and 印版's Git tab pane swap
 * (demo.html `clip-path: inset(0 100% 0 0)` -> `inset(0)` -> `inset(0 0 0 100%)`).
 */
class PlateClipShape(
    private val leftFrac: () -> Float,
    private val rightFrac: () -> Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val l = (leftFrac().coerceIn(0f, 1f)) * size.width
        val r = (rightFrac().coerceIn(0f, 1f)) * size.width
        return Outline.Rectangle(Rect(l, 0f, r.coerceAtLeast(l), size.height))
    }
}

/**
 * 印版's signature letterpress slip panel (demo.html `.scrim` + `.slip`):
 * Diagonal hatched paper/ink scrim, bottom-anchored 14dp-inset square sheet with 2dp ink border,
 * 5dp hard solid-ink shadow, and four 12dp outer corner crop marks. Prints in from the left on
 * [Motion.plateEasePress] and wipes out to the right on dismiss without ever clipping shadow or marks.
 */
@Composable
fun PlateSlipPanel(
    onDismiss: () -> Unit,
    tag: String = "plateSlip",
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Clauderc.colors
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val scope = rememberCoroutineScope()

    val rightFrac = remember { Animatable(0f) }
    val leftFrac = remember { Animatable(0f) }
    val scrimAlpha = remember { Animatable(0f) }
    val titleRule = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        launch { scrimAlpha.animateTo(1f, tween(240)) }
        launch { rightFrac.animateTo(1f, tween(Motion.PLATE_SLIP_OPEN_MS, easing = Motion.plateEasePress)) }
        launch {
            delay(100)
            titleRule.animateTo(1f, tween(560, easing = Motion.plateEasePress))
        }
    }

    val requestClose: ((() -> Unit)?) -> Unit = remember(onDismiss) {
        { after ->
            if (!closing) {
                closing = true
                scope.launch {
                    launch { scrimAlpha.animateTo(0f, tween(200)) }
                    leftFrac.animateTo(1f, tween(Motion.PLATE_SLIP_CLOSE_MS, easing = Motion.inkEaseIn))
                    onDismiss()
                    after?.invoke()
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { requestClose(null) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        Box(Modifier.fillMaxSize()) {
            // Diagonal hatched paper-and-ink scrim (demo.html repeating-linear-gradient -45deg 1px / 7px).
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = scrimAlpha.value }
                    .drawBehind {
                        drawRect(c.bg.copy(alpha = 0.86f))
                        val lineCol = c.fg.copy(alpha = 0.20f)
                        val step = 7.dp.toPx()
                        val stroke = 1.dp.toPx()
                        val clip = Path().apply { addRect(Rect(0f, 0f, size.width, size.height)) }
                        clipPath(clip) {
                            var x = -size.height
                            while (x < size.width + size.height) {
                                drawLine(lineCol, Offset(x, 0f), Offset(x + size.height, size.height), stroke)
                                x += step
                            }
                        }
                    }
                    .clickable(remember { MutableInteractionSource() }, indication = null) { requestClose(null) }
                    .testTag("plateScrim"),
            )

            // Outer clip container includes 14dp side / 18dp bottom / 16dp top bleed so 5dp shadow & -5dp crop marks stay unclipped.
            val clipShape = remember { PlateClipShape({ leftFrac.value }, { rightFrac.value }) }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .fillMaxWidth()
                    .graphicsLayer {
                        clip = true
                        shape = clipShape
                    }
                    .padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 18.dp)
                    .testTag(tag),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = screenHeightDp * 0.78f)
                        .drawBehind {
                            val sh = 5.dp.toPx()
                            drawRect(c.fg, Offset(sh, sh), size)
                            // Four 12dp outer corner crop marks offset 5dp outside the frame.
                            val t = 12.dp.toPx()
                            val o = 5.dp.toPx()
                            val w = 1.5.dp.toPx()
                            drawLine(c.fg, Offset(-o, -o), Offset(-o + t, -o), w)
                            drawLine(c.fg, Offset(-o, -o), Offset(-o, -o + t), w)
                            drawLine(c.fg, Offset(size.width + o - t, -o), Offset(size.width + o, -o), w)
                            drawLine(c.fg, Offset(size.width + o, -o), Offset(size.width + o, -o + t), w)
                            drawLine(c.fg, Offset(-o, size.height + o), Offset(-o + t, size.height + o), w)
                            drawLine(c.fg, Offset(-o, size.height + o - t), Offset(-o, size.height + o), w)
                            drawLine(c.fg, Offset(size.width + o - t, size.height + o), Offset(size.width + o, size.height + o), w)
                            drawLine(c.fg, Offset(size.width + o, size.height + o - t), Offset(size.width + o, size.height + o), w)
                        }
                        .plateBox(c.bg, c.fg, 2.dp)
                        .pointerInput(Unit) { detectTapGestures {} }
                        .verticalScroll(rememberScrollState())
                        .padding(top = 16.dp, bottom = 10.dp),
                ) {
                    CompositionLocalProvider(
                        LocalOverlayActions provides true,
                        LocalMorphClose provides requestClose,
                        LocalPlateSlipLine provides { titleRule.value },
                    ) {
                        content()
                    }
                }
            }
        }
    }
}
