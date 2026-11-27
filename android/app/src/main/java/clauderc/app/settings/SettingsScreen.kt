package clauderc.app.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import clauderc.app.sessions.ClearGlassTopBar
import clauderc.app.sessions.TileMiniBar
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import clauderc.app.ui.theme.Motion
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import clauderc.app.data.AccentChoice
import clauderc.app.data.Appearance
import clauderc.app.data.PlateInk
import clauderc.app.data.PlateNavStyle
import clauderc.app.data.ThemeMode
import clauderc.app.data.UiStyle
import clauderc.app.machines.InkStatusPanel
import clauderc.app.machines.NavClearance
import clauderc.app.machines.PendingJumpBadge
import clauderc.app.machines.RelayStatusSheet
import clauderc.app.net.ConnectionState
import clauderc.app.net.RelayLink
import clauderc.app.protocol.Machine
import clauderc.app.sessions.InkTopBar
import clauderc.app.sessions.InkTopRight
import clauderc.app.ui.components.AppBar
import clauderc.app.ui.components.CardCorner
import clauderc.app.ui.components.ClaudercDialog
import clauderc.app.ui.components.ClaudercSheet
import clauderc.app.ui.components.Glyph
import clauderc.app.ui.components.Hero
import clauderc.app.ui.components.PlateRule
import clauderc.app.ui.components.PlateSweep
import clauderc.app.ui.components.plateStamp
import clauderc.app.ui.components.plateSweepFill
import androidx.compose.ui.graphics.lerp
import clauderc.app.ui.components.SheetHead
import clauderc.app.ui.components.Spinner
import clauderc.app.ui.components.StatusGlyph
import clauderc.app.ui.components.Switch
import clauderc.app.ui.components.TextAction
import clauderc.app.ui.components.TextTone
import clauderc.app.ui.components.captureAnchor
import clauderc.app.ui.components.inkSurface
import clauderc.app.ui.components.rememberAnchorBox
import clauderc.app.ui.components.tileSurface
import clauderc.app.ui.components.topLine
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.pressProgress
import clauderc.app.ui.theme.listRowPress
import clauderc.app.ui.theme.controlPress
import clauderc.app.ui.theme.PressTone
import clauderc.app.ui.theme.PressRow
import clauderc.app.ui.theme.ClaudercColors
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.PressKind
import clauderc.app.ui.theme.Symbols
import clauderc.app.ui.theme.accentColor
import clauderc.app.ui.theme.page
import clauderc.app.ui.theme.palette
import clauderc.app.ui.theme.pressShrink
import kotlin.math.ceil
import kotlinx.coroutines.delay

/** Names of the accents, in the order of the swatches. */
val AccentChoice.label: String
    get() = when (this) {
        AccentChoice.Default -> "默认"
        AccentChoice.Wallpaper -> "跟随壁纸"
        AccentChoice.Blue -> "蓝色"
        AccentChoice.Purple -> "紫色"
        AccentChoice.Pink -> "粉色"
        AccentChoice.Red -> "红色"
        AccentChoice.Orange -> "橙色"
        AccentChoice.Yellow -> "黄色"
        AccentChoice.Green -> "绿色"
        AccentChoice.Gray -> "灰色"
    }

private sealed interface SettingsOverlay {
    data class Relay(val anchor: Rect? = null) : SettingsOverlay
    data class ClearConfirm(val anchor: Rect? = null) : SettingsOverlay
    data class About(val anchor: Rect? = null) : SettingsOverlay
    data class MachineStatus(val anchor: Rect? = null) : SettingsOverlay
}

/**
 * The 设置 tab (design/ui-mockup/demo.html):
 * - 浮墨: compact single-screen layout with floating InkTopBar, 24dp `.blk` groups for 外观
 *   (4-col mini phone previews + single-row light/dark segmented pill + compact accent dots),
 *   连接 (live relay status + pair + clear pairings), and 关于 — each row morphing its overlay from the row.
 * - 印版 / 清玻 / 素卡: style-specific layouts (further refined in their dedicated steps).
 */
@Composable
fun SettingsScreen(
    look: Appearance,
    cardAccent: Color?,
    wallpaperAvailable: Boolean,
    onStyle: (UiStyle, Offset) -> Unit,
    onMode: (ThemeMode, Offset) -> Unit,
    onAccent: (AccentChoice, Offset) -> Unit,
    onPlateInk: (PlateInk, Offset) -> Unit = { _, _ -> },
    onPlateNav: (PlateNavStyle) -> Unit = {},
    relays: List<RelayLink> = emptyList(),
    onPair: () -> Unit = {},
    onClearAll: () -> Unit = {},
    pendingCount: Int = 0,
    onOpenPending: () -> Unit = {},
    versionName: String? = null,
    onOpenDrawer: (() -> Unit)? = null,
    onPairAt: ((Rect?) -> Unit)? = null,
    machine: Machine? = null,
    machines: List<Machine> = emptyList(),
) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    val dark = c.isDark
    val context = LocalContext.current
    val resolvedVersion = versionName ?: remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty().ifEmpty { "dev" }
    }
    val bottomClearance = if (ui == UiStyle.Clear) 144.dp else NavClearance
    var overlay by remember { mutableStateOf<SettingsOverlay?>(null) }

    if (ui == UiStyle.Ink) {
        InkSettingsContent(
            look = look,
            cardAccent = cardAccent,
            wallpaperAvailable = wallpaperAvailable,
            dark = dark,
            relays = relays,
            machine = machine,
            resolvedVersion = resolvedVersion,
            bottomClearance = bottomClearance,
            onStyle = onStyle,
            onMode = onMode,
            onAccent = onAccent,
            onOpenDrawer = { onOpenDrawer?.invoke() },
            onOpenMachineStatus = { rect ->
                if (machine != null) overlay = SettingsOverlay.MachineStatus(rect)
                else onPairAt?.invoke(rect) ?: onPair()
            },
            onOpenRelay = { rect -> overlay = SettingsOverlay.Relay(rect) },
            onPairAt = { rect -> onPairAt?.invoke(rect) ?: onPair() },
            onRequestClear = { rect -> overlay = SettingsOverlay.ClearConfirm(rect) },
            onOpenAbout = { rect -> overlay = SettingsOverlay.About(rect) },
        )
    } else if (ui == UiStyle.Plate) {
        PlateSettingsContent(
            look = look,
            cardAccent = cardAccent,
            dark = dark,
            relays = relays,
            pendingCount = pendingCount,
            resolvedVersion = resolvedVersion,
            bottomClearance = bottomClearance,
            onStyle = onStyle,
            onMode = onMode,
            onPlateInk = onPlateInk,
            onPlateNav = onPlateNav,
            onOpenPending = onOpenPending,
            onOpenRelay = { overlay = SettingsOverlay.Relay(it) },
            onPairAt = { onPairAt?.invoke(it) ?: onPair() },
            onRequestClear = { overlay = SettingsOverlay.ClearConfirm(it) },
            onOpenAbout = { overlay = SettingsOverlay.About(it) },
        )
    } else if (ui == UiStyle.Clear) {
        ClearSettingsContent(
            look = look,
            cardAccent = cardAccent,
            wallpaperAvailable = wallpaperAvailable,
            dark = dark,
            relays = relays,
            resolvedVersion = resolvedVersion,
            bottomClearance = bottomClearance,
            onStyle = onStyle,
            onMode = onMode,
            onAccent = onAccent,
            onOpenRelay = { overlay = SettingsOverlay.Relay(it) },
            onPairAt = { onPairAt?.invoke(it) ?: onPair() },
            onRequestClear = { overlay = SettingsOverlay.ClearConfirm(it) },
            onOpenAbout = { overlay = SettingsOverlay.About(it) },
        )
    } else {
        TileSettingsContent(
            look = look,
            cardAccent = cardAccent,
            wallpaperAvailable = wallpaperAvailable,
            dark = dark,
            relays = relays,
            pendingCount = pendingCount,
            resolvedVersion = resolvedVersion,
            bottomClearance = bottomClearance,
            onStyle = onStyle,
            onMode = onMode,
            onAccent = onAccent,
            onOpenPending = onOpenPending,
            onOpenRelay = { overlay = SettingsOverlay.Relay(it) },
            onPairAt = { onPairAt?.invoke(it) ?: onPair() },
            onRequestClear = { overlay = SettingsOverlay.ClearConfirm(it) },
            onOpenAbout = { overlay = SettingsOverlay.About(it) },
        )
    }

    when (val o = overlay) {
        is SettingsOverlay.Relay -> RelayStatusSheet(
            relays = relays,
            onDismiss = { overlay = null },
            anchor = o.anchor,
            fromTop = false,
        )
        is SettingsOverlay.ClearConfirm -> ClaudercDialog(
            onDismiss = { overlay = null },
            title = "清除全部配对？",
            text = "这台手机会忘记所有中转服务器的凭证，需要重新扫码配对。",
            anchor = o.anchor,
            buttons = {
                TextAction("取消", onClick = { overlay = null })
                TextAction("清除", onClick = { overlay = null; onClearAll() }, tone = TextTone.Danger)
            },
        )
        is SettingsOverlay.About -> ClaudercSheet(
            onDismiss = { overlay = null },
            anchor = o.anchor,
        ) {
            SheetHead("关于 clauderc", "手机远程查看和操作目标机后台 Claude 会话")
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Clauderc.spacing.page, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AboutMetaRow("版本", resolvedVersion, mono = true)
                AboutMetaRow("协议", "v1 · WebSocket Relay", mono = true)
                val onlineCount = machines.count { it.online }
                AboutMetaRow("目标机", "${onlineCount} 台在线 / ${machines.size} 台已配对", mono = false)
            }
            Spacer(Modifier.height(Clauderc.spacing.sp3))
        }
        is SettingsOverlay.MachineStatus -> if (machine != null) {
            InkStatusPanel(
                machine = machine,
                usage = null,
                profileCount = 0,
                relays = relays,
                anchor = o.anchor,
                onDismiss = { overlay = null },
                onOpenRelay = { overlay = SettingsOverlay.Relay(o.anchor) },
            )
        }
        null -> Unit
    }
}

