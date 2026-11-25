package clauderc.app.ui.components

import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
 * 素卡's signature soft floating bottom tray (demo.html `.scrim` + `.sheet`):
 * 8dp-inset 32dp-rounded pure white/dark card tray with a centered grab handle sliding softly up
 * from the bottom edge.
 */
@Composable
fun TileBottomSheet(
    onDismiss: () -> Unit,
    tag: String = "tileSheet",
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Clauderc.colors
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val scope = rememberCoroutineScope()

    val progress = remember { Animatable(0f) }
    val scrimAlpha = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        launch { scrimAlpha.animateTo(1f, tween(240)) }
        launch { progress.animateTo(1f, Motion.enter()) }
    }

    val requestClose: ((() -> Unit)?) -> Unit = remember(onDismiss) {
        { after ->
            if (!closing) {
                closing = true
                scope.launch {
                    launch { scrimAlpha.animateTo(0f, tween(200)) }
                    progress.animateTo(0f, Motion.exit())
                    onDismiss()
                    after?.invoke()
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { requestClose(null) },
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
                    .clickable(remember { MutableInteractionSource() }, indication = null) { requestClose(null) }
                    .testTag("tileScrim"),
            )

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .graphicsLayer {
                        val p = progress.value
                        translationY = (size.height + 48f) * (1f - p)
                    }
                    .heightIn(max = screenHeightDp * 0.80f)
                    .tileSurface(c.card, 32.dp)
                    .pointerInput(Unit) { detectTapGestures {} }
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp, bottom = 14.dp)
                    .testTag(tag),
            ) {
                // Centered soft grab handle matching demo.html .sheet .grab
                Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(c.line))
                }
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
