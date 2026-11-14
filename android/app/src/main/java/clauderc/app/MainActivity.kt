package clauderc.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import clauderc.app.ui.theme.ClaudercTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ClaudercApp).container
        setContent {
            ClaudercTheme { ClaudercNav(container) }
        }
    }
}