@Composable
private fun AboutMetaRow(label: String, value: String, mono: Boolean) {
    val c = Clauderc.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = Clauderc.fontSizes.label, color = c.fg3)
        Text(value, fontSize = if (mono) Clauderc.fontSizes.monoSm else Clauderc.fontSizes.label, fontFamily = if (mono) MonoFamily else null, color = c.fg)
    }
}

/**
 * 素卡's compact single-screen white rounded-card settings (demo.html `.grp` + `.sitem` + `.pchip`):
 * 30sp Bold 设置 title + top-right scalloped cookie badge, borderless 24dp pure-white rounded cards
 * for 外观 (4-col mini previews + single-row 深浅色 capsule + compact 主题色 swatches), 连接, and 关于,
 * with a scroll-triggered compact [TileMiniBar] fading in past 70dp.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TileSettingsContent(
    look: Appearance,
    cardAccent: Color?,
    wallpaperAvailable: Boolean,
    dark: Boolean,
    relays: List<RelayLink>,
    pendingCount: Int,
    resolvedVersion: String,
    bottomClearance: androidx.compose.ui.unit.Dp,
    onStyle: (UiStyle, Offset) -> Unit,
    onMode: (ThemeMode, Offset) -> Unit,
    onAccent: (AccentChoice, Offset) -> Unit,
    onOpenPending: () -> Unit,
    onOpenRelay: (Rect?) -> Unit,
    onPairAt: (Rect?) -> Unit,
    onRequestClear: (Rect?) -> Unit,
    onOpenAbout: (Rect?) -> Unit,
) {
    val c = Clauderc.colors
    val scroll = rememberScrollState()
    val scrollPx = with(LocalDensity.current) { 70.dp.toPx() }
    val scrolled by remember(scroll, scrollPx) { derivedStateOf { scroll.value > scrollPx } }

    Box(Modifier.fillMaxSize().page()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .statusBarsPadding()
                .padding(bottom = bottomClearance)
                .testTag("tileSettings"),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("设置", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = c.fg, modifier = Modifier.weight(1f))
                PendingJumpBadge(pendingCount, onOpenPending)
            }

            TileSettingsSection("外观")
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .tileSurface(c.card, CardCorner),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (style in UiStyle.entries) {
                        TileMiniStyleOption(style, look.style == style, dark, cardAccent, look.plateInk, onStyle)
                    }
                }
                HorizontalDivider(Modifier.padding(start = 52.dp), thickness = 1.dp, color = c.line)
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Symbols.Contrast, contentDescription = null, tint = c.fg2, modifier = Modifier.size(21.dp))
                    Text("深浅色", modifier = Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.fg)
                    ModeSegmentRow(look.mode, onMode, compact = true)
                }
                HorizontalDivider(Modifier.padding(start = 52.dp), thickness = 1.dp, color = c.line)
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Symbols.Palette, contentDescription = null, tint = c.fg2, modifier = Modifier.size(21.dp))
                    Text("主题色", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.fg)
                    Spacer(Modifier.weight(1f))
                    Text(look.accent.label, fontSize = 12.5.sp, color = c.fg3)
                }
                val choices = AccentChoice.entries.filter { it != AccentChoice.Wallpaper || wallpaperAvailable }
                FlowRow(
                    Modifier.fillMaxWidth().padding(start = 48.dp, end = 16.dp, top = 4.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    choices.forEach { choice ->
                        CompactSwatch(choice, choice == look.accent, dark, onAccent)
                    }
                }
            }

            TileSettingsSection("连接")
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .tileSurface(c.card, CardCorner),
            ) {
                val primaryHost = relays.firstOrNull()?.host
                val relayStateText = when {
                    relays.isEmpty() -> "未配对"
                    relays.all { it.state is ConnectionState.Connected } -> "已连接"
                    relays.any { it.state is ConnectionState.Waiting } -> "重连中"
                    else -> "连接中"
                }
                TileSettingsRow(
                    icon = Symbols.Dns,
                    title = "中转服务器",
                    subtitle = primaryHost?.let { if (relays.size > 1) "$it 等 ${relays.size} 台" else it },
                    trailing = relayStateText,
                    tag = "settingsRelayRow",
                    onClick = { onOpenRelay(null) },
                )
                HorizontalDivider(Modifier.padding(start = 52.dp), thickness = 1.dp, color = c.line)
                TileSettingsRow(
                    icon = Symbols.QrCodeScanner,
                    title = "配对新的目标机",
                    chevron = true,
                    tag = "settingsPairRow",
                    onClick = { onPairAt(null) },
                )
                if (relays.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(start = 52.dp), thickness = 1.dp, color = c.line)
                    TileSettingsRow(
                        icon = Symbols.Delete,
                        title = "清除全部配对",
                        danger = true,
                        tag = "settingsClearPairingsRow",
                        onClick = { onRequestClear(null) },
                    )
                }
            }

            TileSettingsSection("关于")
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .tileSurface(c.card, CardCorner),
            ) {
                TileSettingsRow(
                    icon = Symbols.Description,
                    title = "clauderc",
                    trailing = resolvedVersion,
                    tag = "settingsAboutRow",
                    onClick = { onOpenAbout(null) },
                )
            }
        }

        TileMiniBar(
            visible = scrolled,
            title = "设置",
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun TileSettingsSection(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 22.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = Clauderc.colors.fg2,
    )
}

@Composable
private fun RowScope.TileMiniStyleOption(
    style: UiStyle,
    selected: Boolean,
    dark: Boolean,
    cardAccent: Color?,
    plateInk: PlateInk,
    onPick: (UiStyle, Offset) -> Unit,
) {
    val c = Clauderc.colors
    var center by remember { mutableStateOf(Offset.Zero) }
    val press = remember { MutableInteractionSource() }
    val edge = previewPressEdge(press, selected)
    Column(
        Modifier
            .weight(1f)
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .pressShrink(press, PressKind.Preview)
            .clip(RoundedCornerShape(14.dp))
            .selectable(selected, press, null, role = Role.RadioButton) { onPick(style, center) }
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.66f)
                .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else edge, RoundedCornerShape(12.dp))
                .padding(2.dp),
        ) {
            MiniPhone(style, dark, cardAccent, plateInk)
        }
        Text(
            style.label,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) c.fg else c.fg2,
            maxLines = 1,
        )
    }
}

@Composable
private fun TileSettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    chevron: Boolean = false,
    danger: Boolean = false,
    tag: String,
    onClick: () -> Unit,
) {
    val c = Clauderc.colors
    Row(
        Modifier
            .fillMaxWidth()
            .listRowPress(if (danger) PressRow.Danger else PressRow.Page, onClick = onClick)
            .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (danger) c.danger else c.fg2, modifier = Modifier.size(21.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (danger) c.danger else c.fg)
            if (subtitle != null) {
                Text(subtitle, fontFamily = MonoFamily, fontSize = 12.sp, color = c.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Text(trailing, fontSize = 13.5.sp, color = c.fg2)
        }
        if (chevron) {
            Icon(Symbols.ChevronRight, contentDescription = null, tint = c.fg3, modifier = Modifier.size(19.dp))
        }
    }
}

/**
 * 清玻's borderless flat single-screen settings (demo.html `.sitem` + `.styles` + `.mode` + `.sw-row`):
 * pure white/black paper with scroll-triggered frosted `.gbar`, 4-col mini phone previews,
 * single-row capsule 深浅色 toggle, compact circular 主题色 swatches, and 连接 / 关于 rows opening [ClearDropPanel].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClearSettingsContent(
    look: Appearance,
    cardAccent: Color?,
    wallpaperAvailable: Boolean,
    dark: Boolean,
    relays: List<RelayLink>,
    resolvedVersion: String,
    bottomClearance: androidx.compose.ui.unit.Dp,
    onStyle: (UiStyle, Offset) -> Unit,
    onMode: (ThemeMode, Offset) -> Unit,
    onAccent: (AccentChoice, Offset) -> Unit,
    onOpenRelay: (Rect?) -> Unit,
    onPairAt: (Rect?) -> Unit,
    onRequestClear: (Rect?) -> Unit,
    onOpenAbout: (Rect?) -> Unit,
) {
    val c = Clauderc.colors
    val scroll = rememberScrollState()
    val haze = rememberHazeState()
    val scrollPx = with(LocalDensity.current) { 90.dp.toPx() }
    val scrolled by remember(scroll, scrollPx) { derivedStateOf { scroll.value > scrollPx } }

    Box(Modifier.fillMaxSize().page()) {
        Column(
            Modifier
                .fillMaxSize()
                .hazeSource(haze)
                .verticalScroll(scroll)
                .statusBarsPadding()
                .padding(bottom = bottomClearance)
                .testTag("clearSettings"),
        ) {
            Text(
                "设置",
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 2.dp),
                fontSize = 32.sp,
                fontWeight = FontWeight.Medium,
                color = c.fg,
            )

            ClearSettingsSection("外观")
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (style in UiStyle.entries) {
                    InkMiniStyleOption(style, look.style == style, dark, cardAccent, look.plateInk, onStyle)
                }
            }
            // Single-row 深浅色 with right-aligned capsule segmented control
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Symbols.Contrast, contentDescription = null, tint = c.fg2, modifier = Modifier.size(21.dp))
                Text("深浅色", modifier = Modifier.weight(1f), fontSize = 15.5.sp, color = c.fg)
                ModeSegmentRow(look.mode, onMode, compact = true)
            }
            // 主题色 row + compact circular swatches
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 6.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Symbols.Palette, contentDescription = null, tint = c.fg2, modifier = Modifier.size(21.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("主题色", fontSize = 15.5.sp, color = c.fg)
                    Text("只用在等你拍板的地方", fontSize = 12.sp, color = c.fg2)
                }
                Text(look.accent.label, fontSize = 12.5.sp, color = c.fg3)
            }
            val choices = AccentChoice.entries.filter { it != AccentChoice.Wallpaper || wallpaperAvailable }
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 59.dp, end = 20.dp, top = 2.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                choices.forEach { choice ->
                    CompactSwatch(choice, choice == look.accent, dark, onAccent)
                }
            }

            ClearSettingsSection("连接")
            val primaryHost = relays.firstOrNull()?.host
            val relayStateText = when {
                relays.isEmpty() -> "未配对"
                relays.all { it.state is ConnectionState.Connected } -> "已连接"
                relays.any { it.state is ConnectionState.Waiting } -> "重连中"
                else -> "连接中"
            }
            ClearSettingsRow(
                icon = Symbols.Dns,
                title = "中转服务器",
                subtitle = primaryHost?.let { if (relays.size > 1) "$it 等 ${relays.size} 台" else it },
                trailing = relayStateText,
                tag = "settingsRelayRow",
                onClick = { onOpenRelay(null) },
            )
            ClearSettingsRow(
                icon = Symbols.QrCodeScanner,
                title = "配对新的目标机",
                chevron = true,
                tag = "settingsPairRow",
                onClick = { onPairAt(null) },
            )
            if (relays.isNotEmpty()) {
                ClearSettingsRow(
                    icon = Symbols.Delete,
                    title = "清除全部配对",
                    danger = true,
                    tag = "settingsClearPairingsRow",
                    onClick = { onRequestClear(null) },
                )
            }

            ClearSettingsSection("关于")
            ClearSettingsRow(
                icon = Symbols.Description,
                title = "clauderc",
                trailing = resolvedVersion,
                tag = "settingsAboutRow",
                onClick = { onOpenAbout(null) },
            )
        }

        ClearGlassTopBar(
            visible = scrolled,
            title = "设置",
            haze = haze,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun ClearSettingsSection(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 2.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = Clauderc.colors.fg2,
    )
}

@Composable
private fun ClearSettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    chevron: Boolean = false,
    danger: Boolean = false,
    tag: String,
    onClick: () -> Unit,
) {
    val c = Clauderc.colors
    Row(
        Modifier
            .fillMaxWidth()
            .listRowPress(if (danger) PressRow.Danger else PressRow.Page, onClick = onClick)
            .padding(start = 24.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (danger) c.danger else c.fg2, modifier = Modifier.size(21.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, fontSize = 16.sp, color = if (danger) c.danger else c.fg)
            if (subtitle != null) {
                Text(subtitle, fontFamily = MonoFamily, fontSize = 12.sp, color = c.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Text(trailing, fontSize = 14.sp, color = c.fg2)
        }
        if (chevron) {
            Icon(Symbols.ChevronRight, contentDescription = null, tint = c.fg3, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * 印版's borderless horizontal-ruled compact settings (demo.html `.sitem` + `.styles` + `.modes` + `.inks`):
 * 30sp heavy title + top-right `.stamp`, single-row underline 深浅色 & 底栏样式 switches,
 * 4×2 8-ink grid, and compact 连接 / 关于 rows opening [PlateSlipPanel].
 */
