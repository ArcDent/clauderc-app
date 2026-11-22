package clauderc.app.chat

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.chat.MdBlock.Code
import clauderc.app.protocol.ChatItem
import clauderc.app.protocol.ItemKind
import clauderc.app.protocol.SessionState
import clauderc.app.testing.session
import clauderc.app.ui.theme.ClaudercTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Round 8: links in replies and in the user's own messages, and the small menu a tap opens. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp")
class ChatLinkTest {
    @get:Rule val compose = createComposeRule()

    private fun text(s: String) = MdRun(RunKind.TEXT, s)
    private fun link(s: String, url: String = s) = MdRun(RunKind.LINK, s, url)

    @Test fun markdownAndBareLinksAreFound() {
        assertEquals(listOf(text("见"), link("文字", "https://a.b/c"), text("。")), mdRuns("见[文字](https://a.b/c)。"))
        // A bare address stops at full-width punctuation and leaves the punctuation stuck to it.
        assertEquals(listOf(text("见 "), link("https://a.b/c"), text("）。")), mdRuns("见 https://a.b/c）。"))
        assertEquals(listOf(link("https://a.b/c"), text(".")), mdRuns("https://a.b/c."))
        // A closing bracket the address opened is part of it; one it did not open is not.
        assertEquals(listOf(text("("), link("https://en.wikipedia.org/wiki/Go_(game)"), text(")")), mdRuns("(https://en.wikipedia.org/wiki/Go_(game))"))
        assertEquals(listOf(link("https://pkg.go.dev/time#Duration"), text("里有说明")), mdRuns("https://pkg.go.dev/time#Duration里有说明"))
        assertEquals(listOf(link("围棋", "https://en.wikipedia.org/wiki/Go_(game)")), mdRuns("[围棋](https://en.wikipedia.org/wiki/Go_(game))"))
    }

    @Test fun pathsDomainsAndCodeAreNotLinks() {
        assertEquals(listOf(text("改了 chat.go 和 /Users/x/a.go，见 example.com")), mdRuns("改了 chat.go 和 /Users/x/a.go，见 example.com"))
        assertEquals(listOf(text("用 "), MdRun(RunKind.CODE, "https://a.b/c"), text(" 访问")), mdRuns("用 `https://a.b/c` 访问"))
        assertEquals(listOf(Code("sh", "curl https://a.b/c")), mdBlocks("```sh\ncurl https://a.b/c\n```"))
    }

    @Test fun theUsersOwnMessageOnlyHasItsLinksPickedOut() {
        assertEquals(
            listOf(text("**别动** `x`，文档 "), link("https://pkg.go.dev/time")),
            linkRuns("**别动** `x`，文档 https://pkg.go.dev/time"),
        )
    }

    @Test fun onlyWebAndMailCanBeOpened() {
        assertTrue(isOpenable("https://a.b") && isOpenable("HTTP://a.b") && isOpenable("mailto:a@b.c"))
        for (u in listOf("javascript:alert(1)", "file:///etc/hosts", "intent://x#Intent;end", "ftp://a.b", "https:foo", "http:/a.b", "https://", "mailto:")) assertTrue(u, !isOpenable(u))
    }

    @Test fun theMenuHangsUnderTheLinkAndFlipsAboveNearTheFoot() {
        val window = IntSize(1080, 2400)
        val menu = IntSize(700, 400)
        fun place(at: IntRect) = NearLink(at, 6, 36, 48).calculatePosition(IntRect.Zero, window, LayoutDirection.Ltr, menu)
        assertEquals("under the link's line, at its left", 200 to 1006, place(IntRect(200, 950, 500, 1000)).let { it.x to it.y })
        assertEquals("kept inside the right side", 1080 - 700 - 36, place(IntRect(900, 950, 1000, 1000)).x)
        assertEquals("above the line near the foot", 2000 - 6 - 400, place(IntRect(200, 2000, 500, 2050)).y)
    }

