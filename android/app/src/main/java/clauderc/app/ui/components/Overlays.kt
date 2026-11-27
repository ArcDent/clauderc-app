package clauderc.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.listRowPress
import clauderc.app.ui.theme.PressRow
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.Lift
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.Symbols
import clauderc.app.ui.theme.styled
import kotlinx.coroutines.delay

/**
 * Style-dispatched sheet overlay:
 * - 浮墨: [MorphOverlay] blooming elastically from [anchor] into a 30dp floating panel.
 * - 印版 / 清玻 / 素卡: style-specific modal sheet (deepened in their respective style steps).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaudercSheet(
    onDismiss: () -> Unit,
    anchor: Rect? = null,
    fromTop: Boolean = false,
    tall: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    if (ui == UiStyle.Ink) {
        MorphOverlay(onDismiss = onDismiss, anchor = anchor, fromTop = fromTop, tall = tall) {
            Column(Modifier.fillMaxWidth().padding(top = Clauderc.spacing.sp4, bottom = Clauderc.spacing.sp4), content = content)
        }
        return
    }
    if (ui == UiStyle.Plate) {
        PlateSlipPanel(onDismiss = onDismiss, content = content)
        return
    }
    if (ui == UiStyle.Clear) {
        ClearDropPanel(onDismiss = onDismiss, content = content)
        return
    }
    if (ui == UiStyle.Tile) {
        TileBottomSheet(onDismiss = onDismiss, content = content)
        return
    }
    val corner = when (ui) {
        UiStyle.Plate -> 0.dp
        UiStyle.Tile -> 32.dp
        UiStyle.Clear -> Clauderc.radii.lg
        UiStyle.Ink -> 30.dp
    }
    val shape = if (ui == UiStyle.Plate) RectangleShape else RoundedCornerShape(topStart = corner, topEnd = corner)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = if (ui == UiStyle.Clear) c.pane.compositeOver(c.sheet) else c.sheet,
        contentColor = c.fg,
        scrimColor = c.scrim,
        shape = shape,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    if (ui == UiStyle.Plate) Modifier.drawBehind {
                        val w = 2.dp.toPx()
                        drawRect(c.fg, size = Size(w, size.height))
                        drawRect(c.fg, topLeft = androidx.compose.ui.geometry.Offset(size.width - w, 0f), size = Size(w, size.height))
                    } else Modifier,
                )
                .verticalScroll(rememberScrollState())
                .padding(bottom = Clauderc.spacing.sp5)
                .navigationBarsPadding(),
        ) {
            CompositionLocalProvider(LocalOverlayActions provides true) { content() }
        }
    }
}

@Composable
private fun SheetHandle() {
    val c = Clauderc.colors
    val ui = Clauderc.style
    Box(
        Modifier.fillMaxWidth().drawBehind {
            when (ui) {
                UiStyle.Plate -> {
                    val w = 2.dp.toPx()
                    drawRect(c.fg, size = Size(size.width, w))
                    drawRect(c.fg, size = Size(w, size.height))
                    drawRect(c.fg, topLeft = androidx.compose.ui.geometry.Offset(size.width - w, 0f), size = Size(w, size.height))
                }
                UiStyle.Clear -> drawRect(c.rim, size = Size(size.width, 1.dp.toPx()))
                UiStyle.Ink -> drawRect(c.edge, size = Size(size.width, 1.dp.toPx()))
                UiStyle.Tile -> Unit
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .padding(top = Clauderc.spacing.sp3, bottom = Clauderc.spacing.sp5)
                .size(if (ui == UiStyle.Plate) 36.dp else 32.dp, if (ui == UiStyle.Plate) 3.dp else 4.dp)
                .background(if (ui == UiStyle.Plate) c.fg else c.surface2, if (ui == UiStyle.Plate) RectangleShape else RoundedCornerShape(2.dp)),
        )
    }
}

/** True for the action buttons of a sheet or dialog. */
internal val LocalOverlayActions = staticCompositionLocalOf { false }