@Composable
private fun PlateSettingsContent(
    look: Appearance,
    cardAccent: Color?,
    dark: Boolean,
    relays: List<RelayLink>,
    pendingCount: Int,
    resolvedVersion: String,
    bottomClearance: androidx.compose.ui.unit.Dp,
    onStyle: (UiStyle, Offset) -> Unit,
    onMode: (ThemeMode, Offset) -> Unit,
    onPlateInk: (PlateInk, Offset) -> Unit,
    onPlateNav: (PlateNavStyle) -> Unit,
    onOpenPending: () -> Unit,
    onOpenRelay: (Rect?) -> Unit,
    onPairAt: (Rect?) -> Unit,
    onRequestClear: (Rect?) -> Unit,
    onOpenAbout: (Rect?) -> Unit,
) {
    val c = Clauderc.colors
    Column(
        Modifier
            .fillMaxSize()
            .page()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(bottom = bottomClearance)
            .testTag("plateSettings"),
    ) {
        // Compact header with right-aligned solid-ink pending stamp + 30sp Black 设置 title
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("设置", fontSize = 30.sp, fontWeight = FontWeight.Black, color = c.fg, modifier = Modifier.weight(1f))
            PendingJumpBadge(pendingCount, onOpenPending)
        }

        // 外观 section
        PlateSettingsSectionRule("外观")
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (style in UiStyle.entries) {
                PlateMiniStyleOption(style, look.style == style, dark, cardAccent, look.plateInk, onStyle)
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 18.dp), thickness = 1.dp, color = c.line)
        // Single-row 深浅色 with animated underline .modes buttons
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("深浅色", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = c.fg, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(ThemeMode.System to "跟随系统", ThemeMode.Light to "浅色", ThemeMode.Dark to "深色").forEach { (mode, label) ->
                    PlateUnderlineOption(
                        label = label,
                        selected = look.mode == mode,
                        onClick = { onMode(mode, it) },
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 18.dp), thickness = 1.dp, color = c.line)
        // 4×2 墨色 grid (demo.html .inks)
        Text(
            "墨色",
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 6.dp),
            fontSize = 12.sp,
            fontFamily = MonoFamily,
            fontWeight = FontWeight.ExtraBold,
            color = c.fg2,
        )
        Column(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PlateInk.entries.chunked(4).forEach { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (ink in line) {
                        val selected = ink == look.plateInk
                        val swatch = Color(0xFF000000 or if (dark) ink.darkInk else ink.lightInk)
                        val press = remember { MutableInteractionSource() }
                        var center by remember { mutableStateOf(Offset.Zero) }
                        Row(
                            Modifier
                                .weight(1f)
                                .onGloballyPositioned { center = it.boundsInRoot().center }
                                .pressShrink(press, PressKind.Swatch)
                                .semantics { contentDescription = ink.label }
                                .selectable(selected, press, controlPress(PressKind.Swatch), role = Role.RadioButton) { onPlateInk(ink, center) }
                                .padding(horizontal = 2.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .then(if (selected) Modifier.border(2.dp, c.fg, RectangleShape).padding(2.dp) else Modifier.padding(1.dp))
                                    .background(swatch, RectangleShape)
                                    .border(1.dp, c.fg.copy(alpha = 0.28f), RectangleShape),
                            )
                            Text(
                                ink.label,
                                fontSize = 12.5.sp,
                                fontFamily = MonoFamily,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (selected) c.fg else lerp(c.fg2, c.fg, pressProgress(press, PressKind.Swatch).value),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 18.dp), thickness = 1.dp, color = c.line)
        // Single-row 底栏样式 with underline toggle
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 14.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("底栏样式", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = c.fg, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                PlateNavStyle.entries.forEach { option ->
                    PlateUnderlineOption(
                        label = option.label,
                        selected = look.plateNav == option,
                        onClick = { onPlateNav(option) },
                    )
                }
            }
        }

        // 连接 section
        PlateSettingsSectionRule("连接")
        val primaryHost = relays.firstOrNull()?.host
        val relayStateText = when {
            relays.isEmpty() -> "未配对"
            relays.all { it.state is ConnectionState.Connected } -> "已连接"
            relays.any { it.state is ConnectionState.Waiting } -> "重连中"
            else -> "连接中"
        }
        PlateSettingsRow(
            title = "中转服务器",
            subtitle = primaryHost?.let { if (relays.size > 1) "$it 等 ${relays.size} 台" else it },
            trailing = relayStateText,
            tag = "settingsRelayRow",
            onClick = { onOpenRelay(null) },
        )
        PlateSettingsRow(
            title = "配对新的目标机",
            trailing = "→",
            tag = "settingsPairRow",
            onClick = { onPairAt(null) },
        )
        if (relays.isNotEmpty()) {
            PlateSettingsRow(
                title = "清除全部配对",
                danger = true,
                tag = "settingsClearPairingsRow",
                onClick = { onRequestClear(null) },
            )
        }

        // 关于 section
        PlateSettingsSectionRule("关于")
        PlateSettingsRow(
            title = "clauderc",
            trailing = resolvedVersion,
            tag = "settingsAboutRow",
            showDivider = false,
            onClick = { onOpenAbout(null) },
        )
    }
}

