package clauderc.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.pressShrink
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import kotlinx.coroutines.launch

data class NavTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector, val dot: Boolean = false)

/**
 * The liquid-glass bottom navigation from the mockup: a blurred, tinted pill
 * floating 12dp above the bottom edge. Switching tabs moves the indicator's
 * leading edge first and lets the trailing edge catch up, in step with the page swap.
 * The card style trades the glass for a solid card and tints the picked tab with the accent.
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
    val card = Clauderc.style == UiStyle.Card
    val shape = RoundedCornerShape(if (card) 28.dp else 32.dp)
    val glass = if (card) {
        Modifier.background(c.card)
    } else if (hazeState != null) {
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

    // Indicator edges, in cells. A new tap retargets both from wherever they are.
    val left = remember { Animatable(selected.toFloat()) }
    val right = remember { Animatable(selected + 1f) }
    LaunchedEffect(selected) {
        val toRight = selected > left.value
        launch { left.animateTo(selected.toFloat(), if (toRight) Motion.navTrail() else Motion.navLead()) }
        launch { right.animateTo(selected + 1f, if (toRight) Motion.navLead() else Motion.navTrail()) }
    }

    Box(
        modifier
            .navigationBarsPadding()
            .padding(start = Clauderc.spacing.sp4, end = Clauderc.spacing.sp4, bottom = Clauderc.spacing.sp3)
            .fillMaxWidth()
            .height(64.dp)
            .shadow(if (card) 10.dp else 14.dp, shape, ambientColor = c.glassShadow, spotColor = c.glassShadow)
            .clip(shape)
            .then(glass)
            .drawWithContent {
                drawContent()
                if (card) return@drawWithContent
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
            .border(0.5.dp, if (card) c.line else c.glassEdge, shape)
            .padding(6.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cell = maxWidth / tabs.size
            val indicator = Modifier
                .offset(x = cell * left.value)
                .width(cell * (right.value - left.value))
                .fillMaxHeight()
            if (card) {
                Box(indicator.clip(RoundedCornerShape(22.dp)).background(c.accent.copy(alpha = 0.14f)))
            } else {
                Box(
                    indicator
                        .shadow(4.dp, RoundedCornerShape(26.dp), ambientColor = c.glassShadow, spotColor = c.glassShadow)
                        .clip(RoundedCornerShape(26.dp))
                        .background(c.glassInd)
                        .border(0.5.dp, c.glassEdge, RoundedCornerShape(26.dp)),
                )
            }
            Row(Modifier.fillMaxSize()) {
                tabs.forEachIndexed { i, tab -> NavCell(tab, i == selected, Modifier.weight(1f)) { onSelect(i) } }
            }
        }
    }
}

@Composable
private fun NavCell(tab: NavTab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Clauderc.colors
    val card = Clauderc.style == UiStyle.Card
    // The selected look fades in as the indicator arrives, like the mockup's FILL transition.
    val on by animateFloatAsState(if (selected) 1f else 0f, Motion.enter(), label = "navCell")
    val tint = lerp(c.fg2, if (card) c.accent else c.fg, on)
    val press = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressShrink(press)
            .fillMaxHeight()
            .clip(RoundedCornerShape(if (card) 22.dp else 26.dp))
            .selectable(selected = selected, interactionSource = press, indication = LocalIndication.current, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Box(Modifier.size(24.dp)) {
                Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize().alpha(1f - on))
                Icon(tab.selectedIcon, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize().alpha(on))
            }
            Text(tab.label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = tint)
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
