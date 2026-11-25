package clauderc.app.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.lerp
import clauderc.app.ui.components.PlateSweep
import clauderc.app.ui.components.plateSweepFill
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import clauderc.app.machines.PendingEntry
import clauderc.app.protocol.Machine
import clauderc.app.protocol.SessionList
import clauderc.app.protocol.UsageResponse
import clauderc.app.ui.TimeText
import clauderc.app.ui.components.CardCorner
import clauderc.app.ui.components.CookieShape
import clauderc.app.ui.components.buildCookiePath
import clauderc.app.ui.components.captureAnchor
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import clauderc.app.ui.components.inkSurface
import clauderc.app.ui.components.plateBox
import clauderc.app.ui.components.plateHatchedBar
import clauderc.app.ui.components.rememberAnchorBox
import clauderc.app.ui.components.tileSurface
import clauderc.app.ui.components.glassPane
import dev.chrisbanes.haze.HazeState
import clauderc.app.ui.needsYou
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.PressIndication
import clauderc.app.ui.theme.PressKind
import clauderc.app.ui.theme.Symbols
import clauderc.app.ui.theme.pressShrink
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Right action slot of 浮墨's shared floating top bar (demo.html `drawTop`). */
enum class InkTopRight { Add, Refresh, None }

/**
 * 浮墨's floating top bar (demo.html `.top`):
 * subtle top-down gradient fade so scrolled text glides cleanly beneath the 3 floating controls:
 * left asymmetric 3-bar `.burger` orb, centered 40dp status/dir pill, and right `+` / refresh orb.
 */
@Composable
fun InkTopBar(
    title: String,
    online: Boolean,
    showDot: Boolean,
    rightAction: InkTopRight,
    rightEnabled: Boolean = true,
    onOpenDrawer: () -> Unit,
    onPillClick: (Rect?) -> Unit,
    onRightClick: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
    rightDesc: String? = null,
) {
    val c = Clauderc.colors
    val pillAnchor = rememberAnchorBox()
    val rightAnchor = rememberAnchorBox()
    val spin = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Row(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to c.bg,
                    0.65f to c.bg,
                    1f to Color.Transparent,
                ),
            )
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val drawerPress = remember { MutableInteractionSource() }
        Box(
            Modifier
                .pressShrink(drawerPress, PressKind.Icon)
                .size(48.dp)
                .inkSurface(c.float, c.edge, 24.dp)
                .clickable(drawerPress, PressIndication(c.fg), role = Role.Button, onClick = onOpenDrawer)
                .semantics { contentDescription = "打开目录抽屉" }
                .testTag("inkDrawerButton"),
            contentAlignment = Alignment.Center,
        ) {
            // Asymmetric 3-bar burger matching demo.html `.burger` (20 / 15 / 10 dp).
            Canvas(Modifier.size(20.dp, 14.dp)) {
                val h = 1.6.dp.toPx()
                val gap = 5.dp.toPx()
                val r = CornerRadius(h / 2f, h / 2f)
                val widths = floatArrayOf(20.dp.toPx(), 15.dp.toPx(), 10.dp.toPx())
                widths.forEachIndexed { idx, w ->
                    val y = idx * (h + gap)
                    drawRoundRect(c.fg, Offset(0f, y), Size(w, h), r)
                }
            }
        }

        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            val pillPress = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .captureAnchor(pillAnchor)
                    .widthIn(max = 244.dp)
                    .height(40.dp)
                    .pressShrink(pillPress, PressKind.Plain)
                    .inkSurface(c.float, c.edge, 20.dp)
                    .clickable(pillPress, PressIndication(c.fg), role = Role.Button) { onPillClick(pillAnchor.rect) }
                    .padding(start = 14.dp, end = 10.dp)
                    .testTag("inkMachinePill"),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showDot) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (online) c.ok else c.fg3))
                }
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = c.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Symbols.ExpandMore, contentDescription = null, tint = c.fg2, modifier = Modifier.size(18.dp))
            }
        }

        when (rightAction) {
            InkTopRight.None -> Spacer(Modifier.size(48.dp))
            InkTopRight.Add, InkTopRight.Refresh -> {
                val addPress = remember { MutableInteractionSource() }
                val desc = rightDesc ?: if (rightAction == InkTopRight.Add) "新建会话" else "刷新"
                val icon = if (rightAction == InkTopRight.Add) Symbols.Add else Symbols.Refresh
                Box(
                    Modifier
                        .captureAnchor(rightAnchor)
                        .pressShrink(addPress, PressKind.Icon)
                        .size(48.dp)
                        .inkSurface(c.float, c.edge, 24.dp)
                        .clickable(addPress, PressIndication(c.fg), enabled = rightEnabled, role = Role.Button) {
                            if (rightAction == InkTopRight.Refresh) {
                                scope.launch {
                                    spin.snapTo(0f)
                                    spin.animateTo(360f, tween(520, easing = Motion.inkEase))
                                    spin.snapTo(0f)
                                }
                            }
                            onRightClick(rightAnchor.rect)
                        }
                        .semantics { contentDescription = desc }
                        .testTag(if (rightAction == InkTopRight.Add) "inkAddButton" else "inkRefreshButton"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (rightEnabled) c.fg else c.fg3,
                        modifier = Modifier
                            .size(21.dp)
                            .graphicsLayer { rotationZ = spin.value },
                    )
                }
            }
        }
    }
}