@Composable
private fun PlateSettingsSectionRule(title: String) {
    val c = Clauderc.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 12.dp)
            .topLine(c.fg, PlateRule),
    ) {
        Text(
            title,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            fontSize = 12.sp,
            fontFamily = MonoFamily,
            fontWeight = FontWeight.ExtraBold,
            color = c.fg,
        )
    }
}

@Composable
private fun RowScope.PlateMiniStyleOption(
    style: UiStyle,
    selected: Boolean,
    dark: Boolean,
    cardAccent: Color?,
    plateInk: PlateInk,
    onPick: (UiStyle, Offset) -> Unit,
) {
    val c = Clauderc.colors
    var center by remember { mutableStateOf(Offset.Zero) }
    val press = remember { MutableInteractionSource() }
    val edge = previewPressEdge(press, selected)
    Column(
        Modifier
            .weight(1f)
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .pressShrink(press, PressKind.Preview)
            .selectable(selected, press, null, role = Role.RadioButton) { onPick(style, center) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.66f)
                .border(if (selected) 2.dp else 1.dp, if (selected) c.fg else edge, RectangleShape)
                .padding(2.dp),
        ) {
            MiniPhone(style, dark, cardAccent, plateInk)
        }
        Text(
            style.label,
            fontSize = 12.5.sp,
            fontFamily = MonoFamily,
            fontWeight = FontWeight.ExtraBold,
            color = if (selected) c.fg else c.fg2,
            maxLines = 1,
        )
    }
}

@Composable
private fun PlateUnderlineOption(
    label: String,
    selected: Boolean,
    onClick: (Offset) -> Unit,
) {
    val c = Clauderc.colors
    var center by remember { mutableStateOf(Offset.Zero) }
    val press = remember { MutableInteractionSource() }
    val lineScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(340, easing = Motion.plateEasePress),
        label = "plateModeUnderline:$label",
    )
    Box(
        Modifier
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .pressShrink(press, PressKind.Segment)
            .selectable(selected, press, controlPress(PressKind.Segment), role = Role.RadioButton) { onClick(center) }
            .drawBehind {
                if (lineScale > 0.005f) {
                    val h = 2.5.dp.toPx()
                    val pad = 5.dp.toPx()
                    val fullW = (size.width - pad * 2f).coerceAtLeast(0f)
                    val w = fullW * lineScale
                    drawRect(c.fg, Offset(pad + (fullW - w) / 2f, size.height - h), Size(w, h))
                }
            }
            .padding(horizontal = 6.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontFamily = MonoFamily,
            fontWeight = FontWeight.ExtraBold,
            color = if (selected) c.fg else c.fg2,
            maxLines = 1,
        )
    }
}

