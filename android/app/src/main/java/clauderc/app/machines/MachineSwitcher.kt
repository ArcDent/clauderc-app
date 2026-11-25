package clauderc.app.machines

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import clauderc.app.ui.components.plateStamp
import clauderc.app.ui.theme.Motion
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import clauderc.app.data.UiStyle
import clauderc.app.net.ConnectionState
import clauderc.app.net.RelayLink
import clauderc.app.protocol.Machine
import clauderc.app.protocol.Session
import clauderc.app.protocol.SessionList
import clauderc.app.protocol.UsageResponse
import clauderc.app.sessions.display
import clauderc.app.ui.TimeText
import clauderc.app.ui.components.ClaudercSheet
import clauderc.app.ui.components.CookieShape
import clauderc.app.ui.components.Glyph
import clauderc.app.ui.components.LocalMorphClose
import clauderc.app.ui.components.MenuItem
import clauderc.app.ui.components.MorphOverlay
import clauderc.app.ui.components.SheetHead
import clauderc.app.ui.components.Spinner
import clauderc.app.ui.components.StatusGlyph
import clauderc.app.ui.components.topHighlight
import kotlin.math.roundToInt
import clauderc.app.ui.needsYou
import clauderc.app.ui.sessionStateText
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.PressIndication
import clauderc.app.ui.theme.PressKind
import clauderc.app.ui.theme.Symbols
import clauderc.app.ui.theme.pressShrink
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/** A session on an online machine currently waiting for the user's permission, answer or reply. */
data class PendingEntry(
    val machineId: String,
    val machineName: String,
    val session: Session,
)

/**
 * All sessions waiting for the user across currently online machines.
 * Sessions on [preferredMachineId] come first, then other online machines, newest active first.
 */
fun collectPendingOnline(
    machines: List<Machine>?,
    sessions: Map<String, SessionList>,
    preferredMachineId: String? = null,
): List<PendingEntry> {
    if (machines.isNullOrEmpty()) return emptyList()
    val online = machines.filter { it.online }.associateBy { it.id }
    return sessions.entries.flatMap { (id, list) ->
        val m = online[id] ?: return@flatMap emptyList()
        list.sessions.filter(::needsYou).map { s -> PendingEntry(m.id, m.name, s) }
    }.sortedWith(
        compareByDescending<PendingEntry> { it.machineId == preferredMachineId }
            .thenByDescending { it.session.lastActiveAt },
    )
}

/**
 * 清玻's global floating frosted glass pending strip (demo.html .pstrip): sits right above the glass
 * bottom nav bar on all three tabs whenever any online machine has a session waiting for confirmation.
 */
@Composable
fun ClearPendingStrip(
    entries: List<PendingEntry>,
    currentMachineId: String?,
    haze: HazeState?,
    onOpenNext: (PendingEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = entries.firstOrNull() ?: return
    val c = Clauderc.colors
    val shape = RoundedCornerShape(23.dp)
    val press = remember { MutableInteractionSource() }
    val rise = remember { Animatable(0f) }
    val dotPulse = remember { Animatable(0f) }
    var lastCount by remember { mutableIntStateOf(entries.size) }
    val motion = Clauderc.motion
    LaunchedEffect(Unit) {
        rise.animateTo(1f, tween(motion.durL, easing = motion.easeSpring))
    }
    LaunchedEffect(entries.size) {
        if (entries.size > lastCount) {
            dotPulse.snapTo(0f)
            dotPulse.animateTo(1f, tween(900, easing = Motion.inkEase))
            dotPulse.snapTo(0f)
        }
        lastCount = entries.size
    }
    val glass = if (haze != null) {
        Modifier.hazeBlur(
            HazeInput.Sources(haze),
            HazeBlurStyle {
                blurRadius(22.dp)
                noiseFactor(0f)
                backgroundColor(c.bg)
                colorEffects(
                    listOf(
                        HazeColorEffect.colorFilter(ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.8f) })),
                        HazeColorEffect.tint(c.pane),
                    ),
                )
                fallbackColorEffect(HazeColorEffect.tint(c.pane.copy(alpha = 0.94f)))
            },
        )
    } else {
        Modifier.background(c.pane.copy(alpha = 0.94f))
    }
    Row(
        modifier
            .padding(horizontal = 24.dp)
            .widthIn(max = 312.dp)
            .fillMaxWidth()
            .graphicsLayer {
                val p = rise.value
                transformOrigin = TransformOrigin(0.5f, 1f)
                translationY = (1f - p) * 48.dp.toPx()
                scaleX = 0.86f + 0.14f * p
                scaleY = 0.30f + 0.70f * p
                alpha = p.coerceIn(0f, 1f)
            }
            .pressShrink(press, PressKind.Card)
            .shadow(10.dp, shape, ambientColor = c.glassShadow, spotColor = c.glassShadow)
            .clip(shape)
            .then(glass)
            .topHighlight(c.rim)
            .border(0.5.dp, c.edge, shape)
            .clickable(press, PressIndication(c.fg), role = Role.Button, onClickLabel = "前往下一个待确认") { onOpenNext(first) }
            .padding(start = 18.dp, end = 12.dp, top = 11.dp, bottom = 11.dp)
            .testTag("clearPendingStrip"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.drawBehind {
                val p = dotPulse.value
                if (p > 0.01f && p < 0.99f) {
                    drawCircle(
                        color = c.accent.copy(alpha = (1f - p) * 0.7f),
                        radius = size.minDimension / 2f + 9.dp.toPx() * p,
                    )
                }
            },
        ) {
            StatusGlyph(Glyph.Need)
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.fg)) { append("${entries.size} 个待确认") }
                append(" · ")
                withStyle(SpanStyle(color = c.fg2)) {
                    append(display(first.session))
                    if (currentMachineId != null && first.machineId != currentMachineId) append(" · ${first.machineName}")
                }
            },
            modifier = Modifier.weight(1f),
            fontSize = 13.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(Symbols.ChevronRight, contentDescription = null, tint = c.fg3, modifier = Modifier.size(18.dp))
    }
}