/**
 * 浮墨's signature push-aside drawer layout (demo.html `.phone.drawer-open .app`):
 * The drawer lives UNDERNEATH the main app. Opening shrinks the entire app (including the floating
 * nav bar) to 0.84x with 40dp corners and shifts it 292dp to the right; tapping the shrunk card
 * springs it smoothly back.
 */
@Composable
fun InkPushDrawerHost(
    open: Boolean,
    machine: Machine?,
    dirs: List<DirGroup>,
    totalSessions: Int,
    selectedDir: String?,
    onSelectDir: (String?) -> Unit,
    onScanPairAt: (Rect?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val c = Clauderc.colors
    val density = LocalDensity.current
    val p by animateFloatAsState(if (open) 1f else 0f, tween(340, easing = Motion.inkEase), label = "inkDrawer")
    val scanAnchor = rememberAnchorBox()
    BackHandler(enabled = open, onBack = onClose)

    Box(modifier.fillMaxSize().background(c.bg)) {
        // Layer 1 (underneath): the directory drawer with bottom-left 56dp QR scan orb.
        if (open || p > 0.01f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = p
                        translationX = with(density) { (-24).dp.toPx() } * (1f - p)
                    }
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .testTag("inkDrawer"),
            ) {
                Column(
                    Modifier
                        .width(286.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 24.dp, bottom = 104.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Brand header with 30dp orange dr-mark + tilted dash.
                    Row(
                        Modifier.padding(start = 26.dp, end = 20.dp, bottom = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Canvas(Modifier.size(30.dp)) {
                            drawCircle(c.accent)
                            rotate(-8f) {
                                val bw = size.width - 14.dp.toPx()
                                val bh = 4.dp.toPx()
                                drawRoundRect(
                                    color = c.bg,
                                    topLeft = Offset((size.width - bw) / 2f, (size.height - bh) / 2f),
                                    size = Size(bw, bh),
                                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                                )
                            }
                        }
                        Text("clauderc", fontSize = 25.sp, fontWeight = FontWeight.Medium, color = c.fg)
                    }
                    HorizontalDivider(Modifier.padding(start = 24.dp, bottom = 12.dp).width(248.dp), thickness = 1.dp, color = c.edge)
                    DrawerRow(
                        icon = Symbols.Forum,
                        title = "全部目录",
                        subtitle = "${machine?.name ?: "未配对"} · $totalSessions 个会话",
                        selected = selectedDir == null,
                        onClick = { onSelectDir(null); onClose() },
                    )
                    if (dirs.isNotEmpty()) {
                        Text(
                            "目录",
                            modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 6.dp),
                            fontSize = 12.sp,
                            color = c.fg3,
                        )
                        dirs.forEach { d ->
                            val sub = buildString {
                                append("${d.sessions.size} 个会话")
                                d.currentBranch?.let { append(" · $it") }
                            }
                            DrawerRow(
                                icon = Symbols.Folder,
                                title = d.title,
                                subtitle = sub,
                                selected = selectedDir == d.cwd,
                                onClick = { onSelectDir(d.cwd); onClose() },
                            )
                        }
                    }
                }
                val scanPress = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 24.dp, bottom = 24.dp)
                        .captureAnchor(scanAnchor)
                        .pressShrink(scanPress, PressKind.Icon)
                        .size(56.dp)
                        .shadow(if (c.isDark) 0.dp else 10.dp, CircleShape, ambientColor = c.scrim, spotColor = c.scrim)
                        .inkSurface(c.float, c.edge, 28.dp)
                        .clickable(scanPress, PressIndication(c.fg), role = Role.Button) { onScanPairAt(scanAnchor.rect) }
                        .semantics { contentDescription = "扫码配对" }
                        .testTag("inkDrawerScanOrb"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Symbols.QrCodeScanner, contentDescription = null, tint = c.fg, modifier = Modifier.size(23.dp))
                }
            }
        }

        // Layer 2 (on top): the entire app shrinking & pushing right into a 40dp rounded card.
        val corner = (40f * p).dp
        val shape = RoundedCornerShape(corner)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    translationX = with(density) { 290.dp.toPx() } * p
                    val s = lerp(1f, 0.84f, p)
                    scaleX = s
                    scaleY = s
                    this.shape = shape
                    clip = p > 0.001f
                    shadowElevation = with(density) { (28f * p).dp.toPx() }
                }
                .then(if (p > 0.01f) Modifier.border(1.dp, c.edge, shape) else Modifier)
                .background(c.bg),
        ) {
            content()
            if (open) {
                // Tapping the shrunk right-hand card closes the drawer without triggering app content.
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose)
                        .testTag("inkDrawerCloseTap"),
                )
            }
        }
    }
}

