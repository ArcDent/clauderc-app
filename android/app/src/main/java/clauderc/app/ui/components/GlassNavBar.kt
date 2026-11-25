package clauderc.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import clauderc.app.data.PlateNavStyle
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.PressIndication
import clauderc.app.ui.theme.PressKind
import clauderc.app.ui.theme.Symbols
import clauderc.app.ui.theme.pressShrink
import clauderc.app.ui.theme.styled
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import kotlinx.coroutines.launch

data class NavTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector, val dot: Boolean = false)

/**
 * The bottom navigation bar for all four looks (design/ui-mockup/demo.html):
 * - 浮墨: floating dark/light capsule pill + optional right pending/machine orb.
 * - 素卡: centered floating white rounded capsule pill with accent-tinted active bubble.
 * - 清玻: blurred frosted glass floating capsule with bright rims and lens indicator.
 * - 印版: toggleable square floating box with sliding solid-ink indicator block, or full-width docked bar under a 2dp rule.
 */
@Composable
fun GlassNavBar(
    tabs: List<NavTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
    pendingCount: Int = 0,
    onPendingOrb: (() -> Unit)? = null,
    onPendingOrbAt: ((Rect?) -> Unit)? = null,
) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    val plateNav = Clauderc.plateNav

    // Indicator edges, in cells.
    // 浮墨 moves as ONE whole elastic ink blob on --goo; 清玻 stretches with 70ms lead/trail delay.
    val left = remember { Animatable(selected.toFloat()) }
    val right = remember { Animatable(selected + 1f) }
    LaunchedEffect(selected, ui) {
        if (ui == UiStyle.Ink) {
            val spec = tween<Float>(440, easing = Motion.inkGoo)
            launch { left.animateTo(selected.toFloat(), spec) }
            launch { right.animateTo(selected + 1f, spec) }
        } else {
            val toRight = selected > left.value
            launch { left.animateTo(selected.toFloat(), if (toRight) Motion.navTrail() else Motion.navLead()) }
            launch { right.animateTo(selected + 1f, if (toRight) Motion.navLead() else Motion.navTrail()) }
        }
    }

    if (ui == UiStyle.Ink) {
        val orbHandler = onPendingOrbAt ?: onPendingOrb?.let { fn -> { _: Rect? -> fn() } }
        Row(
            modifier
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val pillShape = RoundedCornerShape(32.dp)
            Box(
                Modifier
                    .weight(1f)
                    .height(64.dp)
                    .shadow(if (c.isDark) 0.dp else 10.dp, pillShape, ambientColor = c.scrim, spotColor = c.scrim)
                    .inkSurface(c.float, c.edge, 32.dp)
                    .padding(6.dp),
            ) {
                NavCells(tabs, selected, left.value, right.value, onSelect)
            }
            if (orbHandler != null) {
                val orbPress = remember { MutableInteractionSource() }
                val orbAnchor = rememberAnchorBox()
                val hasPending = pendingCount > 0
                val orbBg by animateColorAsState(if (hasPending) c.accent else c.float, tween(240), label = "orbBg")
                val orbFg by animateColorAsState(if (hasPending) c.onAccent else c.fg, tween(240), label = "orbFg")
                val countFrac by animateFloatAsState(if (hasPending) 1f else 0f, tween(280, easing = Motion.inkGoo), label = "orbMode")
                val pulse = remember { Animatable(0f) }
                val bump = remember { Animatable(1f) }
                var prevCount by remember { mutableIntStateOf(pendingCount) }
                LaunchedEffect(pendingCount) {
                    if (pendingCount > prevCount && prevCount >= 0) {
                        launch {
                            pulse.snapTo(0f)
                            pulse.animateTo(1f, tween(820, easing = Motion.inkEase))
                            pulse.snapTo(0f)
                        }
                        launch {
                            bump.snapTo(1f)
                            bump.animateTo(1.32f, tween(130, easing = Motion.inkEase))
                            bump.animateTo(1f, tween(260, easing = Motion.inkGoo))
                        }
                    }
                    prevCount = pendingCount
                }
                Box(
                    Modifier
                        .captureAnchor(orbAnchor)
                        .pressShrink(orbPress, PressKind.Icon)
                        .size(64.dp)
                        .drawBehind {
                            val p = pulse.value
                            if (p > 0.01f && p < 0.99f) {
                                val expand = 16.dp.toPx() * p
                                val r = size.minDimension / 2f + expand
                                drawCircle(
                                    color = c.accent.copy(alpha = 0.65f * (1f - p)),
                                    radius = r,
                                    style = Stroke(width = (3.dp.toPx() * (1f - p * 0.5f)).coerceAtLeast(1f)),
                                )
                            }
                        }
                        .shadow(if (c.isDark) 0.dp else 10.dp, CircleShape, ambientColor = c.scrim, spotColor = c.scrim)
                        .clip(CircleShape)
                        .background(orbBg)
                        .then(if (hasPending) Modifier else Modifier.border(1.dp, c.edge, CircleShape))
                        .clickable(orbPress, PressIndication(orbFg), role = Role.Button) { orbHandler(orbAnchor.rect) }
                        .semantics { contentDescription = if (hasPending) "$pendingCount 个待确认" else "切换目标机" }
                        .testTag("inkPendingOrb"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Symbols.Computer,
                        contentDescription = null,
                        tint = orbFg,
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer {
                                alpha = (1f - countFrac).coerceIn(0f, 1f)
                                val s = 0.4f + 0.6f * (1f - countFrac).coerceIn(0f, 1f)
                                scaleX = s
                                scaleY = s
                                rotationZ = -20f * countFrac.coerceIn(0f, 1f)
                            },
                    )
                    if (pendingCount > 0 || countFrac > 0.02f) {
                        Text(
                            pendingCount.coerceAtLeast(1).toString(),
                            color = orbFg,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.graphicsLayer {
                                alpha = countFrac.coerceIn(0f, 1f)
                                val s = (0.4f + 0.6f * countFrac).coerceAtLeast(0.4f) * bump.value
                                scaleX = s
                                scaleY = s
                            },
                        )
                    }
                }
            }
        }
        return
    }

    val bar = when (ui) {
        UiStyle.Plate -> if (plateNav == PlateNavStyle.Float) {
            Modifier
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, bottom = 14.dp)
                .fillMaxWidth()
                .height(60.dp)
                .drawBehind {
                    val gap = 3.dp.toPx()
                    val outer = 4.5.dp.toPx()
                    drawRect(c.fg, Offset(outer, outer), size)
                    drawRect(c.bg, Offset(gap, gap), size)
                }
                .plateBox(c.bg, c.fg, 2.dp)
                .padding(4.dp)
        } else {
            Modifier
                .fillMaxWidth()
                .background(c.bg)
                .drawWithContent {
                    drawContent()
                    drawRect(c.fg, size = Size(size.width, 2.dp.toPx()))
                }
                .navigationBarsPadding()
                .height(60.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        }
        UiStyle.Tile -> {
            val shape = RoundedCornerShape(32.dp)
            Modifier
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 14.dp)
                .widthIn(max = 284.dp)
                .fillMaxWidth()
                .height(64.dp)
                .shadow(14.dp, shape, ambientColor = c.scrim, spotColor = c.scrim)
                .clip(shape)
                .background(c.float)
                .border(1.dp, c.line, shape)
                .padding(6.dp)
        }
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
                .widthIn(max = 284.dp)
                .fillMaxWidth()
                .height(62.dp)
                .shadow(14.dp, shape, ambientColor = c.glassShadow, spotColor = c.glassShadow)
                .clip(shape)
                .then(glass)
                .drawWithContent {
                    drawContent()
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
        UiStyle.Ink -> Modifier // handled above
    }

    Box(modifier.then(bar)) {
        NavCells(tabs, selected, left.value, right.value, onSelect)
    }
}