/**
 * 浮墨's bottom-right orb morph panel (demo.html `machinePanel`): grows out of [anchor],
 * listing cross-machine pending sessions on top and paired machines + dashed「添加目标机」below.
 */
@Composable
fun InkHubSheet(
    pending: List<PendingEntry>,
    machines: List<Machine>,
    sessions: Map<String, SessionList>,
    currentMachineId: String?,
    onDismiss: () -> Unit,
    onOpenPending: (PendingEntry) -> Unit,
    onSelectMachine: (Machine) -> Unit,
    onPair: () -> Unit,
    anchor: Rect? = null,
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    ClaudercSheet(onDismiss = onDismiss, anchor = anchor) {
        val close = LocalMorphClose.current
        fun dismissThen(action: () -> Unit) {
            if (close != null) close(action) else { onDismiss(); action() }
        }
        if (pending.isNotEmpty()) {
            Row(
                Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(c.accent))
                Text("待确认 · ${pending.size}", fontSize = 13.sp, color = c.fg2, fontWeight = FontWeight.Medium)
            }
            pending.forEach { entry ->
                val detail = buildString {
                    append(entry.machineName)
                    append(" · ")
                    append(sessionStateText(entry.session))
                    entry.session.detail?.takeIf { it.isNotBlank() }?.let { append(" · ${it.trim()}") }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { dismissThen { onOpenPending(entry) } }
                        .padding(start = 22.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusGlyph(Glyph.Need)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(display(entry.session), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(detail, fontSize = 13.sp, color = c.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Symbols.ChevronRight, contentDescription = null, tint = c.fg3, modifier = Modifier.size(20.dp))
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 22.dp, vertical = 8.dp), thickness = 1.dp, color = c.edge)
        }
        Text(
            "目标机",
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 6.dp),
            fontSize = 13.sp,
            color = c.fg2,
        )
        machines.forEach { m ->
            val list = sessions[m.id]?.sessions
            val count = list?.size
            val wait = list?.count(::needsYou) ?: 0
            val sub = when {
                !m.online -> "离线 · 最后在线 ${TimeText.clock(m.lastSeenAt)}"
                count == null -> if (m.claudeVersion.isNotEmpty()) "在线 · claude ${m.claudeVersion}" else "在线"
                wait > 0 -> "在线 · $count 个会话 · $wait 个待确认"
                else -> "在线 · $count 个会话"
            }
            MenuItem(
                text = m.name,
                onClick = { dismissThen { onSelectMachine(m) } },
                icon = Symbols.Computer,
                supporting = sub,
                checked = m.id == currentMachineId,
                muted = !m.online,
            )
        }
        // Dashed circle + 「添加目标机」 matching demo.html .pm .dashed
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { dismissThen(onPair) }
                .padding(start = 18.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .drawBehind {
                        drawCircle(
                            color = c.fg3,
                            radius = size.minDimension / 2f - 1.dp.toPx(),
                            style = Stroke(
                                width = 1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                            ),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Symbols.Add, contentDescription = null, tint = c.fg2, modifier = Modifier.size(18.dp))
            }
            Text("添加目标机", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.fg)
        }
        Spacer(Modifier.height(sp.sp1))
    }
}

