package clauderc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.unit.dp
import clauderc.app.ui.theme.Clauderc
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * 清玻's glass over the content (--k-pane, blur 22 and saturate 1.8): what scrolls under it shows
 * through blurred and tinted, with a bright rim along the top edge and a hairline along the bottom.
 * Without a [haze] to blur, the tint is laid on nearly solid.
 */
@Composable
fun Modifier.glassPane(haze: HazeState?, rimTop: Boolean = true, edgeBottom: Boolean = true): Modifier {
    val c = Clauderc.colors
    val glass = if (haze != null) {
        hazeBlur(
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
        background(c.pane.copy(alpha = 0.94f))
    }
    return glass.drawWithContent {
        drawContent()
        if (rimTop) drawLine(c.rim, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx())
        if (edgeBottom) drawLine(c.edge, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
    }
}

/** The content that 清玻's glass parts of a page blur: the chat's messages under its floating input area. */
val LocalGlassSource = staticCompositionLocalOf<HazeState?> { null }