@Composable
private fun DrawerRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = Clauderc.colors
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .width(268.dp)
            .padding(start = 14.dp, top = 2.dp, bottom = 2.dp)
            .clip(shape)
            .background(if (selected) c.card else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, c.edge, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) c.accent else c.fg2, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 13.sp, color = c.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 浮墨's signature oversized two-tab switcher with a hand-painted ink brush stroke gliding beneath
 * the active label on `--goo` spring (demo.html `.brush-tabs`, shared by Sessions 活跃/全部 and Git 时间线/改动).
 */
@Composable
fun InkBrushTabs(
    labels: List<Pair<String, String?>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Clauderc.colors
    val density = LocalDensity.current
    val slots = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val active = slots[selected]
    val padPx = with(density) { 8.dp.toPx() }
    val targetLeft = active?.let { it.first - padPx } ?: 0f
    val targetWidth = active?.let { it.second + padPx * 2f } ?: 0f
    val brushLeft by animateFloatAsState(targetLeft, tween(300, easing = Motion.inkGoo), label = "brushL")
    val brushWidth by animateFloatAsState(targetWidth, tween(300, easing = Motion.inkGoo), label = "brushW")

    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp)
            .drawBehind {
                if (brushWidth > 1f) {
                    // Exact hand-painted SVG brush path from demo.html (viewBox 0 0 100 30) mapped to active label.
                    val bx = brushLeft
                    val by = with(density) { 14.dp.toPx() }
                    val bw = brushWidth
                    val bh = with(density) { 26.dp.toPx() }
                    fun sx(x: Float) = bx + (x / 100f) * bw
                    fun sy(y: Float) = by + (y / 30f) * bh
                    val path = Path().apply {
                        moveTo(sx(3f), sy(13f))
                        cubicTo(sx(14f), sy(6f), sx(38f), sy(9f), sx(58f), sy(7f))
                        cubicTo(sx(78f), sy(5f), sx(90f), sy(5f), sx(97f), sy(10f))
                        cubicTo(sx(100f), sy(15f), sx(97f), sy(22f), sx(88f), sy(24f))
                        cubicTo(sx(70f), sy(26f), sx(46f), sy(23f), sx(27f), sy(26f))
                        cubicTo(sx(14f), sy(28f), sx(5f), sy(25f), sx(2f), sy(20f))
                        close()
                    }
                    drawPath(path, c.surface2)
                }
            }
            .testTag("inkBrushTabs"),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            labels.forEachIndexed { idx, (text, badge) ->
                val on = idx == selected
                Row(
                    Modifier
                        .onGloballyPositioned { coords ->
                            slots[idx] = coords.positionInParent().x to coords.size.width.toFloat()
                        }
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(idx) }
                        .padding(vertical = 2.dp)
                        .testTag("brushTab:$idx"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (on) c.fg else c.fg3,
                        lineHeight = 36.sp,
                    )
                    if (!badge.isNullOrEmpty()) {
                        Text(
                            badge,
                            modifier = Modifier.padding(top = 4.dp),
                            fontSize = 15.sp,
                            color = c.fg3,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 素卡's inline horizontal machine strip (shown when more than one machine is paired) + Bento hero cards
 * (left scalloped cookie pending card + right usage/summary card).
 */
@Composable
internal fun TileSessionsBento(
    machine: Machine,
    machines: List<Machine>,
    allSessions: Map<String, SessionList>,
    pendingEntries: List<PendingEntry>,
    workingCount: Int,
    totalSessions: Int,
    usage: UsageResponse?,
    onSelectMachine: (Machine) -> Unit,
    onOpenPending: (PendingEntry) -> Unit,
    onCreate: () -> Unit,
    onPair: () -> Unit,
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    Column(verticalArrangement = Arrangement.spacedBy(sp.sp3)) {
        if (machines.size > 1) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().testTag("tileMachineStrip"),
                contentPadding = PaddingValues(horizontal = sp.sp4),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(machines, key = { it.id }) { m ->
                    val selected = m.id == machine.id
                    val list = allSessions[m.id]?.sessions
                    val wait = list?.count(::needsYou) ?: 0
                    val sub = when {
                        !m.online -> "离线"
                        wait > 0 -> "$wait 个等你"
                        list != null -> "${list.size} 个会话"
                        else -> "在线"
                    }
                    val press = remember { MutableInteractionSource() }
                    // Selected check circle springs in 0.4 -> 1.0 (demo.html .mchip.on .chk).
                    val chkScale by animateFloatAsState(
                        targetValue = if (selected) 1f else 0f,
                        animationSpec = tween(Clauderc.motion.durM, easing = Clauderc.motion.easeSpring),
                        label = "mchipChk:${m.id}",
                    )
                    Row(
                        Modifier
                            .pressShrink(press, PressKind.Button)
                            .tileSurface(c.card, 18.dp)
                            .clickable(press, LocalIndication.current, role = Role.Button) { onSelectMachine(m) }
                            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(m.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (m.online) c.fg else c.fg2, maxLines = 1)
                            Text(sub, fontSize = 12.sp, color = if (wait > 0) c.accent else c.fg2, maxLines = 1)
                        }
                        Box(
                            Modifier
                                .size(22.dp)
                                .graphicsLayer {
                                    val s = 0.4f + 0.6f * chkScale
                                    scaleX = s
                                    scaleY = s
                                    alpha = chkScale.coerceIn(0f, 1f)
                                }
                                .clip(CircleShape)
                                .background(c.accent),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected || chkScale > 0.05f) {
                                Icon(Symbols.Check, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
                item(key = "pair") {
                    val press = remember { MutableInteractionSource() }
                    Row(
                        Modifier
                            .pressShrink(press, PressKind.Button)
                            .tileSurface(c.surface, 18.dp)
                            .clickable(press, LocalIndication.current, role = Role.Button, onClick = onPair)
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Symbols.Add, contentDescription = null, tint = c.fg2, modifier = Modifier.size(16.dp))
                        Text("配对", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = c.fg2)
                    }
                }
            }
        }

        // Bento two-column hero row
        val first = pendingEntries.firstOrNull()
        val pendingCount = pendingEntries.size
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = sp.sp4)
                .testTag("tileBento"),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val leftPress = remember { MutableInteractionSource() }
            val leftPressed by leftPress.collectIsPressedAsState()
            // Interactive scalloped cookie morph: holding flattens 8 lobes into a smooth circle with gentle spin,
            // releasing springs back into the scalloped cookie (demo.html wireCookie).
            val cookieAmp = remember { Animatable(0.085f) }
            val cookieRot = remember { Animatable(0f) }
            val springEase = Clauderc.motion.easeSpring
            LaunchedEffect(leftPressed) {
                if (leftPressed) {
                    launch { cookieAmp.animateTo(0f, tween(180, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f))) }
                    launch { cookieRot.animateTo(cookieRot.value + 0.38f, tween(180, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f))) }
                } else {
                    cookieAmp.animateTo(0.085f, tween(520, easing = springEase))
                }
            }
            val cookieIconScale by animateFloatAsState(
                targetValue = if (leftPressed) 0.90f else 1f,
                animationSpec = tween(260, easing = springEase),
                label = "cookieIconScale",
            )
            Box(
                Modifier
                    .weight(1.12f)
                    .height(142.dp)
                    .pressShrink(leftPress, PressKind.Card)
                    .tileSurface(c.card, CardCorner)
                    .clickable(leftPress, LocalIndication.current, role = Role.Button) {
                        if (first != null) onOpenPending(first) else onCreate()
                    }
                    .padding(14.dp)
                    .testTag("tilePendingCard"),
            ) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (pendingCount > 0) "待确认" else "工作中",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (pendingCount > 0) c.accent else c.fg2,
                    )
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            (if (pendingCount > 0) pendingCount else workingCount).toString(),
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            color = c.fg,
                            lineHeight = 36.sp,
                        )
                        Text(
                            if (pendingCount > 0) "个等你" else "个在跑",
                            modifier = Modifier.padding(bottom = 4.dp),
                            fontSize = 12.5.sp,
                            color = c.fg2,
                        )
                    }
                    Text(
                        first?.let { display(it.session) } ?: "没有等你处理的会话",
                        fontSize = 12.5.sp,
                        color = c.fg2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val outerFill = if (pendingCount > 0) c.accent.copy(alpha = if (c.isDark) 0.24f else 0.15f) else c.surface
                val innerFill = if (pendingCount > 0) c.accent else c.surface2
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(62.dp)
                        .testTag("tileCookieButton"),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val outerR = minOf(cx, cy) * 0.94f
                        drawPath(buildCookiePath(cx, cy, outerR, ampFrac = 0.06f, lobes = 5, rotRad = 0.3f), outerFill)
                        val amp = cookieAmp.value.coerceIn(0f, 0.09f)
                        val shrink = (0.085f - amp) * 1.4f
                        val innerR = minOf(cx, cy) * (0.72f - shrink)
                        drawPath(buildCookiePath(cx, cy, innerR, ampFrac = amp, lobes = 8, rotRad = cookieRot.value), innerFill)
                    }
                    Icon(
                        if (pendingCount > 0) Symbols.ChevronRight else Symbols.Add,
                        contentDescription = if (pendingCount > 0) "下一个待确认" else "新建会话",
                        tint = if (pendingCount > 0) c.onAccent else c.fg,
                        modifier = Modifier.size(24.dp).graphicsLayer {
                            scaleX = cookieIconScale
                            scaleY = cookieIconScale
                        },
                    )
                }
            }

            // Right Bento card: compact usage bars if available, else fleet summary.
            Column(
                Modifier
                    .weight(1f)
                    .height(142.dp)
                    .tileSurface(c.card, CardCorner)
                    .padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                if (usage != null && usage.available) {
                    Text("用量", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = c.fg2)
                    val rows = listOfNotNull(usage.fiveHour?.let { "5 小时" to it }, usage.sevenDay?.let { "每周" to it })
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        rows.forEach { (label, w) ->
                            val pct = w.utilization.roundToInt().coerceIn(0, 100)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                                    Text("$pct%", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.fg)
                                    Text(label, fontSize = 11.5.sp, color = c.fg3)
                                }
                                Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(c.surface2)) {
                                    Box(Modifier.fillMaxWidth(pct / 100f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(if (usage.stale != null) c.fg3 else c.accent))
                                }
                            }
                        }
                    }
                } else {
                    Text("会话概览", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = c.fg2)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(totalSessions.toString(), fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = c.fg, lineHeight = 36.sp)
                        Text("个会话", modifier = Modifier.padding(bottom = 4.dp), fontSize = 12.sp, color = c.fg2)
                    }
                    val onlineCount = machines.count { it.online }.coerceAtLeast(if (machine.online) 1 else 0)
                    Text(
                        "$onlineCount/${machines.size.coerceAtLeast(1)} 台目标机在线",
                        fontSize = 12.sp,
                        color = c.fg2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 印版's editorial letterpress masthead (demo.html .mast): corner crop marks, box wordmark machine
 * switcher, giant mono pending counter with direct jump, and framed diagonal-hatched quota bars.
 */
@Composable
internal fun PlateMasthead(
    machine: Machine,
    pendingEntries: List<PendingEntry>,
    workingCount: Int,
    usage: UsageResponse?,
    onOpenMachines: () -> Unit,
    onOpenPending: (PendingEntry) -> Unit,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val online = machine.online
    val first = pendingEntries.firstOrNull()
    val pendingCount = pendingEntries.size
    val shortMark = machine.name.filter { it.isLetterOrDigit() }.take(2).uppercase().ifEmpty { "RC" }

    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = sp.sp4, vertical = sp.sp2)
            .drawBehind {
                val stroke = 2.dp.toPx()
                val tick = 9.dp.toPx()
                // Top and bottom editorial rules.
                drawLine(c.fg, Offset(0f, stroke / 2f), Offset(size.width, stroke / 2f), stroke)
                drawLine(c.fg, Offset(0f, size.height - stroke / 2f), Offset(size.width, size.height - stroke / 2f), stroke)
                // Four crisp corner crop ticks.
                val w = 1.5.dp.toPx()
                drawLine(c.fg, Offset(0f, 0f), Offset(0f, tick), w)
                drawLine(c.fg, Offset(size.width, 0f), Offset(size.width, tick), w)
                drawLine(c.fg, Offset(0f, size.height - tick), Offset(0f, size.height), w)
                drawLine(c.fg, Offset(size.width, size.height - tick), Offset(size.width, size.height), w)
            }
            .padding(vertical = 12.dp, horizontal = 4.dp)
            .testTag("plateMasthead"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Row 1: Box wordmark machine switcher + refresh + square add button
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val wmPress = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .weight(1f)
                    .pressShrink(wmPress, PressKind.Plain)
                    .clickable(wmPress, LocalIndication.current, role = Role.Button, onClick = onOpenMachines)
                    .padding(vertical = 2.dp)
                    .testTag("plateMachineTrigger"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .background(c.fg, RectangleShape)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(shortMark, fontSize = 11.sp, fontFamily = MonoFamily, fontWeight = FontWeight.ExtraBold, color = c.bg)
                }
                Text(
                    machine.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Symbols.ExpandMore, contentDescription = null, tint = c.fg2, modifier = Modifier.size(16.dp))
            }

            val refPress = remember { MutableInteractionSource() }
            val refSpin = remember { Animatable(0f) }
            val mastScope = rememberCoroutineScope()
            Box(
                Modifier
                    .pressShrink(refPress, PressKind.Icon)
                    .size(34.dp)
                    .plateBox(c.bg, c.fg, 1.5.dp)
                    .clickable(refPress, PressIndication(c.fg), role = Role.Button) {
                        mastScope.launch {
                            refSpin.snapTo(0f)
                            refSpin.animateTo(360f, tween(520, easing = Motion.plateEasePress))
                            refSpin.snapTo(0f)
                        }
                        onRefresh()
                    }
                    .semantics { contentDescription = "刷新" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Symbols.Refresh,
                    contentDescription = null,
                    tint = c.fg,
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = refSpin.value },
                )
            }

            // Differentiated button A: bottom-to-top ink sweep + 90° + rotation on press (demo.html .mast .plus).
            val addPress = remember { MutableInteractionSource() }
            val addPressed by addPress.collectIsPressedAsState()
            val addSweep by animateFloatAsState(
                targetValue = if (online && addPressed) 1f else 0f,
                animationSpec = tween(if (addPressed) 220 else 160, easing = Motion.plateEasePress),
                label = "plateMastAddSweep",
            )
            val addRot by animateFloatAsState(
                targetValue = if (online && addPressed) 90f else 0f,
                animationSpec = tween(if (addPressed) 220 else 180, easing = Motion.plateEasePress),
                label = "plateMastAddRot",
            )
            val addTint = if (online) lerp(c.fg, c.bg, addSweep) else c.fg3
            Box(
                Modifier
                    .pressShrink(addPress, PressKind.Plain)
                    .size(34.dp)
                    .background(if (online) c.bg else c.surface, RectangleShape)
                    .plateSweepFill(addSweep, c.fg, PlateSweep.BottomToTop)
                    .border(1.5.dp, if (online) c.fg else c.fg3, RectangleShape)
                    .clickable(addPress, indication = null, enabled = online, role = Role.Button, onClick = onCreate)
                    .semantics { contentDescription = "新建会话" }
                    .testTag("plateMastAdd"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Symbols.Add,
                    contentDescription = null,
                    tint = addTint,
                    modifier = Modifier.size(19.dp).graphicsLayer { rotationZ = addRot },
                )
            }
        }

        HorizontalDivider(thickness = 1.dp, color = c.line)

        // Row 2: Giant mono numeral pending headline with forward-nudging arrow on press
        val headPress = remember { MutableInteractionSource() }
        val headPressed by headPress.collectIsPressedAsState()
        val headArrowShift by animateDpAsState(
            targetValue = if (headPressed) 6.dp else 0.dp,
            animationSpec = tween(160, easing = Motion.plateEasePress),
            label = "plateHeadArrowShift",
        )
        Row(
            Modifier
                .fillMaxWidth()
                .pressShrink(headPress, PressKind.Card)
                .clickable(headPress, LocalIndication.current, role = Role.Button) {
                    if (first != null) onOpenPending(first) else onCreate()
                }
                .padding(vertical = 2.dp)
                .testTag("platePendingHeadline"),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "%02d".format(pendingCount),
                    fontSize = 42.sp,
                    fontFamily = MonoFamily,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (pendingCount > 0) c.fg else c.fg3,
                    lineHeight = 42.sp,
                )
                Text(
                    "待确认",
                    modifier = Modifier.padding(top = 4.dp),
                    fontSize = 11.sp,
                    fontFamily = MonoFamily,
                    fontWeight = FontWeight.Bold,
                    color = c.fg2,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("下一个", fontSize = 10.5.sp, fontFamily = MonoFamily, color = c.fg3)
                Text(
                    first?.let { display(it.session) } ?: "没有等你的",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        first?.machineName ?: if (workingCount > 0) "$workingCount 个在跑" else "点此新建会话",
                        fontSize = 11.5.sp,
                        fontFamily = MonoFamily,
                        color = c.fg2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (first != null || workingCount == 0) {
                        Text(
                            "→",
                            fontSize = 11.5.sp,
                            fontFamily = MonoFamily,
                            fontWeight = FontWeight.Bold,
                            color = c.fg,
                            modifier = Modifier.offset(x = headArrowShift),
                        )
                    }
                }
            }
        }

        // Row 3: Framed diagonal-hatched quota bars inside masthead when available
        if (usage != null && usage.available) {
            HorizontalDivider(thickness = 1.dp, color = c.line)
            val rows = listOfNotNull(usage.fiveHour?.let { "5 小时" to it }, usage.sevenDay?.let { "每周" to it })
            val stale = usage.stale != null
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { (label, w) ->
                    val pct = w.utilization.roundToInt().coerceIn(0, 100)
                    val reset = w.resetsAt?.let { TimeText.resets(it) }.orEmpty()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, fontSize = 11.5.sp, fontFamily = MonoFamily, color = c.fg2)
                            Text(
                                if (reset.isNotEmpty()) "$pct% · $reset" else "$pct%",
                                fontSize = 11.5.sp,
                                fontFamily = MonoFamily,
                                fontWeight = FontWeight.Bold,
                                color = if (stale) c.fg3 else c.fg,
                            )
                        }
                        Box(Modifier.fillMaxWidth().height(11.dp).plateHatchedBar(pct / 100f, if (stale) c.fg3 else c.fg, c.bg))
                    }
                }
            }
        }
    }
}