/** .sheet-head */
@Composable
fun SheetHead(title: String, text: String? = null, textContent: (@Composable () -> Unit)? = null) {
    val sp = Clauderc.spacing
    val c = Clauderc.colors
    val ui = Clauderc.style
    val slipRule = LocalPlateSlipLine.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(
                start = if (ui == UiStyle.Plate) 16.dp else sp.page,
                end = if (ui == UiStyle.Plate) 16.dp else sp.page,
                bottom = sp.sp3,
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            fontSize = if (ui == UiStyle.Plate) 18.sp else Clauderc.fontSizes.headline,
            fontWeight = if (ui == UiStyle.Plate) FontWeight.ExtraBold else FontWeight.SemiBold,
            lineHeight = 1.3.em,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (ui == UiStyle.Plate) {
                        Modifier
                            .drawBehind {
                                val f = (slipRule?.invoke() ?: 1f).coerceIn(0f, 1f)
                                if (f > 0f) {
                                    val h = 2.dp.toPx()
                                    drawRect(c.fg, androidx.compose.ui.geometry.Offset(0f, size.height - h), Size(size.width * f, h))
                                }
                            }
                            .padding(bottom = 10.dp)
                    } else {
                        Modifier
                    },
                )
                .semantics { heading() },
        )
        if (text != null) Text(text, fontSize = Clauderc.fontSizes.label, color = c.fg2, lineHeight = 1.6.em)
        textContent?.invoke()
    }
}

/** .sheet-sec */
@Composable
fun SheetSection(text: String) {
    val sp = Clauderc.spacing
    val ui = Clauderc.style
    Text(
        text,
        modifier = Modifier.padding(start = sp.page, end = sp.page, top = sp.sp4, bottom = sp.sp1),
        fontSize = Clauderc.fontSizes.caption,
        fontWeight = if (ui == UiStyle.Plate) FontWeight.ExtraBold else FontWeight.Medium,
        fontFamily = if (ui == UiStyle.Plate) MonoFamily else null,
        color = Clauderc.colors.fg3,
    )
}

/** .mi: a 56dp menu row with optional icon, second line and check mark. */
@Composable
fun MenuItem(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    supporting: String? = null,
    supportingMono: Boolean = false,
    mono: Boolean = false,
    danger: Boolean = false,
    checked: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val ui = Clauderc.style
    val fg = if (danger) c.danger else if (muted) c.fg2 else c.fg
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (ui == UiStyle.Plate) {
                    Modifier
                        .padding(horizontal = 16.dp)
                        .drawBehind {
                            val h = 1.dp.toPx()
                            drawRect(c.line, androidx.compose.ui.geometry.Offset(0f, size.height - h), Size(size.width, h))
                        }
                } else {
                    Modifier
                },
            )
            .heightIn(min = if (ui == UiStyle.Plate) 52.dp else 56.dp)
            .listRowPress(if (danger) PressRow.Danger else PressRow.Panel, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (ui == UiStyle.Plate) 4.dp else sp.page, vertical = sp.sp2),
        horizontalArrangement = Arrangement.spacedBy(if (ui == UiStyle.Plate) 12.dp else sp.sp4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon.styled, contentDescription = null, tint = if (danger) c.danger else c.fg2)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text,
                color = if (enabled) fg else c.fg3,
                fontSize = if (mono) 15.sp else if (ui == UiStyle.Plate) 15.sp else Clauderc.fontSizes.body,
                fontWeight = if (ui == UiStyle.Plate) FontWeight.ExtraBold else FontWeight.Normal,
                fontFamily = if (mono) MonoFamily else null,
            )
            if (supporting != null) {
                Text(
                    supporting,
                    color = if (ui == UiStyle.Plate) c.fg2 else c.fg3,
                    fontSize = if (supportingMono) 11.sp else Clauderc.fontSizes.caption,
                    fontFamily = if (supportingMono || ui == UiStyle.Plate) MonoFamily else null,
                    lineHeight = 1.45.em,
                    maxLines = if (supportingMono) 1 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (checked) Icon(Symbols.Check, contentDescription = "当前", tint = if (ui == UiStyle.Plate) c.fg else c.accent)
    }
}

