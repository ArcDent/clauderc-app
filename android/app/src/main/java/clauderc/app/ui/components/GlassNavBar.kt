package clauderc.app.ui.components

import clauderc.app.ui.theme.styled
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.MonoFamily
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.pressShrink
import clauderc.app.ui.theme.StickerPress
import clauderc.app.ui.theme.stickerPushed
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import kotlinx.coroutines.launch

data class NavTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector, val dot: Boolean = false)

/**
 * The bottom navigation. 清玻: a blurred, tinted glass pill floating 12dp above the bottom edge, with
 * bright rims and a lens over the picked tab. 格栅: a bar docked to the bottom under a heavy rule, the
 * picked tab a solid block with its icon and label reversed out. 贴纸: an ink-outlined capsule, the
 * picked tab a yellow sticker. Switching tabs moves the indicator's leading edge
 * first and lets the trailing edge catch up, in step with the page swap.
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
    val ui = Clauderc.style

    // Indicator edges, in cells. A new tap retargets both from wherever they are.
    val left = remember { Animatable(selected.toFloat()) }
    val right = remember { Animatable(selected + 1f) }
    LaunchedEffect(selected) {
        val toRight = selected > left.value
        launch { left.animateTo(selected.toFloat(), if (toRight) Motion.navTrail() else Motion.navLead()) }
        launch { right.animateTo(selected + 1f, if (toRight) Motion.navLead() else Motion.navTrail()) }
    }

    val bar = when (ui) {
        UiStyle.Grid -> Modifier
            .fillMaxWidth()
            .background(c.bg)
            .drawWithContent {
                drawContent()
                drawRect(c.fg, size = Size(size.width, 1.5.dp.toPx()))
            }
            .navigationBarsPadding()
            .height(58.dp)
            .padding(horizontal = Clauderc.spacing.sp3, vertical = 8.dp)
        UiStyle.Sticker -> Modifier
            .navigationBarsPadding()
            .padding(start = Clauderc.spacing.sp4, end = Clauderc.spacing.sp4, bottom = Clauderc.spacing.sp3)
            .fillMaxWidth()
            .height(66.dp)
            .sticker(c.float, c.ink, 33.dp)
            .padding(5.dp)
        UiStyle.Clear -> {
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
            Modifier
                .navigationBarsPadding()
                .padding(start = Clauderc.spacing.sp4, end = Clauderc.spacing.sp4, bottom = Clauderc.spacing.sp3)
                .fillMaxWidth()
                .height(64.dp)
                .shadow(14.dp, shape, ambientColor = c.glassShadow, spotColor = c.glassShadow)
                .clip(shape)
                .then(glass)
                .drawWithContent {
                    drawContent()
                    // Sheen, inner highlight, bright side rims and hairline edge (.navbar::before and its box-shadow).
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
                    val rim = Brush.verticalGradient(0f to Color.Transparent, 0.3f to c.rim, 0.7f to c.rim, 1f to Color.Transparent)
                    drawLine(rim, Offset(0.5f, 0f), Offset(0.5f, size.height), 1.dp.toPx())
                    drawLine(rim, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1.dp.toPx())
                }
                .border(0.5.dp, c.glassEdge, shape)
                .padding(6.dp)
        }
    }

    Box(modifier.then(bar)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cell = maxWidth / tabs.size
            val indicator = Modifier
                .offset(x = cell * left.value)
                .width(cell * (right.value - left.value))
                .fillMaxHeight()
            when (ui) {
                UiStyle.Grid -> Box(indicator.clip(RoundedCornerShape(2.dp)).background(c.fg))
                UiStyle.Sticker -> Box(indicator.sticker(c.hue2, c.ink, 27.dp))
                UiStyle.Clear -> Box(
                    indicator
                        .shadow(6.dp, RoundedCornerShape(26.dp), ambientColor = c.glassShadow, spotColor = c.glassShadow)
                        .clip(RoundedCornerShape(26.dp))
                        .background(c.lens)
                        .topHighlight(c.rim)
                        .border(0.5.dp, c.edge, RoundedCornerShape(26.dp)),
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
    val ui = Clauderc.style
    // The selected look fades in as the indicator arrives, like the mockup's FILL transition.
    val on by animateFloatAsState(if (selected) 1f else 0f, Motion.enter(), label = "navCell")
    val picked = when (ui) {
        UiStyle.Grid -> c.bg
        UiStyle.Sticker -> StickerOnYellow
        UiStyle.Clear -> c.fg
    }
    val tint = lerp(c.fg2, picked, on)
    // 贴纸 pops the picked icon: wide and low, then tall, then back (ci-pop).
    val pop = remember { Animatable(1f) }
    LaunchedEffect(selected, ui) {
        if (selected && ui == UiStyle.Sticker) {
            pop.snapTo(0f)
            pop.animateTo(1f, Motion.iconPop())
        }
    }
    val press = remember { MutableInteractionSource() }
    // 贴纸 keeps a pressed tab where it is and only squashes and tilts its sticker icon.
    val pushed by stickerPushed(press)
    val corner = when (ui) {
        UiStyle.Grid -> 2.dp
        UiStyle.Sticker -> 27.dp
        UiStyle.Clear -> 26.dp
    }
    Box(
        modifier
            .pressShrink(press, StickerPress.Still)
            .fillMaxHeight()
            .clip(RoundedCornerShape(corner))
            .selectable(selected = selected, interactionSource = press, indication = LocalIndication.current, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val iconSize = if (ui == UiStyle.Sticker) 25.dp else if (ui == UiStyle.Grid) 18.dp else 24.dp
        val icon: @Composable () -> Unit = {
            Box(
                Modifier.size(iconSize).graphicsLayer {
                    val p = if (ui == UiStyle.Sticker) pushed else 0f
                    val held = 1f - 0.18f * p
                    popScale(pop.value).let { (x, y, dy) -> scaleX = x * held; scaleY = y * held; translationY = dy * density }
                    rotationZ = -12f * p
                },
            ) {
                Icon(tab.icon.styled, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize().alpha(1f - on))
                Icon(tab.selectedIcon.styled, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize().alpha(on))
            }
        }
        if (ui == UiStyle.Grid) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                icon()
                Text(tab.label, fontSize = 12.sp, fontFamily = MonoFamily, fontWeight = FontWeight.Medium, color = tint)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                icon()
                Text(tab.label, fontSize = 11.sp, fontWeight = if (ui == UiStyle.Sticker) FontWeight.Bold else FontWeight.Medium, color = tint)
            }
        }
        if (tab.dot) {
            val dot = if (ui == UiStyle.Grid) {
                Modifier.align(Alignment.TopEnd).offset(x = (-10).dp, y = 0.dp).size(7.dp).background(c.accent).testTag("navDot")
            } else {
                Modifier.align(Alignment.TopCenter).offset(x = 10.5.dp, y = 3.dp).size(7.dp).clip(CircleShape).background(c.accent).testTag("navDot")
            }
            Box(dot)
        }
    }
}

/**
 * ci-pop at [t] from 0 to 1: scale x, scale y and the upward lift in dp. Keyframes at 30% (1.22,
 * 0.86, down 1) and 60% (0.94, 1.08, up 2), linear between them, as the demo's keyframes.
 */
private fun popScale(t: Float): Triple<Float, Float, Float> {
    fun mix(a: Float, b: Float, f: Float) = a + (b - a) * f
    return when {
        t >= 1f -> Triple(1f, 1f, 0f)
        t < 0.3f -> (t / 0.3f).let { Triple(mix(1f, 1.22f, it), mix(1f, 0.86f, it), mix(0f, 1f, it)) }
        t < 0.6f -> ((t - 0.3f) / 0.3f).let { Triple(mix(1.22f, 0.94f, it), mix(0.86f, 1.08f, it), mix(1f, -2f, it)) }
        else -> ((t - 0.6f) / 0.4f).let { Triple(mix(0.94f, 1f, it), mix(1.08f, 1f, it), mix(-2f, 0f, it)) }
    }
}