@Composable
private fun NavCells(
    tabs: List<NavTab>,
    selected: Int,
    left: Float,
    right: Float,
    onSelect: (Int) -> Unit,
) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    val plateNav = Clauderc.plateNav
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cell = maxWidth / tabs.size
        val indicator = Modifier
            .offset(x = cell * left)
            .width(cell * (right - left))
            .fillMaxHeight()
        when (ui) {
            UiStyle.Plate -> if (plateNav == PlateNavStyle.Float) {
                Box(indicator.background(c.fg, RectangleShape))
            } else {
                Box(indicator.align(Alignment.BottomStart)) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(c.fg))
                }
            }
            // 素卡 pops its active pill in-place (scale 0.84 -> 1.0 on spring) inside each NavCell (demo.html .tab::before).
            UiStyle.Tile -> Unit
            UiStyle.Ink -> Box(
                indicator
                    .clip(RoundedCornerShape(26.dp))
                    .background(c.surface2)
                    .border(0.5.dp, c.edge, RoundedCornerShape(26.dp)),
            )
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

@Composable
private fun NavCell(tab: NavTab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    val plateNav = Clauderc.plateNav
    val on by animateFloatAsState(if (selected) 1f else 0f, Motion.enter(), label = "navCell")
    val tilePop by animateFloatAsState(
        targetValue = if (ui == UiStyle.Tile && selected) 1f else 0f,
        animationSpec = tween(Clauderc.motion.durM, easing = Clauderc.motion.easeSpring),
        label = "tileTabPop",
    )
    val picked = when (ui) {
        UiStyle.Plate -> if (plateNav == PlateNavStyle.Float) c.bg else c.fg
        UiStyle.Tile -> c.accent
        UiStyle.Ink, UiStyle.Clear -> c.fg
    }
    val tint = lerp(c.fg2, picked, on)
    val press = remember { MutableInteractionSource() }
    val shape = if (ui == UiStyle.Plate) RectangleShape else RoundedCornerShape(26.dp)
    Box(
        modifier
            .pressShrink(press, PressKind.Still)
            .fillMaxHeight()
            .clip(shape)
            .selectable(selected = selected, interactionSource = press, indication = LocalIndication.current, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (ui == UiStyle.Tile && tilePop > 0.01f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val s = 0.84f + 0.16f * tilePop
                        scaleX = s
                        scaleY = s
                        alpha = tilePop.coerceIn(0f, 1f)
                    }
                    .clip(RoundedCornerShape(26.dp))
                    .background(c.accent.copy(alpha = if (c.isDark) 0.22f else 0.14f)),
            )
        }
        val iconSize = if (ui == UiStyle.Plate) 18.dp else 22.dp
        val icon: @Composable () -> Unit = {
            Box(Modifier.size(iconSize)) {
                Icon(tab.icon.styled, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize().alpha(1f - on))
                Icon(tab.selectedIcon.styled, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize().alpha(on))
            }
        }
        if (ui == UiStyle.Plate) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                icon()
                Text(tab.label, fontSize = 12.sp, fontFamily = MonoFamily, fontWeight = FontWeight.ExtraBold, color = tint)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                icon()
                Text(tab.label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = tint)
            }
        }
        if (tab.dot) {
            val dotColor = if (ui == UiStyle.Plate && selected && plateNav == PlateNavStyle.Float) c.bg else c.accent
            val dotMod = if (ui == UiStyle.Plate) {
                Modifier.align(Alignment.TopEnd).offset(x = (-8).dp, y = 4.dp).size(6.dp).background(dotColor).testTag("navDot")
            } else {
                Modifier.align(Alignment.TopCenter).offset(x = 11.dp, y = 4.dp).size(7.dp).clip(CircleShape).background(dotColor).testTag("navDot")
            }
            Box(dotMod)
        }
    }
}
