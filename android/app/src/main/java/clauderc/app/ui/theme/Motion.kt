package clauderc.app.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Every animation of the app, built from the mockup's --dur-* and --ease-* (design/ui-mockup,
 * 「动效规则」). Three rules:
 * 1. Things appear in durM on easeEmph and leave in durS on easeEmphIn; scrims only fade, in durS.
 * 2. Going deeper slides the new page in from the right over the old one, which gives way by a
 *    quarter; going back is the reverse. The three top-level pages swap with a 24dp shift and a fade.
 * 3. Only two things overshoot: the nav bar when it springs back onto the top-level pages, and a
 *    small control springing back to size after a press (Press.kt).
 * Loops (spinners, the pending beacon, the scan line) keep their own fixed periods, listed here too.
 */
object Motion {
    private val t = ClaudercMotion()

    fun <T> enter(): FiniteAnimationSpec<T> = tween(t.durM, easing = t.easeEmph)
    fun <T> exit(): FiniteAnimationSpec<T> = tween(t.durS, easing = t.easeEmphIn)
    fun <T> fade(): FiniteAnimationSpec<T> = tween(t.durS)

    /**
     * Small parts over the composer and the snackbar: fade, move [px] into place and take their room. One
     * coming up from the bottom is drawn whole from the first frame, over what is above it, while its room
     * opens under it; one coming down from the top unrolls inside its room.
     */
    fun liftIn(px: Int, edge: Alignment.Vertical = Alignment.Bottom): EnterTransition =
        fadeIn(enter()) + slideInVertically(enter()) { px } + expandVertically(enter(), expandFrom = edge, clip = edge != Alignment.Bottom)
    fun liftOut(px: Int, edge: Alignment.Vertical = Alignment.Bottom): ExitTransition =
        fadeOut(exit()) + slideOutVertically(exit()) { px } + shrinkVertically(exit(), shrinkTowards = edge, clip = edge != Alignment.Bottom)

    /** Panels and sheets come up from the bottom edge. */
    val panelIn: EnterTransition = slideInVertically(enter()) { it }
    val panelOut: ExitTransition = slideOutVertically(exit()) { it }

    // Pages. Back keeps the leaving page on top (NavHost lowers the target on pop), so it slides off to the right.
    val pushIn: EnterTransition = slideInHorizontally(enter()) { it }
    val pushOut: ExitTransition = slideOutHorizontally(enter()) { -it / 4 }
    val popIn: EnterTransition = slideInHorizontally(enter()) { -it / 4 }
    val popOut: ExitTransition = slideOutHorizontally(enter()) { it }

    /** The back gesture's slide, on a [GestureEasing] that [dragging] drives. */
    fun gestureEasing(dragging: () -> Boolean): Easing = GestureEasing(dragging, t.easeEmph)
    fun gestureIn(easing: Easing): EnterTransition = slideInHorizontally(tween(t.durM, easing = easing)) { -it / 4 }
    fun gestureOut(easing: Easing): ExitTransition = slideOutHorizontally(tween(t.durM, easing = easing)) { it }

    /** Top-level tabs: [dir] is +1 toward the right-hand tab. */
    fun tabIn(px: Int, dir: Int): EnterTransition = slideInHorizontally(enter()) { px * dir } + fadeIn(enter())
    fun tabOut(px: Int, dir: Int): ExitTransition = slideOutHorizontally(exit()) { -px * dir / 2 } + fadeOut(exit())

    /** The tool row chevron and the group fold arrow turn like the mockup's transform transition. */
    fun <T> turn(): FiniteAnimationSpec<T> = tween(t.durS, easing = t.easeEmph)

    /** A long tool detail folding back after 「收起」 moves on screen as a whole, so it starts and ends slow (durL). */
    fun <T> settle(): FiniteAnimationSpec<T> = tween(t.durL, easing = t.easeReveal)

    /**
     * Rows newly shown in a tool detail fade in from the top down, each 16ms after the one above and at
     * most 12 apart; this is row [i]'s progress [millis] after they started (demo.html fadeRows).
     */
    fun rowIn(i: Int, millis: Float): Float = t.easeEmph.transform(((millis - minOf(i, 12) * 16) / t.durM).coerceIn(0f, 1f))
    val rowsInMillis: Int get() = t.durM + 12 * 16

    /** On 「收起」 the extra rows fade out from the bottom up, each 8ms after the one below and at most 6 apart. */
    fun rowOut(j: Int, millis: Float): Float = t.easeEmphIn.transform(((millis - minOf(j, 6) * 8) / t.durS).coerceIn(0f, 1f))
    val rowsOutMillis: Int get() = t.durS + 6 * 8

    /**
     * The nav bar's indicator keeps pace with the tab swap: the edge on the side of travel leads,
     * the other follows 70ms later, so it stretches a little and settles without bouncing.
     */
    fun <T> navLead(): FiniteAnimationSpec<T> = enter()
    fun <T> navTrail(): FiniteAnimationSpec<T> = tween(t.durM, delayMillis = 70, easing = t.easeEmph)

    /** The nav bar drops 140% of its height away on deeper pages and springs back onto the top-level ones. */
    val navBarIn: EnterTransition = slideInVertically(tween(t.durL, easing = t.easeSpring)) { it * 7 / 5 }
    val navBarOut: ExitTransition = slideOutVertically(exit()) { it * 7 / 5 }

