package clauderc.app.ui.components

import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.Motion
import kotlinx.coroutines.launch

/**
 * 清玻's signature top-falling frosted glass panel (demo.html `.scrim` + `.drop.glass`):
 * Slides smoothly down from above the top edge into an 8dp-inset, 40dp-rounded frosted glass
 * capsule with bright top rim and soft shadow; dismisses back up over the top edge.
 */
@Composable
fun ClearDropPanel(
    onDismiss: () -> Unit,
    tag: String = "clearDrop",
    dismissEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Clauderc.colors
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val scope = rememberCoroutineScope()

    val progress = remember { Animatable(0f) }
    val scrimAlpha = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        launch { scrimAlpha.animateTo(1f, tween(260)) }
        launch { progress.animateTo(1f, Motion.enter()) }
    }

    val requestClose: ((() -> Unit)?) -> Unit = remember(onDismiss) {
        { after ->
            if (!closing) {
                closing = true
                scope.launch {
                    launch { scrimAlpha.animateTo(0f, tween(220)) }
                    progress.animateTo(0f, Motion.exit())
                    onDismiss()
                    after?.invoke()
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { if (dismissEnabled) requestClose(null) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = scrimAlpha.value }
                    .background(c.scrim)
                    .clickable(remember { MutableInteractionSource() }, indication = null, enabled = dismissEnabled) { requestClose(null) }
                    .testTag("clearScrim"),
            )

            val shape = RoundedCornerShape(40.dp)
            val fill = c.pane.copy(alpha = if (c.isDark) 0.86f else 0.90f).compositeOver(c.sheet)
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .imePadding()
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp)
                    .graphicsLayer {
                        val p = progress.value
                        translationY = -(size.height + 48f) * (1f - p)
                    }
                    .heightIn(max = screenHeightDp * 0.80f)
                    .shadow(16.dp, shape, ambientColor = c.glassShadow, spotColor = c.glassShadow)
                    .clip(shape)
                    .background(fill)
                    .topHighlight(c.rim)
                    .border(0.5.dp, c.edge, shape)
                    .pointerInput(Unit) { detectTapGestures {} }
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 12.dp, bottom = 14.dp)
                    .testTag(tag),
            ) {
                CompositionLocalProvider(
                    LocalOverlayActions provides true,
                    LocalMorphClose provides requestClose,
                ) {
                    content()
                }
            }
        }
    }
}