@Composable
private fun PlateSettingsRow(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    danger: Boolean = false,
    tag: String,
    showDivider: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Clauderc.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .then(
                if (showDivider) {
                    Modifier.drawBehind {
                        val h = 1.dp.toPx()
                        drawRect(c.line, Offset(0f, size.height - h), Size(size.width, h))
                    }
                } else {
                    Modifier
                },
            )
            .listRowPress(if (danger) PressRow.Danger else PressRow.Page, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = if (danger) c.danger else c.fg)
            if (subtitle != null) {
                Text(subtitle, fontFamily = MonoFamily, fontSize = 11.5.sp, color = c.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Text(trailing, fontFamily = MonoFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (danger) c.danger else c.fg2)
        }
    }
}

/**
 * 浮墨's compact single-screen settings (demo.html `.title` + `.sec` + `.blk`):
 * fits 外观 (4-col mini previews + single-row segmented mode pill + compact accent dots),
 * 连接 (live relay + pair + clear), and 关于 right on the first screen, with every action row
 * morphing its card straight from the tapped row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InkSettingsContent(
    look: Appearance,
    cardAccent: Color?,
    wallpaperAvailable: Boolean,
    dark: Boolean,
    relays: List<RelayLink>,
    machine: Machine?,
    resolvedVersion: String,
    bottomClearance: androidx.compose.ui.unit.Dp,
    onStyle: (UiStyle, Offset) -> Unit,
    onMode: (ThemeMode, Offset) -> Unit,
    onAccent: (AccentChoice, Offset) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenMachineStatus: (Rect?) -> Unit,
    onOpenRelay: (Rect?) -> Unit,
    onPairAt: (Rect?) -> Unit,
    onRequestClear: (Rect?) -> Unit,
    onOpenAbout: (Rect?) -> Unit,
) {
    val c = Clauderc.colors
    val inkTopPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 70.dp
    Box(Modifier.fillMaxSize().page()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = inkTopPad, bottom = bottomClearance),
        ) {
            Text(
                "设置",
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 0.dp, bottom = 2.dp),
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
                color = c.fg,
            )

            InkSettingsSectionLabel("外观")
            Column(
                Modifier
                    .padding(horizontal = 14.dp)
                    .fillMaxWidth()
                    .inkSurface(c.card, c.edge, 24.dp),
            ) {
                // 4-column compact mini phone previews
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (style in UiStyle.entries) {
                        InkMiniStyleOption(style, look.style == style, dark, cardAccent, look.plateInk, onStyle)
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = c.edge)
                // Single-row 深浅色 with right-aligned capsule segmented pill
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Symbols.Contrast, contentDescription = null, tint = c.fg2, modifier = Modifier.size(20.dp))
                    Text("深浅色", modifier = Modifier.weight(1f), fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = c.fg)
                    ModeSegmentRow(look.mode, onMode, compact = true)
                }
                HorizontalDivider(thickness = 1.dp, color = c.edge)
                // Compact 点缀色 swatches
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Symbols.Palette, contentDescription = null, tint = c.fg2, modifier = Modifier.size(20.dp))
                    Text("点缀色", fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = c.fg)
                    Spacer(Modifier.weight(1f))
                    Text(look.accent.label, fontSize = 12.5.sp, color = c.fg3)
                }
                val choices = AccentChoice.entries.filter { it != AccentChoice.Wallpaper || wallpaperAvailable }
                FlowRow(
                    Modifier.fillMaxWidth().padding(start = 50.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    choices.forEach { choice ->
                        CompactSwatch(choice, choice == look.accent, dark, onAccent)
                    }
                }
            }

            InkSettingsSectionLabel("连接")
            Column(
                Modifier
                    .padding(horizontal = 14.dp)
                    .fillMaxWidth()
                    .inkSurface(c.card, c.edge, 24.dp),
            ) {
                val relayAnchor = rememberAnchorBox()
                val primaryHost = relays.firstOrNull()?.host
                val relayStateText = when {
                    relays.isEmpty() -> "未配对"
                    relays.all { it.state is ConnectionState.Connected } -> "已连接"
                    relays.any { it.state is ConnectionState.Waiting } -> "重连中"
                    else -> "连接中"
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .captureAnchor(relayAnchor)
                        .listRowPress(PressRow.Panel) { onOpenRelay(relayAnchor.rect) }
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                        .testTag("settingsRelayRow"),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Symbols.Dns, contentDescription = null, tint = c.fg2, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("中转服务器", fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = c.fg)
                        if (primaryHost != null) {
                            Text(
                                if (relays.size > 1) "$primaryHost 等 ${relays.size} 台" else primaryHost,
                                fontFamily = MonoFamily,
                                fontSize = 12.sp,
                                color = c.fg2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(relayStateText, fontSize = 13.5.sp, color = if (relayStateText == "已连接") c.ok.takeIf { it != Color.Transparent } ?: c.fg2 else c.fg2)
                }

                HorizontalDivider(thickness = 1.dp, color = c.edge)
                val pairAnchor = rememberAnchorBox()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .captureAnchor(pairAnchor)
                        .listRowPress(PressRow.Panel) { onPairAt(pairAnchor.rect) }
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                        .testTag("settingsPairRow"),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Symbols.QrCodeScanner, contentDescription = null, tint = c.fg2, modifier = Modifier.size(20.dp))
                    Text("配对新的目标机", modifier = Modifier.weight(1f), fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = c.fg)
                    Icon(Symbols.ChevronRight, contentDescription = null, tint = c.fg3, modifier = Modifier.size(18.dp))
                }

                if (relays.isNotEmpty()) {
                    HorizontalDivider(thickness = 1.dp, color = c.edge)
                    val clearAnchor = rememberAnchorBox()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .captureAnchor(clearAnchor)
                            .listRowPress(PressRow.Danger) { onRequestClear(clearAnchor.rect) }
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                            .testTag("settingsClearPairingsRow"),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Symbols.Delete, contentDescription = null, tint = c.danger, modifier = Modifier.size(20.dp))
                        Text("清除全部配对", modifier = Modifier.weight(1f), fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = c.danger)
                    }
                }
            }

            InkSettingsSectionLabel("关于")
            Column(
                Modifier
                    .padding(horizontal = 14.dp)
                    .fillMaxWidth()
                    .inkSurface(c.card, c.edge, 24.dp),
            ) {
                val aboutAnchor = rememberAnchorBox()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .captureAnchor(aboutAnchor)
                        .listRowPress(PressRow.Panel) { onOpenAbout(aboutAnchor.rect) }
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                        .testTag("settingsAboutRow"),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Symbols.Description, contentDescription = null, tint = c.fg2, modifier = Modifier.size(20.dp))
                    Text("clauderc", modifier = Modifier.weight(1f), fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = c.fg)
                    Text(resolvedVersion, fontFamily = MonoFamily, fontSize = 13.sp, color = c.fg2)
                }
            }
        }

        InkTopBar(
            title = if (relays.isEmpty() && machine == null) "未配对" else machine?.name ?: "设置",
            online = machine?.online == true,
            showDot = machine != null,
            rightAction = InkTopRight.None,
            onOpenDrawer = onOpenDrawer,
            onPillClick = onOpenMachineStatus,
            onRightClick = {},
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun InkSettingsSectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 10.dp, bottom = 4.dp),
        fontSize = 12.5.sp,
        color = Clauderc.colors.fg3,
    )
}

@Composable
private fun RowScope.InkMiniStyleOption(
    style: UiStyle,
    selected: Boolean,
    dark: Boolean,
    cardAccent: Color?,
    plateInk: PlateInk,
    onPick: (UiStyle, Offset) -> Unit,
) {
    val c = Clauderc.colors
    var center by remember { mutableStateOf(Offset.Zero) }
    val press = remember { MutableInteractionSource() }
    val edge = previewPressEdge(press, selected)
    Column(
        Modifier
            .weight(1f)
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .pressShrink(press, PressKind.Preview)
            .clip(RoundedCornerShape(14.dp))
            .selectable(selected, press, null, role = Role.RadioButton) { onPick(style, center) }
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) c.fg else edge,
                    RoundedCornerShape(12.dp),
                )
                .padding(2.dp),
        ) {
            MiniPhone(style, dark, cardAccent, plateInk)
        }
        Text(
            style.label,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) c.fg else c.fg2,
            maxLines = 1,
        )
    }
}

@Composable
private fun ModeSegmentRow(
    picked: ThemeMode,
    onPick: (ThemeMode, Offset) -> Unit,
    compact: Boolean = false,
) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    val options = listOf(ThemeMode.System to "跟随系统", ThemeMode.Light to "浅色", ThemeMode.Dark to "深色")
    val shape = if (ui == UiStyle.Plate) androidx.compose.ui.graphics.RectangleShape else CircleShape
    Row(
        Modifier
            .then(if (compact) Modifier else Modifier.fillMaxWidth().padding(horizontal = rowInset(), vertical = 2.dp))
            .clip(shape)
            .background(if (ui == UiStyle.Plate) c.bg else c.surface2)
            .then(if (ui == UiStyle.Plate) Modifier.border(1.5.dp, c.fg) else Modifier)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (mode, label) ->
            val on = mode == picked
            var center by remember { mutableStateOf(Offset.Zero) }
            val press = remember { MutableInteractionSource() }
            val pillBg = when {
                !on -> Color.Transparent
                ui == UiStyle.Ink -> c.inverse
                ui == UiStyle.Plate -> c.fg
                else -> c.card
            }
            val pillFg = when {
                !on -> c.fg2
                ui == UiStyle.Ink -> c.onInverse
                ui == UiStyle.Plate -> c.bg
                else -> c.fg
            }
            Box(
                Modifier
                    .then(if (compact) Modifier else Modifier.weight(1f))
                    .onGloballyPositioned { center = it.boundsInRoot().center }
                    .pressShrink(press, PressKind.Segment)
                    .clip(shape)
                    .background(pillBg)
                    .selectable(on, press, controlPress(PressKind.Segment, if (on && ui == UiStyle.Ink) PressTone.Primary else PressTone.Normal, corner = if (ui == UiStyle.Plate) 0.dp else 100.dp), role = Role.RadioButton) { onPick(mode, center) }
                    .padding(horizontal = if (compact) 10.dp else 12.dp, vertical = if (compact) 5.dp else 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = if (compact) 12.5.sp else 13.sp,
                    fontFamily = if (ui == UiStyle.Plate) MonoFamily else null,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = pillFg,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CompactSwatch(choice: AccentChoice, selected: Boolean, dark: Boolean, onPick: (AccentChoice, Offset) -> Unit) {
    val ui = Clauderc.style
    val color = accentColor(choice, dark) ?: palette(ui, dark).accent
    val ring = if (ui == UiStyle.Tile) color else Clauderc.colors.fg
    val on = palette(ui, dark, color).onAccent
    var center by remember { mutableStateOf(Offset.Zero) }
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(36.dp)
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .semantics { contentDescription = choice.label }
            .pressShrink(press, PressKind.Swatch)
            .selectable(selected, press, indication = null, role = Role.RadioButton) { onPick(choice, center) },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(ring, radius = size.minDimension / 2f - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
            }
        }
        Box(Modifier.size(30.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            val icon = when {
                selected -> Symbols.Check
                choice == AccentChoice.Wallpaper -> Symbols.Wallpaper
                else -> null
            }
            if (icon != null) Icon(icon, contentDescription = null, tint = on, modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun rowInset() = when (Clauderc.style) {
    UiStyle.Clear -> Clauderc.spacing.sp1
    UiStyle.Tile, UiStyle.Ink -> Clauderc.spacing.sp2
    UiStyle.Plate -> Clauderc.spacing.sp1
}

@Composable
private fun ConnectionRows(
    relays: List<RelayLink>,
    onOpenRelay: (Rect?) -> Unit,
    onPair: (Rect?) -> Unit,
    onRequestClear: (Rect?) -> Unit,
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val inset = rowInset()
    Column(Modifier.fillMaxWidth()) {
        if (relays.isEmpty()) {
            val relayAnchor = rememberAnchorBox()
            Row(
                Modifier
                    .fillMaxWidth()
                    .captureAnchor(relayAnchor)
                    .listRowPress(PressRow.Panel) { onOpenRelay(relayAnchor.rect) }
                    .padding(horizontal = inset, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("中转服务器", fontSize = Clauderc.fontSizes.body, color = c.fg)
                    Text("尚未配对中转服务器", fontSize = Clauderc.fontSizes.caption, color = c.fg3)
                }
                Text("未配对", fontSize = Clauderc.fontSizes.caption, color = c.fg3)
            }
        } else {
            relays.forEachIndexed { idx, link ->
                if (idx > 0) HorizontalDivider(Modifier.padding(horizontal = inset), thickness = 1.dp, color = c.line)
                val relayAnchor = rememberAnchorBox()
                RelayStatusRow(
                    link,
                    Modifier
                        .captureAnchor(relayAnchor)
                        .listRowPress(PressRow.Panel) { onOpenRelay(relayAnchor.rect) }
                        .padding(horizontal = inset, vertical = 10.dp),
                )
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = inset), thickness = 1.dp, color = c.line)
        val pairAnchor = rememberAnchorBox()
        Row(
            Modifier
                .fillMaxWidth()
                .captureAnchor(pairAnchor)
                .listRowPress() { onPair(pairAnchor.rect) }
                .padding(horizontal = inset, vertical = 12.dp)
                .testTag("settingsPairRow"),
            horizontalArrangement = Arrangement.spacedBy(sp.sp3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Symbols.QrCodeScanner, contentDescription = null, tint = c.fg2, modifier = Modifier.size(20.dp))
            Text("配对新的目标机", Modifier.weight(1f), fontSize = Clauderc.fontSizes.body, color = c.fg)
            Icon(Symbols.ChevronRight, contentDescription = null, tint = c.fg3, modifier = Modifier.size(18.dp))
        }
        if (relays.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(horizontal = inset), thickness = 1.dp, color = c.line)
            val clearAnchor = rememberAnchorBox()
            Row(
                Modifier
                    .fillMaxWidth()
                    .captureAnchor(clearAnchor)
                    .listRowPress(PressRow.Danger) { onRequestClear(clearAnchor.rect) }
                    .padding(horizontal = inset, vertical = 12.dp)
                    .testTag("settingsClearPairingsRow"),
                horizontalArrangement = Arrangement.spacedBy(sp.sp3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Symbols.Delete, contentDescription = null, tint = c.danger, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("清除全部配对", fontSize = Clauderc.fontSizes.body, color = c.danger)
                    Text("这台手机会忘记所有中转服务器的凭证", fontSize = Clauderc.fontSizes.caption, color = c.fg3)
                }
            }
        }
    }
}

@Composable
private fun RelayStatusRow(link: RelayLink, modifier: Modifier = Modifier) {
    val c = Clauderc.colors
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("中转服务器", fontSize = Clauderc.fontSizes.body, color = c.fg)
            Text(link.host, fontSize = Clauderc.fontSizes.monoSm, fontFamily = MonoFamily, color = c.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when (val conn = link.state) {
            ConnectionState.Connected -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusGlyph(Glyph.On)
                Text("已连接", fontSize = Clauderc.fontSizes.caption, color = c.ok.takeIf { it != Color.Transparent } ?: c.fg2)
            }
            is ConnectionState.Waiting -> {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(conn) {
                    while (true) {
                        now = System.currentTimeMillis()
                        delay(250)
                    }
                }
                val left = ceil((conn.retryAtMillis - now).coerceAtLeast(0) / 1000.0).toInt()
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 12.dp, color = c.fg2)
                    Text(if (left > 0) "重连中 ${left}s" else "重连中", fontSize = Clauderc.fontSizes.caption, color = c.fg2)
                }
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner(size = 12.dp, color = c.fg2)
                Text("连接中", fontSize = Clauderc.fontSizes.caption, color = c.fg2)
            }
        }
    }
}

@Composable
private fun PlateNavToggle(picked: PlateNavStyle, onPick: (PlateNavStyle) -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    Row(
        Modifier.fillMaxWidth().padding(horizontal = sp.sp1, vertical = sp.sp1),
        horizontalArrangement = Arrangement.spacedBy(sp.sp2),
    ) {
        for (option in PlateNavStyle.entries) {
            val selected = option == picked
            val press = remember { MutableInteractionSource() }
            val sweep = if (selected) 0f else pressProgress(press, PressKind.Chip, sweep = true).value
            Box(
                Modifier
                    .weight(1f)
                    .pressShrink(press, if (selected) PressKind.Stamp else PressKind.Chip)
                    .then(if (selected) Modifier.plateStamp(c.fg, c.bg, c.fg, press) else Modifier.background(c.bg))
                    .plateSweepFill(sweep, c.fg, PlateSweep.LeftToRight)
                    .border(1.5.dp, c.fg)
                    .selectable(selected, press, null, role = Role.RadioButton) { onPick(option) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.label,
                    fontSize = 13.sp,
                    fontFamily = MonoFamily,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) c.bg else lerp(c.fg, c.bg, sweep),
                )
            }
        }
    }
}

/** .swrow: a title over a grey line, the whole row a switch. */
@Composable
private fun SwitchRow(title: String, text: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val ui = Clauderc.style
    val press = remember { MutableInteractionSource() }
    val inset = rowInset()
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(on, press, null, role = Role.Switch, onValueChange = onChange)
            .padding(start = inset, end = inset, top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(sp.sp4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = Clauderc.fontSizes.body, color = c.fg)
            Text(
                text,
                fontSize = if (ui == UiStyle.Plate) 11.5.sp else Clauderc.fontSizes.caption,
                fontFamily = if (ui == UiStyle.Plate) MonoFamily else null,
                lineHeight = 1.45.em,
                color = c.fg3,
            )
        }
        Switch(on, press)
    }
}