/** .dialog: style-dispatched confirmation/form dialog (浮墨 morphs from [anchor] into a floating panel). */
@Composable
fun ClaudercDialog(
    onDismiss: () -> Unit,
    title: String,
    text: String? = null,
    anchor: Rect? = null,
    fromTop: Boolean = false,
    body: (@Composable ColumnScope.() -> Unit)? = null,
    dismissEnabled: Boolean = true,
    buttons: @Composable RowScope.() -> Unit,
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val ui = Clauderc.style
    if (ui == UiStyle.Ink) {
        MorphOverlay(onDismiss = onDismiss, anchor = anchor, fromTop = fromTop, dismissEnabled = dismissEnabled) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = sp.sp6, end = sp.sp6, top = sp.sp5, bottom = sp.sp4),
                verticalArrangement = Arrangement.spacedBy(sp.sp4),
            ) {
                Text(
                    title,
                    fontSize = Clauderc.fontSizes.headline,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 1.3.em,
                    color = c.fg,
                    modifier = Modifier.semantics { heading() },
                )
                if (text != null) Text(text, fontSize = Clauderc.fontSizes.label, lineHeight = 1.65.em, color = c.fg2)
                body?.invoke(this)
                Row(
                    Modifier.fillMaxWidth().padding(top = sp.sp2),
                    horizontalArrangement = Arrangement.spacedBy(sp.sp1, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = buttons,
                )
            }
        }
        return
    }
    if (ui == UiStyle.Plate) {
        PlateSlipPanel(onDismiss = onDismiss, tag = "plateDialogSlip", dismissEnabled = dismissEnabled) {
            SheetHead(title, text)
            if (body != null) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(sp.sp3),
                    content = body,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = sp.sp3, bottom = sp.sp1),
                horizontalArrangement = Arrangement.spacedBy(sp.sp2, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = buttons,
            )
        }
        return
    }
    if (ui == UiStyle.Clear) {
        ClearDropPanel(onDismiss = onDismiss, tag = "clearDialogDrop", dismissEnabled = dismissEnabled) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = sp.page, vertical = sp.sp2),
                verticalArrangement = Arrangement.spacedBy(sp.sp3),
            ) {
                Text(
                    title,
                    fontSize = Clauderc.fontSizes.headline,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 1.3.em,
                    color = c.fg,
                    modifier = Modifier.semantics { heading() },
                )
                if (text != null) Text(text, fontSize = Clauderc.fontSizes.label, lineHeight = 1.65.em, color = c.fg2)
                body?.invoke(this)
                Row(
                    Modifier.fillMaxWidth().padding(top = sp.sp1),
                    horizontalArrangement = Arrangement.spacedBy(sp.sp1, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = buttons,
                )
            }
        }
        return
    }
    if (ui == UiStyle.Tile) {
        TileBottomSheet(onDismiss = onDismiss, tag = "tileDialogSheet", dismissEnabled = dismissEnabled) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = sp.page, vertical = sp.sp2),
                verticalArrangement = Arrangement.spacedBy(sp.sp3),
            ) {
                Text(
                    title,
                    fontSize = Clauderc.fontSizes.headline,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 1.3.em,
                    color = c.fg,
                    modifier = Modifier.semantics { heading() },
                )
                if (text != null) Text(text, fontSize = Clauderc.fontSizes.label, lineHeight = 1.65.em, color = c.fg2)
                body?.invoke(this)
                Row(
                    Modifier.fillMaxWidth().padding(top = sp.sp1),
                    horizontalArrangement = Arrangement.spacedBy(sp.sp1, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = buttons,
                )
            }
        }
        return
    }
    val surface = when (ui) {
        UiStyle.Plate -> Modifier.plateBox(c.sheet, c.fg, 2.dp)
        UiStyle.Ink -> Modifier.inkSurface(c.sheet, c.edge, 28.dp)
        UiStyle.Tile -> Modifier.tileSurface(c.sheet, 26.dp)
        UiStyle.Clear -> Modifier.clip(RoundedCornerShape(Clauderc.radii.lg)).background(c.pane.compositeOver(c.sheet)).topHighlight(c.rim)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = sp.sp6)
                .fillMaxWidth()
                .then(surface)
                .padding(start = sp.sp6, end = sp.sp6, top = sp.sp6, bottom = sp.sp4),
            verticalArrangement = Arrangement.spacedBy(sp.sp4),
        ) {
            Text(
                title,
                fontSize = Clauderc.fontSizes.headline,
                fontWeight = if (ui == UiStyle.Plate) FontWeight.ExtraBold else FontWeight.SemiBold,
                lineHeight = 1.3.em,
                color = c.fg,
                modifier = Modifier.semantics { heading() },
            )
            if (text != null) Text(text, fontSize = Clauderc.fontSizes.label, lineHeight = 1.65.em, color = c.fg2)
            body?.invoke(this)
            CompositionLocalProvider(LocalOverlayActions provides true) {
                Row(
                    Modifier.fillMaxWidth().padding(top = sp.sp2),
                    horizontalArrangement = Arrangement.spacedBy(if (ui == UiStyle.Plate) sp.sp2 else sp.sp1, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = buttons,
                )
            }
        }
    }
}

