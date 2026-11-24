package clauderc.app.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import clauderc.app.data.Appearance
import clauderc.app.data.ThemeMode
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Changes the theme so the new one spreads out in a circle from where the user tapped. [start] takes a
 * picture of the screen first and keeps it on top; once the theme has changed underneath, the circle
 * grows over durReveal on easeReveal and cuts the old picture away.
 */
@Stable
class ThemeReveal internal constructor(private val scope: CoroutineScope, private val layer: GraphicsLayer) {
    internal var snapshot by mutableStateOf<ImageBitmap?>(null)
    internal var center by mutableStateOf(Offset.Zero)
    internal val radius = Animatable(0f)
    internal var waiting = false

    /** Size of the screen at the last frame. */
    internal var area = Size.Zero

    /** The circle has to reach the corner farthest from where it starts. */
    internal fun farthest(): Float {
        val c = center
        val (w, h) = area
        return maxOf(hypot(c.x, c.y), hypot(w - c.x, c.y), hypot(c.x, h - c.y), hypot(w - c.x, h - c.y))
    }

    /** True while the old theme is still showing outside the circle. */
    val running: Boolean get() = snapshot != null

    private var job: Job? = null

    /** Takes the picture, then runs [change], which should change the theme soon after. */
    fun start(center: Offset, change: () -> Unit) {
        job?.cancel()
        snapshot = null
        job = scope.launch {
            val shot = runCatching { layer.toImageBitmap() }.getOrNull()
            if (shot == null) {
                change()
                return@launch
            }
            this@ThemeReveal.center = center
            radius.snapTo(0f)
            snapshot = shot
            waiting = true
            change()
            // A pick that leaves the theme as it was never sets the circle off; drop the picture.
            delay(1_000)
            if (waiting) {
                waiting = false
                snapshot = null
            }
        }
    }
}

val LocalThemeReveal = staticCompositionLocalOf<ThemeReveal?> { null }

/** Hosts [ThemeReveal] for [content]; [key] is whatever the theme is made from. */
@Composable
fun ThemeRevealHost(key: Any, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val reveal = remember(layer) { ThemeReveal(scope, layer) }
    val motion = Clauderc.motion
    LaunchedEffect(key) {
        // Anything else that changes the theme mid-way, such as the system going dark, ends the reveal at once.
        if (!reveal.waiting) {
            reveal.snapshot = null
            return@LaunchedEffect
        }
        reveal.waiting = false
        reveal.radius.animateTo(reveal.farthest(), tween(motion.durReveal, easing = motion.easeReveal))
        reveal.snapshot = null
    }
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
                reveal.area = size
                val shot = reveal.snapshot ?: return@drawWithContent
                val hole = Path().apply { addOval(Rect(reveal.center, reveal.radius.value)) }
                clipPath(hole, ClipOp.Difference) { drawImage(shot) }
            },
    ) {
        CompositionLocalProvider(LocalThemeReveal provides reveal, content = content)
        // While the old theme's picture shows, what is under a finger is not what it sees there.
        if (reveal.running) Box(Modifier.matchParentSize().swallowTouches())
    }
}

/** Takes every touch that lands on it, so nothing under it is pressed: for a layer drawn over the page, while it is drawn. */
fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

/** The theme made from what is picked in 设置, with the reveal around it; the app and tests share it. */
@Composable
fun ClaudercRoot(look: Appearance, content: @Composable () -> Unit) {
    val dark = when (look.mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    CompositionLocalProvider(LocalAppearance provides look) {
        ClaudercTheme(darkTheme = dark, style = look.style, accent = accentColor(look.accent, dark)) {
            ThemeRevealHost(key = look, content = content)
        }
    }
}