    private val reply = listOf(
        "参考 [gRPC 的退避说明](https://github.com/grpc/grpc/blob/master/doc/connection-backoff.md)。",
        "",
        "有问题写信给 [维护者](mailto:dev@example.com)，日志在 [本地文件](file:///Users/demo/relay.log)，[别点](javascript:alert(1))",
    ).joinToString("\n")

    private fun show() {
        val state = ChatUiState(
            session = session("s-1", "退避说明", SessionState.Idle, "default"),
            loading = false,
            items = listOf(
                ChatItem(1, ItemKind.USER, text = "Duration 的文档发我 https://pkg.go.dev/time"),
                ChatItem(2, ItemKind.ASSISTANT, text = reply),
            ),
        )
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, ChatActions()) } }
        compose.waitForIdle()
    }

    /** Taps the middle of [label] inside the text [node] shows. */
    private fun tap(node: SemanticsNodeInteraction, label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        val start = layout.layoutInput.text.text.indexOf(label)
        assertTrue("「$label」 is in the text", start >= 0)
        val box = layout.getBoundingBox(start + label.length / 2)
        node.performTouchInput { click(Offset(box.center.x, box.center.y)) }
        compose.waitForIdle()
    }

    private fun clipboard() = ApplicationProvider.getApplicationContext<Application>().getSystemService(ClipboardManager::class.java)

    private fun started(): Intent? = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity

    @Test fun aTapShowsTheWholeAddressAndCopyPutsItOnTheClipboard() {
        show()
        val url = "https://github.com/grpc/grpc/blob/master/doc/connection-backoff.md"
        tap(compose.onNode(hasText("gRPC 的退避说明", substring = true)), "gRPC 的退避说明")
        compose.onNodeWithTag("popMenu").assertIsDisplayed()
        compose.onNodeWithText(url).assertIsDisplayed()
        compose.onNodeWithText("在浏览器中打开").assertIsDisplayed()
        compose.onNodeWithText("复制链接").performClick()
        compose.waitForIdle()
        assertEquals(url, clipboard().primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithText("已复制链接").assertIsDisplayed()
    }

    @Test fun webAndMailLinksOpenWithActionView() {
        show()
        tap(compose.onNode(hasText("gRPC 的退避说明", substring = true)), "gRPC 的退避说明")
        compose.onNodeWithText("在浏览器中打开").performClick()
        compose.waitForIdle()
        started()!!.let {
            assertEquals(Intent.ACTION_VIEW, it.action)
            assertEquals("https://github.com/grpc/grpc/blob/master/doc/connection-backoff.md", it.dataString)
        }
        tap(compose.onNode(hasText("维护者", substring = true)), "维护者")
        compose.onNodeWithText("mailto:dev@example.com").assertIsDisplayed()
        compose.onNodeWithText("写邮件").performClick()
        compose.waitForIdle()
        started()!!.let {
            assertEquals(Intent.ACTION_VIEW, it.action)
            assertEquals("mailto:dev@example.com", it.dataString)
        }
    }

    @Test fun otherSchemesCanOnlyBeCopied() {
        show()
        for ((label, url) in listOf("本地文件" to "file:///Users/demo/relay.log", "别点" to "javascript:alert(1)")) {
            tap(compose.onNode(hasText(label, substring = true)), label)
            compose.onNodeWithText(url).assertIsDisplayed()
            compose.onNodeWithText("复制链接").assertIsDisplayed()
            compose.onNode(hasText("在浏览器中打开")).assertDoesNotExist()
            compose.onNode(hasText("写邮件")).assertDoesNotExist()
            compose.onNodeWithText("复制链接").performClick()
            compose.waitForIdle()
            assertEquals(url, clipboard().primaryClip!!.getItemAt(0).text.toString())
        }
        assertEquals("nothing was opened", null, started())
    }

    @Test fun aLinkInTheUsersOwnMessageOpensTheMenuToo() {
        show()
        tap(compose.onNode(hasText("Duration 的文档发我", substring = true)), "pkg.go.dev")
        compose.onNodeWithText("https://pkg.go.dev/time").assertIsDisplayed()
        compose.onNodeWithText("在浏览器中打开").assertIsDisplayed()
    }
}
