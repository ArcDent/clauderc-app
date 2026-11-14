package clauderc.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.Motion
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

data class NavTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector, val dot: Boolean = false)

private val IndicatorStretch = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)

/**
 * The liquid-glass bottom navigation from the mockup: a blurred, tinted pill
 * floating 12dp above the bottom edge. Switching tabs stretches the indicator
 * over both cells, then springs it into the new cell.
 */
@Composable
fun GlassNavBar(
    tabs: List<NavTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
) {
    val c = Clauderc.colors
    val m = Clauderc.motion
    val shape = RoundedCornerShape(32.dp)
    val glass = if (hazeState != null) {
        Modifier.hazeBlur(
            HazeInput.Sources(hazeState),
            HazeBlurStyle {
                blurRadius(22.dp)
                noiseFactor(0f)
                backgroundColor(c.bg)
                colorEffects(
                    listOf(
                        HazeColorEffect.colorFilter(ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.9f) })),
                        HazeColorEffect.tint(c.glass),
                    ),
                )
                fallbackColorEffect(HazeColorEffect.tint(c.glass.copy(alpha = 0.92f)))
            },
        )
    } else {
        Modifier.background(c.glass.copy(alpha = 0.92f))
    }

    var from by remember { mutableIntStateOf(selected) }
    var to by remember { mutableIntStateOf(selected) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected != to) {
            from = to
            to = selected
            progress.snapTo(0f)
            progress.animateTo(1f, Motion.navIndicator)
        }
    }

    Box(
        modifier
            .navigationBarsPadding()
            .padding(start = Clauderc.spacing.sp4, end = Clauderc.spacing.sp4, bottom = Clauderc.spacing.sp3)
            .fillMaxWidth()
            .height(64.dp)
            .shadow(14.dp, shape, ambientColor = c.glassShadow, spotColor = c.glassShadow)
            .clip(shape)
            .then(glass)
            .drawWithContent {
                drawContent()
                // Sheen, inner highlight and hairline edge (.navbar::before and its box-shadow).
                drawRect(Brush.verticalGradient(0f to c.glassSheen, 0.38f to Color.Transparent))
                drawRect(
                    Brush.radialGradient(
                        0f to c.glassSheen, 0.46f to Color.Transparent,
                        center = Offset(size.width * 0.2f, -size.height * 0.3f),
                        radius = size.width * 0.7f,
                    ),
                )
                drawLine(c.glassHi, Offset(32.dp.toPx(), 0.5f), Offset(size.width - 32.dp.toPx(), 0.5f), 1.dp.toPx())
                drawLine(c.glassLo, Offset(32.dp.toPx(), size.height - 0.5f), Offset(size.width - 32.dp.toPx(), size.height - 0.5f), 1.dp.toPx())
            }
            .border(0.5.dp, c.glassEdge, shape)
            .padding(6.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cell = maxWidth / tabs.size
            val t = progress.value
            val fromL = cell * from
            val toL = cell * to
            val unionL = minOf(fromL, toL)
            val unionR = maxOf(fromL, toL) + cell
            val (left, right, sy) = if (t < 0.38f) {
                val u = IndicatorStretch.transform(t / 0.38f)
                Triple(lerp(fromL, unionL, u), lerp(fromL + cell, unionR, u), 1f + (0.86f - 1f) * u)
            } else {
                val u = m.easeSpring.transform((t - 0.38f) / 0.62f)
                Triple(lerp(unionL, toL, u), lerp(unionR, toL + cell, u), 0.86f + (1f - 0.86f) * u)
            }
            Box(
                Modifier
                    .offset(x = left)
                    .width(right - left)
                    .fillMaxHeight()
                    .graphicsLayer { scaleY = sy }
                    .shadow(4.dp, RoundedCornerShape(26.dp), ambientColor = c.glassShadow, spotColor = c.glassShadow)
                    .clip(RoundedCornerShape(26.dp))
                    .background(c.glassInd)
                    .border(0.5.dp, c.glassEdge, RoundedCornerShape(26.dp)),
            )
            Row(Modifier.fillMaxSize()) {
                tabs.forEachIndexed { i, tab -> NavCell(tab, i == selected, Modifier.weight(1f)) { onSelect(i) } }
            }
        }
    }
}

@Composable
private fun NavCell(tab: NavTab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Clauderc.colors
    val m = Clauderc.motion
    val scale = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) {
            scale.animateTo(1f, keyframes {
                durationMillis = 560
                0.8f at 140 using m.easeEmph
                1.14f at 140 + 252 using m.easeEmph
                1f at 560
            })
        }
    }
    Box(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(26.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Icon(
                if (selected) tab.selectedIcon else tab.icon,
                contentDescription = null,
                tint = if (selected) c.fg else c.fg2,
                modifier = Modifier.size(24.dp).scale(scale.value),
            )
            Text(tab.label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (selected) c.fg else c.fg2)
        }
        if (tab.dot) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(x = 10.5.dp, y = 3.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(c.accent),
            )
        }
    }
}