/**
 * 浮墨's top center pill morph panel (demo.html `statusPanel`): shows machine info, 5h & weekly usage
 * bars, profile name management, and live relay connection state.
 */
@Composable
fun InkStatusPanel(
    machine: Machine,
    usage: UsageResponse?,
    profileCount: Int,
    relays: List<RelayLink>,
    anchor: Rect?,
    onDismiss: () -> Unit,
    onManageProfiles: (() -> Unit)? = null,
    onOpenRelay: (() -> Unit)? = null,
) {
    val c = Clauderc.colors
    MorphOverlay(onDismiss = onDismiss, anchor = anchor, fromTop = true, tag = "inkStatusPanel") {
        val close = LocalMorphClose.current
        fun dismissThen(action: () -> Unit) {
            if (close != null) close(action) else { onDismiss(); action() }
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(machine.name, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = c.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = buildString {
                    if (machine.claudeVersion.isNotEmpty()) append("Claude Code ${machine.claudeVersion}")
                    if (machine.claudeVersion.isNotEmpty()) append(" · ")
                    append(if (machine.online) "在线" else "离线 · ${TimeText.clock(machine.lastSeenAt)}")
                }
                Text(sub, fontSize = 13.sp, color = c.fg2)
            }
            if (usage != null && usage.available) {
                val rows = listOfNotNull(
                    usage.fiveHour?.let { Triple("5 小时", it.utilization, it.resetsAt?.let { t -> TimeText.resets(t) }.orEmpty()) },
                    usage.sevenDay?.let { Triple("每周", it.utilization, it.resetsAt?.let { t -> TimeText.resets(t) }.orEmpty()) },
                )
                val stale = usage.stale != null
                rows.forEach { (label, util, reset) ->
                    val pct = util.roundToInt().coerceIn(0, 100)
                    Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 2.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("$pct%", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = if (stale) c.fg3 else c.fg)
                            Text(label, modifier = Modifier.padding(bottom = 3.dp), fontSize = 13.sp, color = c.fg2)
                            Spacer(Modifier.weight(1f))
                            if (reset.isNotEmpty()) {
                                Text(reset, modifier = Modifier.padding(bottom = 3.dp), fontSize = 12.sp, color = c.fg3)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.surface2)) {
                            Box(
                                Modifier
                                    .fillMaxWidth(pct / 100f)
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (stale) c.fg3 else c.fg),
                            )
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 6.dp), thickness = 1.dp, color = c.edge)
            if (onManageProfiles != null && profileCount > 0) {
                InkStatusRow(
                    icon = Symbols.Edit,
                    title = "管理启动档位名",
                    value = "$profileCount 档",
                    onClick = { dismissThen(onManageProfiles) },
                )
            }
            val relayValue = when {
                relays.isEmpty() -> "未配对"
                relays.all { it.state is ConnectionState.Connected } -> "已连接"
                relays.any { it.state is ConnectionState.Connecting } -> "连接中"
                else -> "已断开"
            }
            InkStatusRow(
                icon = Symbols.Dns,
                title = "中转服务器",
                value = relayValue,
                onClick = { if (onOpenRelay != null) dismissThen(onOpenRelay) else close?.invoke(null) ?: onDismiss() },
            )
        }
    }
}

@Composable
private fun InkStatusRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    val c = Clauderc.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = c.fg2, modifier = Modifier.size(20.dp))
        Text(title, modifier = Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.fg)
        Text(value, fontSize = 14.sp, color = c.fg2)
        Icon(Symbols.ChevronRight, contentDescription = null, tint = c.fg3, modifier = Modifier.size(18.dp))
    }
}

