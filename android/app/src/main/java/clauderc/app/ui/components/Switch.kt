package clauderc.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.Clauderc

/**
 * .switch in each style. 清玻: a 48 × 28 pill, grey off and accent on, its white knob springing across
 * and stretching while pressed. 格栅: a square 44 × 24 outline whose square knob steps across, filled
 * solid when on. 贴纸: a 52 × 30 ink-outlined sticker, accent when on, its ringed knob tilting as it
 * lands; it shrinks to 94% the moment it is pressed and is back the moment it lets go.
 */
@Composable
fun Switch(on: Boolean, press: MutableInteractionSource) {
    val c = Clauderc.colors
    val m = Clauderc.motion
    val pressed by press.collectIsPressedAsState()
    when (Clauderc.style) {
        UiStyle.Clear -> {
            val track by animateColorAsState(if (on) c.accent else c.fg.copy(alpha = 0.18f), tween(m.durS, easing = m.easeEmph), label = "track")
            val wide by animateDpAsState(if (pressed) 28.dp else 22.dp, tween(m.durS, easing = m.easeEmph), label = "knobWidth")
            val x by animateDpAsState(if (on) (if (pressed) 14.dp else 20.dp) else 0.dp, tween(m.durM, easing = m.easeSpring), label = "knob")
            Box(Modifier.size(48.dp, 28.dp).clip(RoundedCornerShape(14.dp)).background(track)) {
                Box(
                    Modifier
                        .offset(x = 3.dp + x, y = 3.dp)
                        .size(wide, 22.dp)
                        .knobShadow()
                        .background(Color.White, RoundedCornerShape(11.dp)),
                )
            }
        }
        UiStyle.Grid -> {
            // steps(3, end): the knob jumps a third of the way at a time.
            val p by animateFloatAsState(if (on) 1f else 0f, tween(m.durS, easing = { t -> kotlin.math.floor(t * 3f) / 3f }), label = "knob")
            Box(Modifier.size(44.dp, 24.dp).background(if (on) c.fg else Color.Transparent).border(2.dp, c.fg)) {
                Box(Modifier.offset(x = 5.dp + 20.dp * p, y = 5.dp).size(14.dp).background(if (on) c.bg else c.fg))
            }
        }
        UiStyle.Sticker -> {
            val fill by animateColorAsState(if (on) c.accent else c.card, tween(m.durS, easing = m.easeEmph), label = "track")
            val x by animateDpAsState(if (on) 22.dp else 0.dp, tween(m.durM, easing = m.easeSpring), label = "knob")
            val tilt by animateFloatAsState(if (on) -8f else 0f, tween(m.durM, easing = m.easeSpring), label = "tilt")
            Box(
                Modifier
                    .graphicsLayer {
                        scaleX = if (pressed) 0.94f else 1f
                        scaleY = scaleX
                    }
                    .size(52.dp, 30.dp)
                    .sticker(fill, c.ink, 15.dp),
            ) {
                Box(
                    Modifier
                        .offset(x = 5.dp + x, y = 5.dp)
                        .size(20.dp)
                        .graphicsLayer { rotationZ = tilt }
                        .background(c.card, CircleShape)
                        .border(2.dp, c.ink, CircleShape),
                )
            }
        }
    }
}

/**
 * The 清玻 knob's soft shadow (box-shadow: 0 1px 3px rgba(0, 0, 0, 0.28)) drawn as two faint rings 1dp
 * down, not as elevation: the theme circle's snapshot of the old page would leave elevation out.
 */
private fun Modifier.knobShadow(): Modifier = drawBehind {
    for ((grow, alpha) in listOf(1.5.dp to 0.08f, 0.75.dp to 0.12f)) {
        val g = grow.toPx()
        drawRoundRect(
            Color.Black.copy(alpha = alpha),
            Offset(-g, -g + 1.dp.toPx()),
            Size(size.width + 2 * g, size.height + 2 * g),
            CornerRadius(size.height / 2 + g),
        )
    }
}
