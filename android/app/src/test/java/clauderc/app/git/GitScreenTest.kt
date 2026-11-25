package clauderc.app.git

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.protocol.Commit
import clauderc.app.protocol.CommitFile
import clauderc.app.protocol.Edge
import clauderc.app.protocol.FileChange
import clauderc.app.protocol.FileStatus
import clauderc.app.protocol.GitDiffResponse
import clauderc.app.protocol.GitShowResponse
import clauderc.app.protocol.GitStatusResponse
import clauderc.app.protocol.Ref
import clauderc.app.protocol.RefKind
import clauderc.app.testing.T0
import androidx.compose.ui.graphics.Color
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.ClaudercTheme
import clauderc.app.ui.theme.InkDarkColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Tasks 14.1 to 14.3: what the git pages show and send. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h844dp")
class GitScreenTest {
    @get:Rule val compose = createComposeRule()

    private val status = GitStatusResponse(
        branch = "dev", upstream = "origin/dev", ahead = 2, behind = 0,
        staged = listOf(FileStatus("internal/protocol/errors.go", change = FileChange.Added)),
        unstaged = listOf(FileStatus("AGENTS.md", change = FileChange.Modified), FileStatus("scripts/e2e-claude/run.py", change = FileChange.Untracked)),
    )

    private fun commits(n: Int) = (0 until n).map { i ->
        Commit(
            hash = "c%06d".format(i), parents = listOf("c%06d".format(i + 1)), author = "林舟", time = T0.minusSeconds(i * 600L),
            subject = "提交 $i", refs = if (i == 0) listOf(Ref("dev", RefKind.Branch), Ref("v0.0.1", RefKind.Tag)) else null,
            lane = 0, edges = listOf(Edge(0, 0)),
        )
    }

    private fun show(state: GitUiState, actions: GitActions = GitActions(), style: UiStyle = UiStyle.Clear) {
        compose.setContent { ClaudercTheme(darkTheme = false, style = style) { GitScreen(state, actions) } }
    }

    private val base = GitUiState(dir = "/Users/me/github/clauderc-app", dirs = listOf("/Users/me/github/clauderc-app", "/Users/me/notes"), status = status, commits = commits(30), hasMore = true)