/** Shared live relay connection status panel (used from 浮墨's status panel & settings「中转服务器」). */
@Composable
fun RelayStatusSheet(
    relays: List<RelayLink>,
    onDismiss: () -> Unit,
    anchor: Rect? = null,
    fromTop: Boolean = false,
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    ClaudercSheet(onDismiss = onDismiss, anchor = anchor, fromTop = fromTop) {
        SheetHead("中转服务器", if (relays.isEmpty()) "扫描目标机二维码后会自动连接中转服务器" else "当前已配对的中转连接状态")
        if (relays.isEmpty()) {
            Text(
                "尚未配对目标机",
                modifier = Modifier.padding(horizontal = sp.page, vertical = sp.sp3),
                fontSize = Clauderc.fontSizes.label,
                color = c.fg3,
            )
        } else {
            relays.forEach { link ->
                val status = when (link.state) {
                    ConnectionState.Connected -> "已连接"
                    ConnectionState.Connecting -> "正在连接"
                    is ConnectionState.Waiting -> "等待重连"
                    ConnectionState.Revoked -> "凭证已被吊销，请重新配对"
                    ConnectionState.Idle -> "未连接"
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = sp.page, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (link.state) {
                        ConnectionState.Connected -> StatusGlyph(Glyph.On)
                        ConnectionState.Connecting -> Spinner(size = 12.dp, color = c.fg2)
                        ConnectionState.Revoked -> StatusGlyph(Glyph.Need)
                        else -> StatusGlyph(Glyph.Stop)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(link.host, fontFamily = MonoFamily, fontSize = 14.sp, color = c.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(status, fontSize = 12.5.sp, color = c.fg2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.height(sp.sp2))
    }
}

/** Shared machine switcher sheet for 清玻 / 素卡 / 印版. */
@Composable
fun MachineSwitcherSheet(
    machines: List<Machine>,
    sessions: Map<String, SessionList>,
    currentMachineId: String?,
    onDismiss: () -> Unit,
    onSelectMachine: (Machine) -> Unit,
    onPair: () -> Unit,
) {
    ClaudercSheet(onDismiss = onDismiss) {
        SheetHead("目标机")
        MachineSwitcherItems(machines, sessions, currentMachineId, onDismiss, onSelectMachine, onPair)
        Spacer(Modifier.height(Clauderc.spacing.sp2))
    }
}

@Composable
private fun MachineSwitcherItems(
    machines: List<Machine>,
    sessions: Map<String, SessionList>,
    currentMachineId: String?,
    onDismiss: () -> Unit,
    onSelectMachine: (Machine) -> Unit,
    onPair: () -> Unit,
) {
    machines.forEach { m ->
        val list = sessions[m.id]?.sessions
        val count = list?.size
        val wait = list?.count(::needsYou) ?: 0
        val sub = when {
            !m.online -> "离线 · 最后在线 ${TimeText.clock(m.lastSeenAt)}"
            count == null -> if (m.claudeVersion.isNotEmpty()) "在线 · claude ${m.claudeVersion}" else "在线"
            wait > 0 -> "在线 · $count 个会话 · $wait 个待确认"
            else -> "在线 · $count 个会话"
        }
        MenuItem(
            text = m.name,
            onClick = { onDismiss(); onSelectMachine(m) },
            icon = Symbols.Computer,
            supporting = sub,
            checked = m.id == currentMachineId,
            muted = !m.online,
        )
    }
    MenuItem(
        text = "配对新的目标机",
        onClick = { onDismiss(); onPair() },
        icon = Symbols.QrCodeScanner,
    )
}

/**
 * Compact cross-machine pending jump badge for secondary tabs (git & settings) top-right:
 * - 素卡: small scalloped cookie badge with accent fill and count.
 * - 印版: solid ink `.stamp` seal (`02 待确认`).
 */
@Composable
fun PendingJumpBadge(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) return
    val c = Clauderc.colors
    val press = remember { MutableInteractionSource() }
    when (Clauderc.style) {
        UiStyle.Tile -> Box(
            modifier
                .pressShrink(press, PressKind.Icon)
                .size(38.dp)
                .clip(CookieShape)
                .background(c.accent)
                .clickable(press, PressIndication(c.onAccent), role = Role.Button, onClick = onClick)
                .semantics { contentDescription = "$count 个待确认" }
                .testTag("pendingJumpBadge"),
            contentAlignment = Alignment.Center,
        ) {
            Text(count.toString(), color = c.onAccent, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
        }
        UiStyle.Plate -> {
            val pressed by press.collectIsPressedAsState()
            val arrowShift by animateDpAsState(
                targetValue = if (pressed) 6.dp else 0.dp,
                animationSpec = tween(160, easing = Motion.plateEasePress),
                label = "stampArrowShift",
            )
            Row(
                modifier
                    .pressShrink(press, PressKind.Primary)
                    .plateStamp(c.fg, c.bg, c.fg, source = press)
                    .clickable(press, PressIndication(c.bg), role = Role.Button, onClick = onClick)
                    .padding(horizontal = 9.dp, vertical = 5.dp)
                    .semantics { contentDescription = "$count 个待确认" }
                    .testTag("pendingJumpBadge"),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "%02d 待确认".format(count),
                    color = c.bg,
                    fontSize = 11.sp,
                    fontFamily = MonoFamily,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    "→",
                    color = c.bg,
                    fontSize = 11.sp,
                    fontFamily = MonoFamily,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.offset(x = arrowShift),
                )
            }
        }
        else -> Unit
    }
}
