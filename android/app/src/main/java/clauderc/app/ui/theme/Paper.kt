package clauderc.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The page surface under everything (--c-bg) across all four looks:
 * 浮墨 near-black / warm stone, 素卡 warm grey / charcoal, 清玻 white / pure black, 印版 cotton / ink paper.
 */
@Composable
fun Modifier.page(): Modifier = background(Clauderc.colors.bg)