@Composable
private fun SettingsGroup(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    val sp = Clauderc.spacing
    val c = Clauderc.colors
    val ui = Clauderc.style
    val outer = when (ui) {
        UiStyle.Tile -> Modifier
            .padding(start = sp.sp4, end = sp.sp4, bottom = sp.sp3)
            .tileSurface(c.card, CardCorner)
            .padding(start = sp.sp2, end = sp.sp2, top = sp.sp4, bottom = sp.sp4)
        UiStyle.Ink -> Modifier
            .padding(start = sp.sp4, end = sp.sp4, bottom = sp.sp3)
            .inkSurface(c.card, c.edge, CardCorner)
            .padding(start = sp.sp2, end = sp.sp2, top = sp.sp4, bottom = sp.sp4)
        UiStyle.Plate -> Modifier
            .padding(start = sp.sp4, end = sp.sp4, bottom = sp.sp4)
            .topLine(c.fg, PlateRule)
        UiStyle.Clear -> Modifier.padding(horizontal = sp.page - sp.sp1)
    }
    Column(outer.fillMaxWidth()) {
        if (ui == UiStyle.Plate) {
            Text(title, Modifier.padding(top = sp.sp3, bottom = sp.sp3), fontSize = 12.sp, fontFamily = MonoFamily, fontWeight = FontWeight.Bold, color = c.fg)
        } else {
            Row(
                Modifier.padding(start = sp.sp2, top = if (Clauderc.look.cards) 0.dp else sp.sp5, bottom = sp.sp3),
                horizontalArrangement = Arrangement.spacedBy(sp.sp2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = c.fg2, modifier = Modifier.size(20.dp))
                Text(title, fontSize = Clauderc.fontSizes.label, fontWeight = if (Clauderc.look.cards) FontWeight.SemiBold else FontWeight.Medium, color = c.fg)
            }
        }
        content()
    }
}