/** .pmenu: dropdown popup menu growing out from under its anchor. */
@Composable
fun PopMenu(
    open: Boolean,
    onDismiss: () -> Unit,
    place: PopupPositionProvider? = null,
    origin: () -> TransformOrigin = { TransformOrigin(1f, 0f) },
    width: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = open
    if (!shown.currentState && !shown.targetState && shown.isIdle) return
    val c = Clauderc.colors
    val ui = Clauderc.style
    val density = LocalDensity.current
    val belowEnd = remember(density) { BelowEnd(with(density) { 4.dp.roundToPx() }, with(density) { 2.dp.roundToPx() }) }
    Popup(popupPositionProvider = place ?: belowEnd, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(shown, enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
            val scale by transition.animateFloat({ if (targetState == EnterExitState.Visible) Motion.enter() else Motion.exit() }, label = "popMenuScale") {
                if (it == EnterExitState.Visible) 1f else 0.86f
            }
            val surface = when (ui) {
                UiStyle.Plate -> Modifier.plateBox(c.bg, c.fg, 2.dp)
                UiStyle.Ink -> Modifier.shadow(16.dp, RoundedCornerShape(22.dp)).inkSurface(c.float, c.edge, 22.dp).padding(vertical = 6.dp)
                UiStyle.Tile -> Modifier.shadow(14.dp, RoundedCornerShape(20.dp)).tileSurface(c.sheet, 20.dp).padding(vertical = 6.dp)
                UiStyle.Clear -> Modifier.shadow(18.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(c.pane.compositeOver(c.sheet)).topHighlight(c.rim).padding(vertical = 6.dp)
            }
            Column(
                Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = origin()
                    }
                    .testTag("popMenu")
                    .then(if (width != null) Modifier.width(width) else Modifier.widthIn(min = 188.dp).width(IntrinsicSize.Max))
                    .then(surface),
                content = content,
            )
        }
    }
}

/** A row of [PopMenu]. */
@Composable
fun PopMenuItem(text: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true, first: Boolean = false) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val ui = Clauderc.style
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (ui == UiStyle.Plate && !first) Modifier.drawBehind { drawRect(c.line, size = Size(size.width, 1.dp.toPx())) } else Modifier)
            .heightIn(min = 48.dp)
            .listRowPress(PressRow.Panel, enabled = enabled, onClick = onClick)
            .padding(start = sp.sp4, end = sp.sp6, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(sp.sp3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon.styled, contentDescription = null, tint = if (enabled) c.fg2 else c.fg3)
        Text(
            text,
            color = if (enabled) c.fg else c.fg3,
            fontSize = if (ui == UiStyle.Plate) 14.sp else Clauderc.fontSizes.body,
            fontFamily = if (ui == UiStyle.Plate) MonoFamily else null,
            fontWeight = if (ui == UiStyle.Plate) FontWeight.Bold else null,
            maxLines = 1,
        )
    }
}

private class BelowEnd(private val inset: Int, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - inset - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.bottom + gap).coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

val LocalSnack = staticCompositionLocalOf<SnackState?> { null }

@Stable
class SnackState {
    var message by mutableStateOf<String?>(null)
        private set
    private var serial by mutableStateOf(0)

    fun show(text: String) {
        message = text
        serial++
    }

    fun dismiss() {
        message = null
    }

    internal val key get() = serial
}

/** .snackbar / .toast: inverse pill (or square framed stamp in 印版), auto-dismissed after 4s. */
@Composable
fun SnackHost(state: SnackState, modifier: Modifier = Modifier, bottom: Dp = Clauderc.spacing.sp3, onCamera: Boolean = false) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val ui = Clauderc.style
    val bg = if (onCamera) OnCameraSnack else c.inverse
    LaunchedEffect(state.key) {
        if (state.message != null) {
            delay(4000)
            state.dismiss()
        }
    }
    var shown by remember { mutableStateOf("") }
    state.message?.let { shown = it }
    Lift(visible = state.message != null, modifier = modifier, distance = 16.dp) {
        Box(
            Modifier
                .padding(start = sp.sp3, end = sp.sp3, bottom = bottom)
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .then(
                    when (ui) {
                        UiStyle.Plate -> Modifier.plateBox(bg, c.fg, 1.5.dp)
                        UiStyle.Ink, UiStyle.Tile -> Modifier.clip(RoundedCornerShape(16.dp)).background(bg)
                        UiStyle.Clear -> Modifier.clip(RoundedCornerShape(4.dp)).background(bg)
                    },
                )
                .padding(horizontal = sp.sp4, vertical = sp.sp3),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                shown,
                color = if (onCamera) OnCameraSnackText else c.onInverse,
                fontSize = Clauderc.fontSizes.label,
                fontWeight = if (ui == UiStyle.Plate) FontWeight.Bold else FontWeight.Normal,
                lineHeight = 1.45.em,
            )
        }
    }
}

private val OnCameraSnack = Color(0xFFE6E6E6)
private val OnCameraSnackText = Color(0xFF111111)