    /** Pressed small controls shrink in 90ms and spring back on release with the nav bar's curve. */
    fun <T> pressIn(): FiniteAnimationSpec<T> = tween(90, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
    fun <T> pressOut(): FiniteAnimationSpec<T> = tween(t.durS, easing = t.easeSpring)

    /** 清玻 presses like a drop of liquid: it spreads to [LensWide] x [LensFlat] in 120ms. */
    fun <T> lensIn(): FiniteAnimationSpec<T> = tween(120, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))

    /** On release the drop overshoots once the other way, to [over], and settles, in 320ms all told. */
    fun lensOut(over: Float): FiniteAnimationSpec<Float> = keyframes {
        durationMillis = 320
        over at 160 using LensEasing
        1f at 320 using LensEasing
    }

    /** 贴纸 pops the picked nav icon in 460ms on the spring curve (ci-pop). */
    fun <T> iconPop(): FiniteAnimationSpec<T> = tween(460, easing = t.easeSpring)

    /** 贴纸 sinks onto its hard shadow and pops back up on the spring curve, both in durS. */
    fun <T> push(): FiniteAnimationSpec<T> = tween(t.durS, easing = t.easeSpring)

    const val LensWide = 1.04f
    const val LensFlat = 0.93f
    /** 清玻's overshoot on release: a little narrow and tall. */
    const val LensBackWide = 0.985f
    const val LensBackFlat = 1.03f
    private val LensEasing = CubicBezierEasing(0.25f, 0.8f, 0.4f, 1f)

    // Loops: one turn of the spinner and one pulse of the pending beacon, both read off [LoopClock].
    const val SPIN_MILLIS = 900
    const val BEACON_MILLIS = 2400
    /** The scan line crosses the viewfinder in 3.6s each way. */
    val scanLine: InfiniteRepeatableSpec<Float> = infiniteRepeatable(tween(3600, easing = FastOutSlowInEasing), RepeatMode.Reverse)
}

/** Loops move on at most this often, about 30 times a second; a small spinner needs no more. */
const val LOOP_FRAME_MILLIS = 33L

/**
 * One clock for every loop on screen. It moves on at most every [LOOP_FRAME_MILLIS] and only while a
 * loop is shown, and all loops read it, so two spinners never ask for frames of their own.
 */
@Stable
class LoopClock {
    var millis by mutableLongStateOf(0L)
        private set
    private var users by mutableIntStateOf(0)

    /** Keeps the clock going while the calling composable is shown. */
    @Composable
    fun Use() {
        DisposableEffect(this) {
            users++
            onDispose { users-- }
        }
    }

    suspend fun run() {
        snapshotFlow { users > 0 }.collectLatest { active ->
            while (active) {
                millis = withInfiniteAnimationFrameMillis { it }
                delay(LOOP_FRAME_MILLIS)
            }
        }
    }
}

/** Provided by [ClaudercTheme]; the default never moves, so a loop outside the theme stands still. */
val LocalLoopClock = staticCompositionLocalOf { LoopClock() }

/**
 * Draws a list into a layer of its own while it stands still. The phone sends the whole screen to the GPU
 * again for every frame, so a loop elsewhere, the status line, a spinner or a text cursor, used to redraw
 * every message with it; now it redraws only itself, a change inside the list only its own part, and a
 * page sliding in or out moves the layer as a picture. A scrolling list changes every frame anyway, so it
 * draws straight while it scrolls.
 */
fun Modifier.stillLayer(list: LazyListState): Modifier =
    graphicsLayer { compositingStrategy = if (list.isScrollInProgress) CompositingStrategy.Auto else CompositingStrategy.Offscreen }

/**
 * The back gesture scrubs the pop by finger progress, so while [dragging] the curve is a straight
 * line and the page keeps pace with the finger. Once the finger lets go, NavHost plays the rest of
 * the pop on this same curve, and from the release point on it eases out on [finish]: the remaining
 * distance takes the remaining share of durM and settles like the mockup's 「减速收尾」. A cancel runs
 * back below the release point, where the curve is still the line.
 */
private class GestureEasing(private val dragging: () -> Boolean, private val finish: Easing) : Easing {
    private var last = 0f
    private var release = -1f

    override fun transform(fraction: Float): Float {
        if (dragging()) {
            release = -1f
            last = fraction
            return fraction
        }
        if (release < 0f) release = last
        if (fraction <= release || release >= 1f) return fraction
        return release + (1f - release) * finish.transform((fraction - release) / (1f - release))
    }
}

/**
 * Shows [content] with the lift rule: it fades in rising [distance] into place, from below,
 * or from above when [fromTop], and leaves the same way, quicker.
 */
@Composable
fun Lift(
    visible: Boolean,
    modifier: Modifier = Modifier,
    fromTop: Boolean = false,
    distance: Dp = 8.dp,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val px = with(LocalDensity.current) { distance.roundToPx() } * if (fromTop) -1 else 1
    val edge = if (fromTop) Alignment.Top else Alignment.Bottom
    AnimatedVisibility(visible, modifier, enter = Motion.liftIn(px, edge), exit = Motion.liftOut(px, edge), content = content)
}
