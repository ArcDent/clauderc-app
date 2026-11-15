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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.Lift
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Symbols
import kotlinx.coroutines.delay

/** .sheet: a bottom sheet with a short handle and 28dp top corners. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaudercSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Clauderc.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.sheet,
        contentColor = c.fg,
        scrimColor = c.scrim,
        shape = RoundedCornerShape(topStart = Clauderc.radii.lg, topEnd = Clauderc.radii.lg),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = Clauderc.spacing.sp3, bottom = Clauderc.spacing.sp5)
                    .size(32.dp, 4.dp)
                    .background(c.surface2, RoundedCornerShape(2.dp)),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Clauderc.spacing.sp5)
                .navigationBarsPadding(),
            content = content,
        )
    }
}

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
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val fg = if (danger) c.danger else c.fg
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = sp.page, vertical = sp.sp2),
        horizontalArrangement = Arrangement.spacedBy(sp.sp4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = if (danger) c.danger else c.fg2)
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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = sp.sp6)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Clauderc.radii.lg))
                .background(c.sheet)
                .padding(start = sp.sp6, end = sp.sp6, top = sp.sp6, bottom = sp.sp4),
            verticalArrangement = Arrangement.spacedBy(sp.sp4),
        ) {
            Text(title, fontSize = Clauderc.fontSizes.headline, lineHeight = 1.3.em, color = c.fg, modifier = Modifier.semantics { heading() })
            if (text != null) Text(text, fontSize = Clauderc.fontSizes.label, lineHeight = 1.65.em, color = c.fg2)
            body?.invoke(this)
            Row(
                Modifier.fillMaxWidth().padding(top = sp.sp2).padding(end = 0.dp),
                horizontalArrangement = Arrangement.spacedBy(sp.sp1, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = buttons,
            )
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

/** .snackbar: inverse colors, 4dp corners, auto-dismissed after four seconds. */
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
                .clip(RoundedCornerShape(if (isCardStyle) 14.dp else 4.dp))
                .background(c.inverse)
                .padding(horizontal = sp.sp4, vertical = sp.sp3),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(shown, color = c.onInverse, fontSize = Clauderc.fontSizes.label, lineHeight = 1.45.em)
        }
    }
}
