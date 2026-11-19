package clauderc.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.Lift
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Symbols
import clauderc.app.ui.theme.styled
import kotlinx.coroutines.delay

/** .sheet: a bottom sheet with a short handle and 28dp top corners. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaudercSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    val corner = when (ui) {
        UiStyle.Grid -> 10.dp
        UiStyle.Sticker -> 32.dp
        UiStyle.Clear -> Clauderc.radii.lg
    }
    val shape = RoundedCornerShape(topStart = corner, topEnd = corner)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // 清玻's glass tint over the sheet colour: the sheet sits in its own window, where the page behind cannot be blurred.
        containerColor = if (ui == UiStyle.Clear) c.pane.compositeOver(c.sheet) else c.sheet,
        contentColor = c.fg,
        scrimColor = c.scrim,
        shape = shape,
        // The sheet's outline is drawn from its handle, which moves with the sheet. Drawn from the sheet's
        // modifier it would stay where the sheet starts: ModalBottomSheet applies that modifier before it
        // moves the sheet into place, so the outline's lower edge ran across the list.
        dragHandle = { SheetHandle(corner) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Clauderc.spacing.sp5)
                .navigationBarsPadding(),
        ) {
            CompositionLocalProvider(LocalOverlayActions provides true) { content() }
        }
    }
}

/**
 * The sheet's short handle, as wide as the sheet so it can draw the sheet's edge: 贴纸's 2dp ink outline
 * along the top and down both sides with no bottom edge (border-bottom: 0), clipped by the sheet's own
 * shape where it ends; 清玻's 1dp rim along the top.
 */
@Composable
private fun SheetHandle(corner: Dp) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    Box(
        Modifier.fillMaxWidth().drawBehind {
            when (ui) {
                UiStyle.Sticker -> {
                    val w = 2.dp.toPx()
                    val half = w / 2
                    val r = corner.toPx() - half
                    // Far past the handle: the sheet clips the sides where it ends.
                    val far = size.height + 100_000f
                    val edge = Path().apply {
                        moveTo(half, far)
                        lineTo(half, half + r)
                        arcTo(Rect(half, half, half + 2 * r, half + 2 * r), 180f, 90f, false)
                        lineTo(size.width - half - r, half)
                        arcTo(Rect(size.width - half - 2 * r, half, size.width - half, half + 2 * r), 270f, 90f, false)
                        lineTo(size.width - half, far)
                    }
                    drawPath(edge, c.ink, style = Stroke(w))
                }
                UiStyle.Clear -> drawRect(c.rim, size = Size(size.width, 1.dp.toPx()))
                UiStyle.Grid -> Unit
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .padding(top = Clauderc.spacing.sp3, bottom = Clauderc.spacing.sp5)
                .size(32.dp, 4.dp)
                .background(c.surface2, RoundedCornerShape(2.dp)),
        )
    }
}

/** True for the action buttons of a sheet or dialog, which 贴纸 draws as ink-outlined stickers. */
internal val LocalOverlayActions = staticCompositionLocalOf { false }

/** .sheet-head */
@Composable
fun SheetHead(title: String, text: String? = null, textContent: (@Composable () -> Unit)? = null) {
    val sp = Clauderc.spacing
    Column(
        Modifier.fillMaxWidth().padding(start = sp.page, end = sp.page, bottom = sp.sp3),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, fontSize = Clauderc.fontSizes.headline, lineHeight = 1.3.em, modifier = Modifier.semantics { heading() })
        if (text != null) Text(text, fontSize = Clauderc.fontSizes.label, color = Clauderc.colors.fg2, lineHeight = 1.6.em)
        textContent?.invoke()
    }
}