@Composable
private fun OptionRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
private fun RowScope.Option(
    label: String,
    selected: Boolean,
    onClick: (Offset) -> Unit,
    preview: @Composable () -> Unit,
) {
    val c = Clauderc.colors
    val plate = Clauderc.style == UiStyle.Plate
    val corner = miniCorner(Clauderc.style)
    var center by remember { mutableStateOf(Offset.Zero) }
    val press = remember { MutableInteractionSource() }
    val edge = previewPressEdge(press, selected)
    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { center = it.boundsInRoot().center }
                .pressShrink(press, PressKind.Preview)
                .clip(RoundedCornerShape(if (plate) 0.dp else Clauderc.radii.md))
                .selectable(selected, press, null, role = Role.RadioButton) { onClick(center) }
                .padding(Clauderc.spacing.sp1),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Clauderc.spacing.sp2),
        ) {
            val frame = Modifier.border(
                if (selected) 2.dp else 1.dp,
                when {
                    selected && plate -> c.fg
                    selected -> c.accent
                    else -> edge
                },
                RoundedCornerShape(corner),
            )
            Box(Modifier.fillMaxWidth(0.92f).aspectRatio(0.72f).then(frame)) { preview() }
            Text(
                label,
                fontSize = Clauderc.fontSizes.label,
                fontFamily = if (plate) MonoFamily else null,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) c.fg else c.fg2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun miniCorner(style: UiStyle) = when (style) {
    UiStyle.Plate -> 0.dp
    UiStyle.Tile, UiStyle.Ink -> 14.dp
    UiStyle.Clear -> 12.dp
}

@Composable
private fun previewPressEdge(source: MutableInteractionSource, selected: Boolean): Color {
    val c = Clauderc.colors
    val ui = Clauderc.style
    val hit = if (selected) 0f else pressProgress(source, PressKind.Preview).value
    val edge = if (ui == UiStyle.Ink) c.edge else c.line
    return lerp(edge, when (ui) {
        UiStyle.Tile -> c.line
        UiStyle.Ink, UiStyle.Clear -> c.fg3
        UiStyle.Plate -> c.fg
    }, hit)
}

@Composable
private fun MiniPhone(
    style: UiStyle,
    dark: Boolean,
    cardAccent: Color?,
    plateInk: PlateInk,
    splitDark: Boolean = false,
) {
    val light = palette(style, dark, cardAccent, plateInk)
    val night = palette(style, true, cardAccent, plateInk)
    Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(miniCorner(style))).testTag("mini")) {
        drawMini(style, light)
        if (splitDark) {
            val half = Path().apply {
                moveTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            clipPath(half) { drawMini(style, night) }
        }
    }
}

