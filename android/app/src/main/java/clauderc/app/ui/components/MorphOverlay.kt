package clauderc.app.ui.components

import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Holds the last window-space bounds of a clickable trigger for 浮墨's morph-from-trigger overlays. */
@Stable
class AnchorBox {
    var rect: Rect? = null
}

@Composable
fun rememberAnchorBox(): AnchorBox = remember { AnchorBox() }

/** Captures this element's window coordinates into [box] whenever laid out. */
fun Modifier.captureAnchor(box: AnchorBox): Modifier = onGloballyPositioned { coords ->
    if (coords.isAttached) box.rect = coords.boundsInWindow()
}

/**
 * Allows controls inside a [MorphOverlay] to collapse the panel smoothly back into its trigger
 * before invoking an optional follow-up navigation or state change.
 */
val LocalMorphClose = staticCompositionLocalOf<((after: (() -> Unit)?) -> Unit)?> { null }

/**
 * 浮墨's signature floating panel (demo.html `morph()`):
 * Opens in a full-window transparent [Dialog] so it floats cleanly above all chrome and supports
 * nesting (e.g. Commit morph -> Diff morph). Its clip rect & corner radius bloom elastically from
 * [anchor] with [Motion.inkGoo] overshoot into a 30dp floating panel while the body fades & rises
 * into place, and shrinks right back into [anchor] on dismiss.
 *
 * When [tall] is true the caller owns internal scrolling (such as a sticky header + scrollable diff +
 * pinned footer); otherwise the panel wraps [content] in a vertical scroll up to 82% viewport height.
 */