    @Test fun timelineShowsRefsAndLoadsMoreAtTheBottom() {
        var more = 0
        show(base, GitActions(onLoadMore = { more++ }))
        compose.onNodeWithText("~/github/clauderc-app").assertIsDisplayed()
        compose.onNode(hasText("HEAD → dev", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("↑2 ↓0 origin/dev").assertIsDisplayed()
        assertEquals(0, more)
        compose.onNodeWithTag("timeline").performScrollToIndex(30)
        compose.waitForIdle()
        assertTrue(more >= 1)
        compose.onNodeWithText("继续加载更早的提交").assertIsDisplayed()
    }

    @Test fun repositoryCanBeSwitchedFromRecentDirectories() {
        val picked = mutableListOf<String>()
        show(base, GitActions(onPickDir = { picked += it }))
        compose.onNodeWithText("~/github/clauderc-app").performClick()
        compose.onNodeWithText("最近的目录").assertIsDisplayed()
        compose.onNodeWithText("notes").performClick()
        assertEquals(listOf("/Users/me/notes"), picked)
    }

    @Test fun notARepository() {
        show(base.copy(dir = "/Users/me/notes", notRepo = true))
        compose.onNodeWithText("不是 git 仓库").assertIsDisplayed()
        compose.onNodeWithText("换一个目录").assertIsDisplayed()
        compose.onNodeWithText("时间线").assertDoesNotExist()
    }

    @Test fun statusGroupsFilesAndCommitNeedsAMessage() {
        val ops = mutableListOf<GitOp>()
        show(base.copy(tab = GitTab.Status), GitActions(onRequest = { ops += it }))
        compose.onNodeWithText("已暂存").assertIsDisplayed()
        compose.onNodeWithText("未暂存").assertIsDisplayed()
        compose.onNodeWithText("errors.go").assertIsDisplayed()
        compose.onNodeWithText("AGENTS.md").assertIsDisplayed()
        compose.onNodeWithText("未跟踪").assertIsDisplayed()
        compose.onNodeWithText("改动(3)").assertIsDisplayed()
        compose.onNodeWithText("提交").assertIsNotEnabled()
        compose.onNodeWithText("取消暂存").performClick()
        assertEquals(listOf<GitOp>(GitOp.Unstage("internal/protocol/errors.go")), ops)
    }

    @Test fun busyAndUpstreamConfirmations() {
        var confirmed = 0
        show(base.copy(tab = GitTab.Status, confirm = GitConfirm.Busy("/Users/me/github/clauderc-app", GitOp.Commit("x"), "clauderc 协议")), GitActions(onConfirm = { confirmed++ }))
        compose.onNodeWithText("claude 正在这个目录工作").assertIsDisplayed()
        compose.onNode(hasText("「clauderc 协议」正在改 clauderc-app", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("仍然提交").performClick()
        assertEquals(1, confirmed)
    }

    @Test fun upstreamConfirmation() {
        show(base.copy(tab = GitTab.Status, confirm = GitConfirm.Upstream("/Users/me/github/clauderc-app", "feat-x")))
        compose.onNodeWithText("feat-x 还没有上游").assertIsDisplayed()
        compose.onNode(hasText("origin/feat-x", substring = true)).assertIsDisplayed()
    }

    @Test fun fileStageActionsAreDisabledWhileAWriteIsRunning() {
        show(base.copy(tab = GitTab.Status, running = true))
        compose.onNodeWithText("取消暂存").assertIsNotEnabled()
        for (node in compose.onAllNodesWithText("暂存").fetchSemanticsNodes().indices) {
            compose.onAllNodesWithText("暂存")[node].assertIsNotEnabled()
        }
    }

    @Test fun failedLoadMoreShowsAClickableRetryInsteadOfASpinner() {
        var retried = 0
        show(base.copy(moreFailed = true, loadingMore = false), GitActions(onLoadMore = { retried++ }))
        compose.onNodeWithTag("timeline").performScrollToIndex(30)
        compose.waitForIdle()
        assertEquals("scrolling to a failed footer does not loop automatically", 0, retried)
        compose.onNodeWithText("继续加载更早的提交").assertDoesNotExist()
        compose.onNodeWithText("加载失败，点此重试").assertIsDisplayed().performClick()
        assertEquals(1, retried)
    }

    @Test fun failureShowsGitsRawOutput() {
        show(base.copy(tab = GitTab.Status, failure = failureOf(GitOp.Push(), "gitFailed", " ! [rejected]        dev -> dev (fetch first)")))
        compose.onNodeWithText("推送被拒绝").assertIsDisplayed()
        compose.onNode(hasText("[rejected]", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("$ git push", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("时间线和改动已刷新").assertIsDisplayed()
    }

    @Test fun commitDetailShowsCountsAndBinaryFiles() {
        val opened = mutableListOf<String>()
        val show = GitShowResponse(
            hash = "7c2e0d4aa", parents = listOf("5b81aa0ff"), author = "林舟", time = T0,
            message = "定义协议信封\n\n- Decode 遇到 v 不等于 1 时返回错误",
            files = listOf(
                CommitFile("internal/protocol/envelope_test.go", added = 112, deleted = 3),
                CommitFile("design/ui-mockup/preview.png", binary = true),
            ),
        )
        compose.setContent { ClaudercTheme(darkTheme = false) { CommitScreen("7c2e0d4aa", Loadable(show), onBack = {}, onOpenFile = { opened += it.path }) } }
        compose.onNodeWithText("定义协议信封").assertIsDisplayed()
        compose.onNodeWithText("2 个文件").assertIsDisplayed()
        compose.onAllNodesWithText("+112").assertCountEquals(2)
        compose.onAllNodesWithText("−3").assertCountEquals(2)
        compose.onNodeWithText("二进制文件已更改").assertIsDisplayed()
        compose.onNodeWithText("5b81aa0").assertIsDisplayed()
        compose.onNodeWithText("envelope_test.go").performClick()
        compose.onNodeWithText("preview.png").performClick()
        assertEquals(listOf("internal/protocol/envelope_test.go"), opened)
    }

    @Test fun longDiffStartsFoldedAndExpands() {
        val patch = "@@ -0,0 +1,1284 @@\n" + (1..1284).joinToString("\n") { "+line $it" }
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                DiffScreen("internal/protocol/testdata/chat_items.json", "7c2e0d4", Loadable(GitDiffResponse("chat_items.json", patch = patch, collapsed = true)), onBack = {})
            }
        }
        compose.onNodeWithText("1,284 行差异，已折叠").assertIsDisplayed()
        compose.onNodeWithText("line 1").assertDoesNotExist()
        compose.onNodeWithText("展开").performClick()
        compose.onNodeWithText("line 1").assertIsDisplayed()
    }

    @Test fun shortDiffShowsAddedAndDeletedLines() {
        val patch = "@@ -1,2 +1,2 @@\n import (\n-\t\"testing\"\n+\t\"errors\"\n"
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                DiffScreen("internal/protocol/envelope_test.go", "7c2e0d4", Loadable(GitDiffResponse("envelope_test.go", patch = patch)), onBack = {})
            }
        }
        compose.onNodeWithText("    \"testing\"").assertIsDisplayed()
        compose.onNodeWithText("    \"errors\"").assertIsDisplayed()
        compose.onNodeWithText("+1").assertIsDisplayed()
    }

    @Test fun binaryDiffShowsAHintOnly() {
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                DiffScreen("preview.png", "7c2e0d4", Loadable(GitDiffResponse("preview.png", binary = true)), onBack = {})
            }
        }
        compose.onNodeWithText("二进制文件已更改，不显示内容").assertIsDisplayed()
    }

    @Test fun diffGroupsAdjacentDelAndAddWithoutBackgroundFillAndShowsAlignedHeader() {
        val patch = "@@ -1,3 +1,4 @@\n package protocol\n-var oldA = 1\n-var oldB = 2\n+var newA = 1\n+var newB = 2\n+var newC = 3\n"
        val blocks = groupDiffLines(parsePatch(patch))
        val del = blocks.filterIsInstance<DiffBlock.Group>().first { it.kind == DiffKind.Del }
        val add = blocks.filterIsInstance<DiffBlock.Group>().first { it.kind == DiffKind.Add }
        assertTrue("adjacent Del joins Add", del.joinNext && !del.joinPrev)
        assertTrue("adjacent Add joins Del", add.joinPrev && !add.joinNext)
        assertEquals(Color.Transparent, diffLineColors(DiffKind.Del, InkDarkColors).first)
        assertEquals(Color.Transparent, diffLineColors(DiffKind.Add, InkDarkColors).first)

        compose.setContent {
            ClaudercTheme(darkTheme = true, style = UiStyle.Ink) {
                DiffScreen("envelope.go", "7c2e0d4", Loadable(GitDiffResponse("envelope.go", patch = patch)), onBack = {})
            }
        }
        compose.onNodeWithTag("diffHeader").assertIsDisplayed()
        compose.onNodeWithText("旧").assertIsDisplayed()
        compose.onNodeWithText("新").assertIsDisplayed()
        compose.onNodeWithText("代码差异").assertIsDisplayed()
        compose.onNodeWithTag("diffDelGroup").assertIsDisplayed()
        compose.onNodeWithTag("diffAddGroup").assertIsDisplayed()
    }

    @Test fun plateGitShowsPendingStampSeal() {
        var jumped = 0
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = UiStyle.Plate) {
                GitScreen(base, GitActions(), pendingCount = 2, onOpenPending = { jumped++ })
            }
        }
        compose.onNodeWithTag("pendingJumpBadge").assertIsDisplayed().performClick()
        assertEquals(1, jumped)
    }

    @Test fun inkGitShowsFloatingTopBarBrushTabsAndMachineDirSwitcher() {
        var drawerOpened = 0
        val pickedDirs = mutableListOf<String>()
        val openedCommits = mutableListOf<String>()
        val tabs = mutableListOf<GitTab>()
        val m1 = clauderc.app.testing.machine("m-1", "工作室 Mac mini")
        val m2 = clauderc.app.testing.machine("m-2", "家里的 iMac")
        val switchedMachines = mutableListOf<String>()
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = UiStyle.Ink) {
                GitScreen(
                    base,
                    GitActions(
                        onTab = { tabs += it },
                        onPickDir = { pickedDirs += it },
                        onOpenCommitAt = { hash, _ -> openedCommits += hash },
                    ),
                    onOpenDrawer = { drawerOpened++ },
                    machines = listOf(m1, m2),
                    currentMachineId = "m-1",
                    onSelectMachine = { switchedMachines += it.id },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("inkDrawerButton").assertIsDisplayed().performClick()
        assertEquals(1, drawerOpened)
        compose.onNodeWithTag("inkBrushTabs").assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithTag("brushTab:1").performClick()
        assertEquals(listOf(GitTab.Status), tabs)

        // Center pill opens recent directory morph panel (without machine switcher).
        compose.onNodeWithTag("inkMachinePill").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("最近的目录").assertIsDisplayed()
        compose.onNodeWithText("家里的 iMac").assertDoesNotExist()
        compose.onNodeWithText("notes").performClick()
        compose.waitForIdle()
        assertEquals(listOf("/Users/me/notes"), pickedDirs)

        // Tapping a commit triggers Ink's morph commit callback.
        compose.onNodeWithText("提交 0").performClick()
        assertEquals(listOf("c000000"), openedCommits)
    }

    @Test fun inkCommitAndDiffMorphPanelsSupportParentBackAndWorkingTreeStaging() {
        val show = GitShowResponse(
            hash = "7c2e0d4aa", parents = listOf("5b81aa0ff"), author = "林舟", time = T0,
            message = "定义协议信封",
            files = listOf(CommitFile("internal/protocol/envelope.go", added = 12, deleted = 2)),
        )
        val parentsOpened = mutableListOf<String>()
        val filesOpened = mutableListOf<String>()
        var backed = 0
        var stageToggled = 0
        val patch = "@@ -1,2 +1,2 @@\n-old\n+new\n"
        var showDiff by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            ClaudercTheme(darkTheme = true, style = UiStyle.Ink) {
                if (!showDiff) {
                    CommitMorphPanel(
                        dir = "/Users/me/github/clauderc-app",
                        hash = "7c2e0d4aa",
                        canGoBack = true,
                        state = Loadable(show),
                        anchor = null,
                        onBackParent = { backed++ },
                        onClose = {},
                        onOpenParent = { parentsOpened += it },
                        onOpenFileAt = { f, _ -> filesOpened += f.path },
                    )
                } else {
                    DiffMorphPanel(
                        path = "internal/protocol/envelope.go",
                        subtitle = "未暂存",
                        state = Loadable(GitDiffResponse("envelope.go", patch = patch)),
                        anchor = null,
                        staged = false,
                        onToggleStage = { stageToggled++ },
                        onClose = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("inkCommitMorph").assertIsDisplayed()
        compose.onNodeWithTag("commitParent:5b81aa0").performClick()
        assertEquals(listOf("5b81aa0ff"), parentsOpened)
        compose.onNodeWithText("envelope.go").performClick()
        assertEquals(listOf("internal/protocol/envelope.go"), filesOpened)
        compose.onNode(androidx.compose.ui.test.hasContentDescription("上一条提交")).performClick()
        assertEquals(1, backed)

        showDiff = true
        compose.waitForIdle()
        compose.onNodeWithTag("inkDiffMorph").assertIsDisplayed()
        compose.onNodeWithTag("diffHeader").assertIsDisplayed()
        compose.onNodeWithText("暂存此文件").assertIsDisplayed().performClick()
        assertEquals(1, stageToggled)
    }

    @Test fun plateGitShowsHeaderTabs2BranchPillAndOpensSlipPanel() {
        val tabs = mutableListOf<GitTab>()
        val pickedDirs = mutableListOf<String>()
        var refreshed = 0
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = UiStyle.Plate) {
                GitScreen(
                    base.copy(dirs = listOf("/Users/me/github/clauderc-app", "/Users/me/github/api")),
                    GitActions(
                        onRefresh = { refreshed++ },
                        onTab = { tabs += it },
                        onPickDir = { pickedDirs += it },
                    ),
                    pendingCount = 2,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("plateGitHeader").assertIsDisplayed()
        compose.onNodeWithTag("plateTabs2").assertIsDisplayed()
        compose.onNodeWithTag("plateBranchPill").assertIsDisplayed()
        compose.onNodeWithContentDescription("刷新").performClick()
        assertEquals(1, refreshed)
        compose.onNodeWithText("改动").performClick()
        assertEquals(listOf(GitTab.Status), tabs)
        compose.onNodeWithTag("plateGitWhere").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("plateSlip").assertIsDisplayed()
        compose.onNodeWithText("api").performClick()
        assertEquals(listOf("/Users/me/github/api"), pickedDirs)
    }

    @Test fun clearGitShowsSegLensAndOpensDropPanel() {
        val pickedDirs = mutableListOf<String>()
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = UiStyle.Clear) {
                GitScreen(
                    base.copy(dirs = listOf("/Users/me/github/clauderc-app", "/Users/me/github/api")),
                    GitActions(onPickDir = { pickedDirs += it }),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("clearGitHeader").assertIsDisplayed()
        compose.onNodeWithTag("clearSegLens").assertIsDisplayed()
        compose.onNodeWithTag("clearGitWhere").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("clearDrop").assertIsDisplayed()
        compose.onNodeWithText("api").performClick()
        assertEquals(listOf("/Users/me/github/api"), pickedDirs)
    }

    @Test fun tileGitShowsGroupedHeaderPendingCookieAndOpensBottomSheet() {
        val pickedDirs = mutableListOf<String>()
        var jumped = 0
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = UiStyle.Tile) {
                GitScreen(
                    base.copy(dirs = listOf("/Users/me/github/clauderc-app", "/Users/me/github/api")),
                    GitActions(onPickDir = { pickedDirs += it }),
                    pendingCount = 3,
                    onOpenPending = { jumped++ },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("tileGitHeader").assertIsDisplayed()
        compose.onNodeWithTag("pendingJumpBadge").assertIsDisplayed().performClick()
        assertEquals(1, jumped)
        compose.onNodeWithTag("tileGitWhere").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tileSheet").assertIsDisplayed()
        compose.onNodeWithText("api").performClick()
        assertEquals(listOf("/Users/me/github/api"), pickedDirs)
    }
}
