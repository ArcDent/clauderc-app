package clauderc.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.ClaudercTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MorphOverlayTest {
    @get:Rule val compose = createComposeRule()

    @Test fun morphCapturesAnchorExpandsAndClosesWithFollowUp() {
        var open by mutableStateOf(false)
        var captured: Rect? = null
        var followed = false
        compose.setContent {
            ClaudercTheme(darkTheme = true, style = UiStyle.Ink) {
                val anchor = rememberAnchorBox()
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(20.dp)
                            .size(56.dp)
                            .captureAnchor(anchor)
                            .clickable {
                                captured = anchor.rect
                                open = true
                            }
                            .testTag("trigger"),
                    ) { Text("球") }
                    if (open) {
                        MorphOverlay(onDismiss = { open = false }, anchor = captured) {
                            val close = LocalMorphClose.current
                            Text("悬浮内容")
                            Text(
                                "点此收起并执行",
                                Modifier.clickable { close?.invoke { followed = true } },
                            )
                        }
                    }
                }
            }
        }

        compose.onNodeWithTag("trigger").performClick()
        compose.waitForIdle()
        assertNotNull("trigger bounds should be captured", captured)
        assertTrue("captured width should match trigger", captured!!.width > 0f)
        compose.onNodeWithTag("morphPanel").assertIsDisplayed()
        compose.onNodeWithText("悬浮内容").assertIsDisplayed()

        compose.onNodeWithText("点此收起并执行").performClick()
        compose.waitForIdle()
        assertFalse("panel should dismiss after shrink animation", open)
        assertTrue("follow-up action should run once closed", followed)
    }

    @Test fun inkSheetAndDialogDispatchThroughMorphOverlay() {
        var showSheet by mutableStateOf(true)
        var showDialog by mutableStateOf(false)
        compose.setContent {
            ClaudercTheme(darkTheme = true, style = UiStyle.Ink) {
                if (showSheet) {
                    ClaudercSheet(onDismiss = { showSheet = false }, fromTop = true) {
                        SheetHead("浮墨顶落浮层")
                        MenuItem("确认项", onClick = { showSheet = false; showDialog = true })
                    }
                }
                if (showDialog) {
                    ClaudercDialog(onDismiss = { showDialog = false }, title = "浮墨确认卡", text = "测试说明") {
                        TextAction("好", onClick = { showDialog = false })
                    }
                }
            }
        }

        compose.waitForIdle()
        compose.onNodeWithTag("morphPanel").assertIsDisplayed()
        compose.onNodeWithText("浮墨顶落浮层").assertIsDisplayed()
        compose.onNodeWithText("确认项").performClick()
        compose.waitForIdle()

        assertEquals(false, showSheet)
        assertEquals(true, showDialog)
        compose.onNodeWithTag("morphPanel").assertIsDisplayed()
        compose.onNodeWithText("浮墨确认卡").assertIsDisplayed()
    }

    @Test fun nestedMorphOverlaysStackAndDismissIndependently() {
        var outer by mutableStateOf(true)
        var inner by mutableStateOf(false)
        compose.setContent {
            ClaudercTheme(darkTheme = true, style = UiStyle.Ink) {
                if (outer) {
                    MorphOverlay(onDismiss = { outer = false }, tag = "outerMorph") {
                        Text("提交详情")
                        Text("看差异", Modifier.clickable { inner = true })
                    }
                }
                if (inner) {
                    MorphOverlay(onDismiss = { inner = false }, tall = true, tag = "innerMorph") {
                        val close = LocalMorphClose.current
                        Text("代码差异")
                        Text("关差异", Modifier.clickable { close?.invoke(null) })
                    }
                }
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("看差异").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("innerMorph").assertIsDisplayed()
        compose.onNodeWithText("代码差异").assertIsDisplayed()

        compose.onNodeWithText("关差异").performClick()
        compose.waitForIdle()
        assertFalse("inner morph closes cleanly", inner)
        assertTrue("outer morph stays open underneath", outer)
        compose.onNodeWithTag("outerMorph").assertIsDisplayed()
    }
}
