package clauderc.app.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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

    /** Small parts over the composer and the snackbar: fade, move [px] into place and take their room. */
    fun liftIn(px: Int, edge: Alignment.Vertical = Alignment.Bottom): EnterTransition =
        fadeIn(enter()) + slideInVertically(enter()) { px } + expandVertically(enter(), expandFrom = edge)
    fun liftOut(px: Int, edge: Alignment.Vertical = Alignment.Bottom): ExitTransition =
        fadeOut(exit()) + slideOutVertically(exit()) { px } + shrinkVertically(exit(), shrinkTowards = edge)

    /** Panels and sheets come up from the bottom edge. */
    val panelIn: EnterTransition = slideInVertically(enter()) { it }
    val panelOut: ExitTransition = slideOutVertically(exit()) { it }

    // Pages. Back keeps the leaving page on top (NavHost lowers the target on pop), so it slides off to the right.
    val pushIn: EnterTransition = slideInHorizontally(enter()) { it }
    val pushOut: ExitTransition = slideOutHorizontally(enter()) { -it / 4 }
    val popIn: EnterTransition = slideInHorizontally(enter()) { -it / 4 }
    val popOut: ExitTransition = slideOutHorizontally(enter()) { it }

    // The back gesture scrubs the pop by finger progress, so it stays linear and the page keeps pace with the finger.
    private fun <T> track(): FiniteAnimationSpec<T> = tween(t.durM, easing = LinearEasing)
    val gestureIn: EnterTransition = slideInHorizontally(track()) { -it / 4 }
    val gestureOut: ExitTransition = slideOutHorizontally(track()) { it }

    /** Top-level tabs: [dir] is +1 toward the right-hand tab. */
    fun tabIn(px: Int, dir: Int): EnterTransition = slideInHorizontally(enter()) { px * dir } + fadeIn(enter())
    fun tabOut(px: Int, dir: Int): ExitTransition = slideOutHorizontally(exit()) { -px * dir / 2 } + fadeOut(exit())

    /** The tool row chevron and the group fold arrow turn like the mockup's transform transition. */
    fun <T> turn(): FiniteAnimationSpec<T> = tween(t.durS, easing = t.easeEmph)

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

    // Loops.
    val spin: InfiniteRepeatableSpec<Float> = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart)
    val beacon: InfiniteRepeatableSpec<Float> = infiniteRepeatable(tween(2400, easing = LinearEasing))
    val scanLine: InfiniteRepeatableSpec<Float> = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse)
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