/**
 * 清玻's scroll-triggered full-width frosted glass header bar (demo.html `.gbar`):
 * fades in cleanly once the page scrolls past 90dp, blurring content as it glides underneath.
 */
@Composable
fun ClearGlassTopBar(
    visible: Boolean,
    title: String,
    haze: HazeState?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Clauderc.colors
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = Motion.enter(),
        label = "clearGlassBarAlpha",
    )
    if (alpha <= 0.01f) return
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .glassPane(haze, rimTop = false, edgeBottom = true)
            .statusBarsPadding()
            .height(48.dp)
            .padding(start = 24.dp, end = 12.dp)
            .testTag("clearGlassBar"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = c.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
    }
}

/**
 * 清玻's flat uncarded 2-column hairline usage grid (demo.html `.usage` / `.bar`):
 * 22sp tabular numerals + 3dp hairline tracks sitting directly on the pure white/black paper.
 */
@Composable
internal fun ClearUsageGrid(
    usage: UsageResponse,
    modifier: Modifier = Modifier,
) {
    val c = Clauderc.colors
    val rows = listOfNotNull(
        usage.fiveHour?.let { Triple("5 小时", it.utilization, it.resetsAt?.let { t -> TimeText.resets(t) }.orEmpty()) },
        usage.sevenDay?.let { Triple("每周", it.utilization, it.resetsAt?.let { t -> TimeText.resets(t) }.orEmpty()) },
    )
    if (rows.isEmpty()) return
    val stale = usage.stale != null
    val fill = if (stale) c.fg3 else c.fg
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 6.dp)
            .testTag("clearUsageGrid"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            rows.forEach { (label, util, reset) ->
                val pct = util.roundToInt().coerceIn(0, 100)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            "$pct%",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Normal,
                            color = if (stale) c.fg3 else c.fg,
                            lineHeight = 24.sp,
                        )
                        Text(
                            label,
                            modifier = Modifier.padding(bottom = 2.dp),
                            fontSize = 13.sp,
                            color = c.fg2,
                            maxLines = 1,
                        )
                        if (reset.isNotEmpty()) {
                            Spacer(Modifier.weight(1f))
                            Text(
                                reset,
                                modifier = Modifier.padding(bottom = 2.dp),
                                fontSize = 11.5.sp,
                                color = c.fg3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.surface2)) {
                        Box(Modifier.fillMaxWidth(pct / 100f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(fill))
                    }
                }
            }
        }
        val note = when (usage.stale) {
            null -> null
            clauderc.app.protocol.UsageStale.Expired -> usage.fetchedAt?.let { "登录凭证已过期，显示的是 ${TimeText.clock(it)} 的数据" } ?: "登录凭证已过期"
            clauderc.app.protocol.UsageStale.Failed -> usage.fetchedAt?.let { "用量读取失败，显示的是 ${TimeText.clock(it)} 的数据" } ?: "用量读取失败"
        }
        if (note != null) {
            Text(note, fontSize = Clauderc.fontSizes.caption, color = c.fg3)
        }
    }
}

/**
 * 素卡's scroll-triggered compact header bar (demo.html `.minibar`):
 * fades in smoothly once the page scrolls past 70dp, pinning the title and quick action above the list.
 */
@Composable
fun TileMiniBar(
    visible: Boolean,
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Clauderc.colors
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = Motion.fade(),
        label = "tileMiniBarAlpha",
    )
    if (alpha <= 0.01f) return
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .background(c.bg)
            .drawBehind {
                val h = 1.dp.toPx()
                drawRect(c.line, Offset(0f, size.height - h), Size(size.width, h))
            }
            .statusBarsPadding()
            .height(52.dp)
            .padding(start = 20.dp, end = 12.dp)
            .testTag("tileMiniBar"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
    }
}
