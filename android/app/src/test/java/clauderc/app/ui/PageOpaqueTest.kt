package clauderc.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.chat.ChatActions
import clauderc.app.chat.ChatScreen
import clauderc.app.chat.ChatUiState
import clauderc.app.git.CommitScreen
import clauderc.app.git.DiffScreen
import clauderc.app.git.Loadable
import clauderc.app.pair.PairScreen
import clauderc.app.pair.PairUiState
import clauderc.app.sessions.CreateSessionScreen
import clauderc.app.sessions.CreateUiState
import clauderc.app.ui.theme.ClaudercTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every page pushed onto the NavHost paints its own background. During the slide the page below
 * stays on screen, so a transparent page shows its text through (the 新建会话 page once did).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xxhdpi")
class PageOpaqueTest {
    @get:Rule val compose = createComposeRule()

    private fun assertOpaque(page: @Composable () -> Unit) {
        // Spinners and the scan line never settle, so drive the clock by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().background(Color.Red).testTag("under")) { page() }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        val bitmap = compose.onNodeWithTag("under").captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertEquals("pure red pixels showing through", 0, pixels.count { it == android.graphics.Color.RED })
    }

    @Test fun createSession() = assertOpaque {
        CreateSessionScreen(
            CreateUiState(profileTitle = "官方", profileHost = "api.anthropic.com", dir = "~/github/infra", recent = listOf("~/github/infra", "~/notes")),
            onClose = {}, onSubmit = {}, onDir = {}, onPickDir = {}, onShowAllDirs = {}, onName = {}, onPrompt = {},
        )
    }

    @Test fun chat() = assertOpaque { ChatScreen(ChatUiState(loading = false), ChatActions()) }

    @Test fun commit() = assertOpaque { CommitScreen("7c2e0d4", Loadable(), onBack = {}, onOpenFile = {}) }

    @Test fun diff() = assertOpaque { DiffScreen("envelope.go", "未暂存", Loadable(), onBack = {}) }

    @Test fun pair() = assertOpaque { PairScreen(PairUiState.Scanning, {}, {}, scanner = { _, _ -> }) }
}
