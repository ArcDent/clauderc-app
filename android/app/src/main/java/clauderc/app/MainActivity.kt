package clauderc.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.ClaudercRoot
import clauderc.app.ui.components.LocalLoadingPolicy
import clauderc.app.ui.components.LoadingPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ClaudercApp).container
        // A few bytes on disk; reading them first keeps the first frame from showing the wrong theme.
        val initial = runBlocking { container.theme.appearance.first() }
        // Read before the first frame too: the start-up cover is the first load it decides on.
        val alwaysInitial = runBlocking { container.theme.alwaysLoading.first() }
        setContent {
            val look by container.theme.appearance.collectAsStateWithLifecycle(initial)
            val always by container.theme.alwaysLoading.collectAsStateWithLifecycle(alwaysInitial)
            val policy = remember(always) { LoadingPolicy(always, container.loadingFirsts) }
            ClaudercRoot(look) {
                val dark = Clauderc.colors.isDark
                SideEffect { themedSystemBars(dark) }
                CompositionLocalProvider(LocalLoadingPolicy provides policy) { ClaudercNav(container) }
            }
        }
    }
}

/** Status and navigation bar icons follow the theme picked in 设置, not the system's. */
fun ComponentActivity.themedSystemBars(dark: Boolean) {
    enableEdgeToEdge(
        SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
        SystemBarStyle.auto(Color.argb(0xE6, 0xFF, 0xFF, 0xFF), Color.argb(0x80, 0x1B, 0x1B, 0x1B)) { dark },
    )
}
