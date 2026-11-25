package clauderc.app

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The real activity starts on the 3-tab home (会话 / git / 设置); an unpaired phone shows the pairing CTA and scans on tap. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h844dp")
class SmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun unpairedPhoneOpensSessionsTabWithThreeTabsAndScansFromPairCta() {
        waitFor("还没有目标机")
        compose.onNodeWithText("还没有目标机").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertCountEquals(3)
        compose.onNodeWithText("扫描配对二维码").assertDoesNotExist()

        compose.onNodeWithText("配对目标机").performClick()
        waitFor("扫描配对二维码")
        compose.onNodeWithText("二维码 5 分钟内有效，只能用一次").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").assertIsDisplayed().performClick()

        waitFor("还没有目标机")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("扫描配对二维码")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("添加目标机").assertIsDisplayed()
    }
}