private fun DrawScope.drawMini(style: UiStyle, p: ClaudercColors) {
    val u = size.width / 84f
    drawRect(p.bg)
    val left = 8 * u
    val width = size.width - 16 * u
    var y = 12 * u
    val plate = style == UiStyle.Plate
    val cards = style == UiStyle.Tile || style == UiStyle.Ink

    // Title bar
    drawRoundRect(
        p.fg.copy(alpha = if (plate) 1f else 0.85f),
        Offset(left, y),
        Size(width * 0.50f, 7 * u),
        CornerRadius(if (plate) 0f else 3.5f * u),
    )
    if (plate) drawRect(p.fg, Offset(left, y + 10 * u), Size(width, 1.5f * u))
    y += 16 * u

    // Rows / bento cards
    val rowH = (if (cards) 16 else 14) * u
    repeat(3) { i ->
        when (style) {
            UiStyle.Tile -> drawRoundRect(p.card, Offset(left, y), Size(width, rowH), CornerRadius(6 * u))
            UiStyle.Ink -> {
                drawRoundRect(p.card, Offset(left, y), Size(width, rowH), CornerRadius(6 * u))
                drawRoundRect(p.edge, Offset(left, y), Size(width, rowH), CornerRadius(6 * u), style = Stroke(0.7f * u))
            }
            UiStyle.Plate -> drawRect(p.line, Offset(left, y + rowH - u), Size(width, u))
            UiStyle.Clear -> drawRect(p.line, Offset(left, y + rowH - u), Size(width, 0.8f * u))
        }
        val dotColor = if (i == 0) p.accent else p.fg3
        drawCircle(dotColor, 2.6f * u, Offset(left + 7 * u, y + rowH / 2))
        drawRoundRect(p.fg.copy(alpha = 0.45f), Offset(left + 14 * u, y + rowH / 2 - 2 * u), Size(width * 0.55f, 4 * u), CornerRadius(if (plate) 0f else 2 * u))
        y += rowH + 5 * u
    }

    // Bottom nav bar matching each style
    val navTop = size.height - 19 * u
    when (style) {
        UiStyle.Ink -> {
            val pillW = width * 0.72f
            drawRoundRect(p.float, Offset(left, navTop), Size(pillW, 13 * u), CornerRadius(6.5f * u))
            drawRoundRect(p.edge, Offset(left, navTop), Size(pillW, 13 * u), CornerRadius(6.5f * u), style = Stroke(0.7f * u))
            drawCircle(p.accent, 6.5f * u, Offset(left + width - 6.5f * u, navTop + 6.5f * u))
        }
        UiStyle.Tile -> {
            val navW = size.width * 0.68f
            val navL = (size.width - navW) / 2f
            drawRoundRect(p.float, Offset(navL, navTop), Size(navW, 13 * u), CornerRadius(6.5f * u))
            drawRoundRect(p.accent.copy(alpha = 0.22f), Offset(navL + 3 * u, navTop + 2.5f * u), Size(navW * 0.34f, 8 * u), CornerRadius(4 * u))
        }
        UiStyle.Clear -> {
            val navW = size.width * 0.76f
            val navL = (size.width - navW) / 2f
            drawRoundRect(p.pane, Offset(navL, navTop), Size(navW, 13 * u), CornerRadius(6.5f * u))
            drawRoundRect(p.edge, Offset(navL, navTop), Size(navW, 13 * u), CornerRadius(6.5f * u), style = Stroke(0.7f * u))
            drawRoundRect(p.lens, Offset(navL + 3 * u, navTop + 2.5f * u), Size(navW * 0.32f, 8 * u), CornerRadius(4 * u))
        }
        UiStyle.Plate -> {
            val navW = width
            drawRect(p.fg, Offset(left + 2 * u, navTop + 2 * u), Size(navW, 13 * u))
            drawRect(p.bg, Offset(left, navTop), Size(navW, 13 * u))
            drawRect(p.fg, Offset(left, navTop), Size(navW, 13 * u), style = Stroke(1.3f * u))
            drawRect(p.fg, Offset(left + 2 * u, navTop + 2 * u), Size(navW / 3f - 2 * u, 9 * u))
        }
    }
}

/** Accent swatches for 浮墨 / 素卡 / 清玻, five to a row; picked swatch gets a ring and tick. */
@Composable
private fun Swatches(choices: List<AccentChoice>, picked: AccentChoice, dark: Boolean, onPick: (AccentChoice, Offset) -> Unit) {
    val sp = Clauderc.spacing
    Column(Modifier.fillMaxWidth()) {
        choices.chunked(5).forEachIndexed { r, line ->
            if (r > 0) Spacer(Modifier.height(sp.sp3))
            Row(Modifier.fillMaxWidth()) {
                line.forEach { choice -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Swatch(choice, choice == picked, dark, onPick) } }
                repeat(5 - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text(
            picked.label,
            modifier = Modifier.padding(start = sp.sp2, top = sp.sp3),
            fontSize = Clauderc.fontSizes.caption,
            color = Clauderc.colors.fg3,
        )
    }
}

@Composable
private fun Swatch(choice: AccentChoice, selected: Boolean, dark: Boolean, onPick: (AccentChoice, Offset) -> Unit) {
    val ui = Clauderc.style
    val color = accentColor(choice, dark) ?: palette(ui, dark).accent
    val on = palette(ui, dark, color).onAccent
    var center by remember { mutableStateOf(Offset.Zero) }
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(52.dp)
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .semantics { contentDescription = choice.label }
            .pressShrink(press, PressKind.Swatch)
            .selectable(selected, press, indication = null, role = Role.RadioButton) { onPick(choice, center) },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().testTag("swatchFace"), contentAlignment = Alignment.Center) {
            if (selected) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = 2.dp.toPx()
                    drawCircle(color, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(w))
                }
            }
            Box(Modifier.size(40.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                val icon = when {
                    selected -> Symbols.Check
                    choice == AccentChoice.Wallpaper -> Symbols.Wallpaper
                    else -> null
                }
                if (icon != null) Icon(icon, contentDescription = null, tint = on, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** 印版's eight selectable printing inks, four per row. */
@Composable
private fun PlateInkSwatches(picked: PlateInk, dark: Boolean, onPick: (PlateInk, Offset) -> Unit) {
    val sp = Clauderc.spacing
    val c = Clauderc.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sp.sp2)) {
        PlateInk.entries.chunked(4).forEach { line ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sp.sp2)) {
                for (ink in line) {
                    val selected = ink == picked
                    val swatch = Color(0xFF000000 or if (dark) ink.darkInk else ink.lightInk)
                    val press = remember { MutableInteractionSource() }
                    var center by remember { mutableStateOf(Offset.Zero) }
                    Row(
                        Modifier
                            .weight(1f)
                            .onGloballyPositioned { center = it.boundsInRoot().center }
                            .pressShrink(press, PressKind.Swatch)
                            .background(if (selected) c.fg else c.bg)
                            .border(if (selected) 2.dp else 1.dp, c.fg)
                            .semantics { contentDescription = ink.label }
                            .selectable(selected, press, if (selected) null else controlPress(PressKind.Swatch), role = Role.RadioButton) { onPick(ink, center) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(12.dp).background(swatch).border(1.dp, if (selected) c.bg else c.fg))
                        Text(
                            ink.label,
                            fontSize = 12.sp,
                            fontFamily = MonoFamily,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) c.bg else c.fg,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
