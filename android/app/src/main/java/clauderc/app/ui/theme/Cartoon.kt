package clauderc.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.vector.ImageVector

/** All four looks (浮墨 / 素卡 / 清玻 / 印版) use clean Material symbols directly. */
val ImageVector.styled: ImageVector
    @Composable @ReadOnlyComposable get() = this