@Composable
fun MorphOverlay(
    onDismiss: () -> Unit,
    anchor: Rect? = null,
    fromTop: Boolean = false,
    tall: Boolean = false,
    smoothResize: Boolean = true,
    tag: String = "morphPanel",
    dismissEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Clauderc.colors
    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val scope = rememberCoroutineScope()

    val progress = remember { Animatable(0f) }
    val scrimAlpha = remember { Animatable(0f) }
    val bodyAlpha = remember { Animatable(0f) }
    val bodyShift = remember { Animatable(if (fromTop) -10f else 10f) }
    var closing by remember { mutableStateOf(false) }

    var outerWindowBounds by remember { mutableStateOf(Rect.Zero) }
    val bleedDp = 12.dp
    val bleedPx = with(density) { bleedDp.toPx() }
    val panelRadiusPx = with(density) { 30.dp.toPx() }

    LaunchedEffect(Unit) {
        launch { scrimAlpha.animateTo(1f, tween(240)) }
        launch { progress.animateTo(1f, tween(Motion.INK_MORPH_OPEN_MS, easing = Motion.inkGoo)) }
        launch {
            delay(95)
            launch { bodyAlpha.animateTo(1f, tween(220, easing = Motion.inkEase)) }
            launch { bodyShift.animateTo(0f, tween(280, easing = Motion.inkEase)) }
        }
    }

    val requestClose: ((() -> Unit)?) -> Unit = remember(onDismiss) {
        { after ->
            if (!closing) {
                closing = true
                scope.launch {
                    launch { bodyAlpha.animateTo(0f, tween(110)) }
                    launch { scrimAlpha.animateTo(0f, tween(220)) }
                    delay(50)
                    progress.animateTo(0f, tween(Motion.INK_MORPH_CLOSE_MS, easing = Motion.inkEaseIn))
                    onDismiss()
                    after?.invoke()
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { if (dismissEnabled) requestClose(null) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        Box(Modifier.fillMaxSize()) {
            // Scrim behind the floating morph panel.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = scrimAlpha.value }
                    .background(c.scrim)
                    .clickable(remember { MutableInteractionSource() }, indication = null, enabled = dismissEnabled) { requestClose(null) }
                    .testTag("morphScrim"),
            )

            // Outer bleed box (12dp around the 30dp panel so spring overshoot visibly blooms outward).
            val alignMod = if (fromTop) {
                Modifier.align(Alignment.TopCenter).statusBarsPadding().imePadding()
            } else {
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 2.dp)
            }
            val clipShape = remember(anchor, fromTop, bleedPx, panelRadiusPx, density) {
                MorphClipShape(
                    progress = { progress.value },
                    anchorInWindow = anchor,
                    outerInWindow = { outerWindowBounds },
                    fromTop = fromTop,
                    bleedPx = bleedPx,
                    endRadiusPx = panelRadiusPx,
                )
            }

            Box(
                alignMod
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .onGloballyPositioned { coords ->
                        if (coords.isAttached) outerWindowBounds = coords.boundsInWindow()
                    }
                    .graphicsLayer {
                        clip = true
                        shape = clipShape
                    }
                    .padding(bleedDp)
                    .testTag(tag),
            ) {
                val maxH = if (tall) screenHeightDp * 0.86f else screenHeightDp * 0.80f
                val cardShape = RoundedCornerShape(30.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxH)
                        .graphicsLayer {
                            val over = (progress.value - 1f).coerceAtLeast(0f)
                            scaleX = 1f + over * 0.25f
                            scaleY = 1f + over * 0.25f
                        }
                        .shadow(if (c.isDark) 20.dp else 14.dp, cardShape, clip = false)
                        .inkSurface(c.float, c.edge, 30.dp)
                        .then(
                            if (smoothResize) {
                                Modifier.animateContentSize(animationSpec = tween(Motion.INK_MORPH_RESIZE_MS, easing = Motion.inkEase))
                            } else {
                                Modifier
                            },
                        )
                        .pointerInput(Unit) { detectTapGestures {} },
                ) {
                    val bodyMod = Modifier
                        .fillMaxWidth()
                        .then(if (tall) Modifier else Modifier.verticalScroll(rememberScrollState()))
                        .graphicsLayer {
                            alpha = bodyAlpha.value
                            translationY = bodyShift.value * density.density
                        }
                    Column(bodyMod) {
                        CompositionLocalProvider(
                            LocalOverlayActions provides true,
                            LocalMorphClose provides requestClose,
                        ) {
                            content()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Custom [Shape] driving 浮墨's clip-path morph from [anchorInWindow] to the inset panel bounds
 * inside the outer bleed container, mirroring demo.html's `inset(...) round ...` clip transition.
 */
private class MorphClipShape(
    private val progress: () -> Float,
    private val anchorInWindow: Rect?,
    private val outerInWindow: () -> Rect,
    private val fromTop: Boolean,
    private val bleedPx: Float,
    private val endRadiusPx: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val p = progress()
        val target = Rect(bleedPx, bleedPx, (size.width - bleedPx).coerceAtLeast(bleedPx), (size.height - bleedPx).coerceAtLeast(bleedPx))
        val outer = outerInWindow()
        val start = if (anchorInWindow != null && !outer.isEmpty && anchorInWindow.width > 0f && anchorInWindow.height > 0f) {
            Rect(
                left = (anchorInWindow.left - outer.left).coerceIn(0f, size.width),
                top = (anchorInWindow.top - outer.top).coerceIn(0f, size.height),
                right = (anchorInWindow.right - outer.left).coerceIn(0f, size.width),
                bottom = (anchorInWindow.bottom - outer.top).coerceIn(0f, size.height),
            )
        } else {
            val side = with(density) { 48.dp.toPx() }
            val cx = size.width / 2f
            val cy = if (fromTop) (bleedPx + side / 2f) else (size.height - bleedPx - side / 2f)
            Rect(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
        }
        val startRad = minOf(with(density) { 28.dp.toPx() }, minOf(start.width, start.height) / 2f).coerceAtLeast(0f)
        val l = lerp(start.left, target.left, p).coerceIn(0f, size.width)
        val t = lerp(start.top, target.top, p).coerceIn(0f, size.height)
        val r = lerp(start.right, target.right, p).coerceIn(l, size.width)
        val b = lerp(start.bottom, target.bottom, p).coerceIn(t, size.height)
        val rad = lerp(startRad, endRadiusPx, p.coerceIn(0f, 1f))
        return Outline.Rounded(RoundRect(Rect(l, t, r, b), CornerRadius(rad, rad)))
    }
}
