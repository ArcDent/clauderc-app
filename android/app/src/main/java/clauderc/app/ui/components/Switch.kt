package clauderc.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.unit.dp
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.PressKind
import clauderc.app.ui.theme.pressProgress
import clauderc.app.ui.theme.pressShrink

/**
 * .switch in each of the four looks:
 * - 印版: square 44 x 24 ink-outlined track with a stepped square knob.
 * - 清玻: 48 x 28 pill, stretching knob while pressed.
 * - 素卡: 48 x 28 warm pill, accent when on.
 * - 浮墨: 48 x 28 floating pill, inverse/accent when on.
 */
@Composable
fun Switch(on: Boolean, press: MutableInteractionSource) {
    val c = Clauderc.colors
    val m = Clauderc.motion
    val pose = Modifier.pressShrink(press, PressKind.Switch)
    when (Clauderc.style) {
        UiStyle.Plate -> {
            val p by animateFloatAsState(if (on) 1f else 0f, tween(m.durS, easing = { t -> kotlin.math.floor(t * 3f) / 3f }), label = "knob")
            Box(pose.size(44.dp, 24.dp).background(if (on) c.fg else Color.Transparent).border(2.dp, c.fg)) {
                Box(Modifier.offset(x = 5.dp + 20.dp * p, y = 5.dp).size(14.dp).background(if (on) c.bg else c.fg))
            }
        }
        UiStyle.Ink -> {
            val track by animateColorAsState(if (on) c.accent else c.surface2, tween(m.durS, easing = m.easeEmph), label = "track")
            val x by animateDpAsState(if (on) 20.dp else 0.dp, tween(m.durM, easing = m.easeSpring), label = "knob")
            Box(pose.size(48.dp, 28.dp).clip(RoundedCornerShape(14.dp)).background(track).border(1.dp, c.edge, RoundedCornerShape(14.dp))) {
                Box(Modifier.offset(x = 3.dp + x, y = 3.dp).size(22.dp).clip(CircleShape).background(Color.White))
            }
        }
        UiStyle.Tile -> {
            val track by animateColorAsState(if (on) c.accent else c.surface2, tween(m.durS, easing = m.easeEmph), label = "track")
            val x by animateDpAsState(if (on) 20.dp else 0.dp, tween(m.durM, easing = m.easeSpring), label = "knob")
            Box(pose.size(48.dp, 28.dp).clip(RoundedCornerShape(14.dp)).background(track)) {
                Box(Modifier.offset(x = 3.dp + x, y = 3.dp).size(22.dp).knobShadow().clip(CircleShape).background(Color.White))
            }
        }
        UiStyle.Clear -> {
            val track by animateColorAsState(if (on) c.accent else c.fg.copy(alpha = 0.18f), tween(m.durS, easing = m.easeEmph), label = "track")
            val hit by pressProgress(press, PressKind.Switch)
            val wide = 22.dp + 6.dp * hit
            val x by animateDpAsState(if (on) 20.dp else 0.dp, tween(m.durM, easing = m.easeSpring), label = "knob")
            Box(pose.size(48.dp, 28.dp).clip(RoundedCornerShape(14.dp)).background(track)) {
                Box(
                    Modifier
                        .offset(x = 3.dp + x - if (on) 6.dp * hit else 0.dp, y = 3.dp)
                        .size(wide, 22.dp)
                        .knobShadow()
                        .background(Color.White, RoundedCornerShape(11.dp)),
                )
            }
        }
    }
}

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