/** .sheet-sec */
@Composable
fun SheetSection(text: String) {
    val sp = Clauderc.spacing
    Text(
        text,
        modifier = Modifier.padding(start = sp.page, end = sp.page, top = sp.sp4, bottom = sp.sp1),
        fontSize = Clauderc.fontSizes.caption,
        fontWeight = FontWeight.Medium,
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
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val fg = if (danger) c.danger else c.fg
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = sp.page, vertical = sp.sp2),
        horizontalArrangement = Arrangement.spacedBy(sp.sp4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon.styled, contentDescription = null, tint = if (danger) c.danger else c.fg2)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text,
                color = if (enabled) fg else c.fg3,
                fontSize = if (mono) 15.sp else Clauderc.fontSizes.body,
                fontFamily = if (mono) MonoFamily else null,
            )
            if (supporting != null) {
                Text(
                    supporting,
                    color = c.fg3,
                    fontSize = if (supportingMono) 11.sp else Clauderc.fontSizes.caption,
                    fontFamily = if (supportingMono) MonoFamily else null,
                    lineHeight = 1.45.em,
                    maxLines = if (supportingMono) 1 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (checked) Icon(Symbols.Check, contentDescription = "当前", tint = c.fg)
    }
}

/** .dialog: centered card, 28dp corners, actions at the bottom right. */
@Composable
fun ClaudercDialog(
    onDismiss: () -> Unit,
    title: String,
    text: String? = null,
    body: (@Composable ColumnScope.() -> Unit)? = null,
    buttons: @Composable RowScope.() -> Unit,
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val ui = Clauderc.style
    val surface = when (ui) {
        UiStyle.Sticker -> Modifier.sticker(c.sheet, c.ink, 32.dp, drop = 6.dp)
        UiStyle.Grid -> Modifier.clip(RoundedCornerShape(6.dp)).background(c.sheet)
        UiStyle.Clear -> Modifier.clip(RoundedCornerShape(Clauderc.radii.lg)).background(c.pane.compositeOver(c.sheet)).topHighlight(c.rim)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = sp.sp6)
                .fillMaxWidth()
                .then(surface)
                .padding(start = sp.sp6, end = sp.sp6, top = sp.sp6, bottom = sp.sp4 + if (ui == UiStyle.Sticker) 4.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(sp.sp4),
        ) {
            Text(title, fontSize = Clauderc.fontSizes.headline, lineHeight = 1.3.em, color = c.fg, modifier = Modifier.semantics { heading() })
            if (text != null) Text(text, fontSize = Clauderc.fontSizes.label, lineHeight = 1.65.em, color = c.fg2)
            body?.invoke(this)
            CompositionLocalProvider(LocalOverlayActions provides true) {
                Row(
                    Modifier.fillMaxWidth().padding(top = sp.sp2).padding(end = 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (ui == UiStyle.Sticker) sp.sp2 else sp.sp1, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = buttons,
                )
            }
        }
    }
}

/** State for the single snackbar a screen shows at a time. */
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

/** .snackbar: inverse colors, 4dp corners (贴纸: an 18dp ink-outlined sticker), auto-dismissed after four seconds. */
@Composable
fun SnackHost(state: SnackState, modifier: Modifier = Modifier, bottom: Dp = Clauderc.spacing.sp3) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    LaunchedEffect(state.key) {
        if (state.message != null) {
            delay(4000)
            state.dismiss()
        }
    }
    // Keep the text while it leaves.
    var shown by remember { mutableStateOf("") }
    state.message?.let { shown = it }
    Lift(visible = state.message != null, modifier = modifier, distance = 16.dp) {
        Box(
            Modifier
                .padding(start = sp.sp3, end = sp.sp3, bottom = bottom)
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .then(
                    if (Clauderc.style == UiStyle.Sticker) {
                        Modifier.sticker(c.inverse, c.ink, 18.dp)
                    } else {
                        Modifier.clip(RoundedCornerShape(4.dp)).background(c.inverse)
                    },
                )
                .padding(horizontal = sp.sp4, vertical = sp.sp3),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(shown, color = c.onInverse, fontSize = Clauderc.fontSizes.label, lineHeight = 1.45.em)
        }
    }
}
