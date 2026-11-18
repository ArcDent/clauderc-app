package clauderc.app.protocol

import kotlinx.serialization.Serializable

/**
 * The terminal of a running session as its hidden attach shows it: one line per row of
 * the 40 by 120 screen, trailing spaces removed. [cursorCol] counts the characters of
 * the cursor's line before it and may lie past the line's end; [cursor] is false while
 * Claude Code hides it, as in a panel. The answer to term.open and the body of
 * term.screen, which comes at most 5 times a second.
 */
@Serializable
data class TermScreen(
    val sessionId: String,
    val lines: List<String> = emptyList(),
    val cursorRow: Int = 0,
    val cursorCol: Int = 0,
    val cursor: Boolean = false,
)

/** term.key presses [key]: up, down, left, right, enter, esc, tab, backspace or a digit 0 to 9. */
@Serializable
data class TermKeyRequest(val sessionId: String, val key: String)

/** The keys of the terminal view's key bar. */
object TermKey {
    const val UP = "up"
    const val DOWN = "down"
    const val LEFT = "left"
    const val RIGHT = "right"
    const val ENTER = "enter"
    const val ESC = "esc"
    const val TAB = "tab"
    const val BACKSPACE = "backspace"
}
