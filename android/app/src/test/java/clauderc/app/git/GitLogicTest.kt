package clauderc.app.git

import clauderc.app.data.Fleet
import clauderc.app.protocol.DirBusyResponse
import clauderc.app.protocol.DirRequest
import clauderc.app.protocol.DirsRecentResponse
import clauderc.app.protocol.Envelope
import clauderc.app.protocol.ErrorCode
import clauderc.app.protocol.FileChange
import clauderc.app.protocol.FileStatus
import clauderc.app.protocol.GitCommitRequest
import clauderc.app.protocol.GitCommitResponse
import clauderc.app.protocol.GitLogRequest
import clauderc.app.protocol.GitLogResponse
import clauderc.app.protocol.GitPushRequest
import clauderc.app.protocol.GitStatusResponse
import clauderc.app.protocol.MachinesListResponse
import clauderc.app.protocol.Method
import clauderc.app.protocol.ProtocolJson
import clauderc.app.protocol.SessionList
import clauderc.app.testing.FakeRelay
import clauderc.app.testing.decode
import clauderc.app.testing.machine
import clauderc.app.ui.theme.DarkColors
import clauderc.app.ui.theme.LightColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tasks 14.1 to 14.3: timeline geometry, diff parsing and the git view model. */
@OptIn(ExperimentalCoroutinesApi::class)
class GitLogicTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher + Job())
    private val relay = FakeRelay()

    /** The agent's sample: a merge of feat-x into main. */
    private val sampleLog: GitLogResponse by lazy {
        val text = javaClass.classLoader!!.getResource("git.log.res.json")!!.readText()
        ProtocolJson.decodeFromJsonElement(GitLogResponse.serializer(), Envelope.decode(text).body!!)
    }

    private val status = GitStatusResponse(
        branch = "feat-x", upstream = null, ahead = 0, behind = 0,
        staged = listOf(FileStatus("internal/protocol/errors.go", change = FileChange.Added)),
        unstaged = listOf(FileStatus("AGENTS.md", change = FileChange.Modified), FileStatus("scripts/e2e-claude/run.py", change = FileChange.Untracked)),
    )

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        relay.respond(Method.MACHINES_LIST, MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-1", "mac"))))
        relay.respond(Method.SESSIONS_SUBSCRIBE, SessionList.serializer(), SessionList())
        relay.respond(Method.DIRS_RECENT, DirsRecentResponse.serializer(), DirsRecentResponse(listOf("/p/clauderc-app", "/p/notes")))
        relay.respond(Method.GIT_STATUS, GitStatusResponse.serializer(), status)
        relay.on(Method.GIT_LOG) { sent ->
            val page = sent.decode(GitLogRequest.serializer()).page
            ProtocolJson.encodeToJsonElement(GitLogResponse.serializer(), if (page == 0) sampleLog.copy(hasMore = true) else GitLogResponse(sampleLog.commits.map { it.copy(hash = it.hash + "-2") }, hasMore = false))
        }
        relay.respond(Method.DIR_BUSY, DirBusyResponse.serializer(), DirBusyResponse(false))
    }

    @After fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(dir: String? = "/p/clauderc-app") = GitViewModel("m-1", dir, relay, Fleet(relay, scope))

    @Test fun mergeNodeHasTwoLinesInDifferentLaneColors() {
        val rows = timelineGeometry(sampleLog.commits, headBranch = "main")
        val merge = rows[0]
        assertTrue("merge commits are rings", merge.hollow)
        val fromNode = merge.segments.filter { it.p0 == merge.node }
        assertEquals("two lines meet at the merge node", 2, fromNode.size)
        assertEquals(setOf(0, 1), fromNode.map { it.lane }.toSet())
        for (theme in listOf(LightColors, DarkColors)) assertNotEquals(theme.lane(0), theme.lane(1))
        // The next row continues both lines from the row boundary down to lanes 0 and 1.
        val next = rows[1].segments.filter { it.p3.y == 32f }
        assertEquals(setOf(24f, 38f), next.map { it.p3.x }.toSet())
        // feat-x (lane 1) curves back into lane 0 on its way to the shared parent.
        val curve = rows[2].segments.single { it.p0 == rows[2].node }
        assertEquals(38f, curve.p0.x, 0.001f)
        assertEquals(31f, curve.p3.x, 0.001f)
        assertEquals(64f, curve.p3.y, 0.001f)
        assertFalse(rows[3].hollow)
    }

    @Test fun patchLinesCarryLineNumbers() {
        val lines = parsePatch("diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -3,3 +3,4 @@ func f\n ctx\n-old\n+new\n+more\n\\ No newline at end of file\n")
        assertEquals(listOf(DiffKind.Hunk, DiffKind.Context, DiffKind.Del, DiffKind.Add, DiffKind.Add), lines.map { it.kind })
        assertEquals(DiffLine(DiffKind.Context, "ctx", 3, 3), lines[1])
        assertEquals(DiffLine(DiffKind.Del, "old", oldNo = 4), lines[2])
        assertEquals(DiffLine(DiffKind.Add, "more", newNo = 5), lines[4])
        assertNotEquals(diffLineColors(DiffKind.Add, LightColors), diffLineColors(DiffKind.Del, LightColors))
    }

    @Test fun loadsStatusAndPagesTheTimeline() = runTest(dispatcher) {
        val vm = viewModel()
        assertEquals(GitLogRequest("/p/clauderc-app", 0), relay.sent(Method.GIT_LOG).single().decode(GitLogRequest.serializer()))
        assertEquals(5, vm.state.value.commits.size)
        assertEquals(listOf("internal/protocol/errors.go"), vm.state.value.status!!.staged.map { it.path })
        assertEquals(2, vm.state.value.status!!.unstaged.size)

        vm.loadMore()
        assertEquals(GitLogRequest("/p/clauderc-app", 1), relay.sent(Method.GIT_LOG).last().decode(GitLogRequest.serializer()))
        assertEquals(10, vm.state.value.commits.size)
        assertFalse(vm.state.value.hasMore)
    }

    @Test fun statusAndTimelineAreFetchedTogether() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        relay.on(Method.GIT_STATUS) { gate.await(); ProtocolJson.encodeToJsonElement(GitStatusResponse.serializer(), status) }
        val vm = viewModel()
        // The status request is still waiting, yet the log request is already out.
        assertEquals(1, relay.sent(Method.GIT_STATUS).size)
        assertEquals(1, relay.sent(Method.GIT_LOG).size)
        assertTrue(vm.state.value.loading)
        gate.complete(Unit)
        assertFalse(vm.state.value.loading)
        assertEquals(5, vm.state.value.commits.size)
    }

    @Test fun switchingRepositoryReloadsAndNotARepoIsShown() = runTest(dispatcher) {
        val vm = viewModel()
        relay.fail(Method.GIT_STATUS, ErrorCode.NOT_GIT_REPO, "不是 git 仓库")
        vm.setDir("/p/notes")
        assertEquals(DirRequest("/p/notes"), relay.sent(Method.GIT_STATUS).last().decode(DirRequest.serializer()))
        assertTrue(vm.state.value.notRepo)
        assertEquals("/p/notes", vm.state.value.dir)
    }

    @Test fun firstRecentDirectoryIsUsedWhenNoneIsGiven() = runTest(dispatcher) {
        val vm = viewModel(dir = null)
        assertEquals("/p/clauderc-app", vm.state.value.dir)
        assertEquals(listOf("/p/clauderc-app", "/p/notes"), vm.state.value.dirs)
    }

    @Test fun commitNeedsAMessageAndStagedFiles() = runTest(dispatcher) {
        val vm = viewModel()
        assertFalse(vm.state.value.canCommit)
        vm.setMessage("定义协议错误码")
        assertTrue(vm.state.value.canCommit)
    }

    @Test fun busyDirectoryAsksBeforeWriting() = runTest(dispatcher) {
        relay.respond(Method.DIR_BUSY, DirBusyResponse.serializer(), DirBusyResponse(true))
        relay.respond(Method.GIT_COMMIT, GitCommitResponse.serializer(), GitCommitResponse("a1f3c92e"))
        val vm = viewModel()
        vm.setMessage("定义协议错误码")
        vm.request(GitOp.Commit("定义协议错误码"))
        assertTrue(vm.state.value.confirm is GitConfirm.Busy)
        assertTrue(relay.sent(Method.GIT_COMMIT).isEmpty())
        vm.confirm()
        assertEquals(GitCommitRequest("/p/clauderc-app", "定义协议错误码"), relay.sent(Method.GIT_COMMIT).single().decode(GitCommitRequest.serializer()))
        assertNull(vm.state.value.confirm)
        assertEquals("", vm.state.value.message)
    }

    @Test fun failedBusyCheckAsksBeforeWriting() = runTest(dispatcher) {
        relay.fail(Method.DIR_BUSY, ErrorCode.TIMEOUT, "读取会话列表超过 30 秒未完成")
        val vm = viewModel()
        vm.request(GitOp.Pull)
        val ask = vm.state.value.confirm as GitConfirm.Busy
        assertTrue("the question says nobody knows", ask.unsure)
        assertTrue("not pulled before the user says so", relay.sent(Method.GIT_PULL).isEmpty())
        assertEquals("不确定 claude 是否在这个目录工作", busyTitle(ask.unsure))
        assertEquals("a busy answer keeps its title", "claude 正在这个目录工作", busyTitle(false))
        assertEquals("没能确认 clauderc-app 里有没有会话在改代码，现在拉取可能带上写到一半的改动。", busyText(ask.sessionName, ask.unsure, "clauderc-app", ask.op.verb))
        vm.confirm()
        assertEquals(listOf(DirRequest("/p/clauderc-app")), relay.sent(Method.GIT_PULL).map { it.decode(DirRequest.serializer()) })
        assertNull(vm.state.value.confirm)
    }

    @Test fun pushWithoutUpstreamAsksThenSetsIt() = runTest(dispatcher) {
        relay.on(Method.GIT_PUSH) { sent ->
            if (!sent.decode(GitPushRequest.serializer()).setUpstream) throw clauderc.app.net.RemoteException(ErrorCode.NEEDS_CONFIRMATION, "没有上游")
            kotlinx.serialization.json.JsonObject(emptyMap())
        }
        val vm = viewModel()
        vm.request(GitOp.Push())
        assertEquals(GitConfirm.Upstream("/p/clauderc-app", "feat-x"), vm.state.value.confirm)
        vm.confirm()
        assertEquals(GitPushRequest("/p/clauderc-app", setUpstream = true), relay.sent(Method.GIT_PUSH).last().decode(GitPushRequest.serializer()))
    }

    @Test fun failuresShowGitOutputAndRefresh() = runTest(dispatcher) {
        val raw = "hint: Diverging branches can't be fast-forwarded\nfatal: Not possible to fast-forward, aborting."
        relay.fail(Method.GIT_PULL, ErrorCode.GIT_FAILED, raw)
        val vm = viewModel()
        val before = relay.sent(Method.GIT_STATUS).size
        vm.request(GitOp.Pull)
        val f = vm.state.value.failure!!
        assertEquals("无法快进", f.title)
        assertEquals(raw, f.output)
        assertEquals("git pull --ff-only", f.command)
        assertEquals(before + 1, relay.sent(Method.GIT_STATUS).size)

        relay.fail(Method.GIT_PUSH, ErrorCode.GIT_CREDENTIALS_MISSING, "fatal: could not read Username")
        vm.dismissFailure()
        vm.request(GitOp.Push())
        assertEquals("目标机缺少 git 凭证", vm.state.value.failure!!.title)
    }

    @Test fun aLateStatusForThePreviousDirectoryIsDropped() = runTest(dispatcher) {
        val gateA = kotlinx.coroutines.CompletableDeferred<Unit>()
        val statusA = status.copy(branch = "branch-a")
        val statusB = status.copy(branch = "branch-b")
        relay.on(Method.GIT_STATUS) { sent ->
            val dir = sent.decode(DirRequest.serializer()).dir
            if (dir == "/p/a") gateA.await()
            ProtocolJson.encodeToJsonElement(GitStatusResponse.serializer(), if (dir == "/p/a") statusA else statusB)
        }
        val vm = viewModel(dir = "/p/a")
        vm.setDir("/p/b")
        assertEquals("branch-b", vm.state.value.status?.branch)
        gateA.complete(Unit)
        assertEquals("B keeps its own status after A answers late", "branch-b", vm.state.value.status?.branch)
        assertEquals("/p/b", vm.state.value.dir)
    }

    @Test fun interleavedRefreshAndLoadMoreProduceNoDuplicateCommits() = runTest(dispatcher) {
        val page1Gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var page1Calls = 0
        relay.on(Method.GIT_LOG) { sent ->
            val req = sent.decode(GitLogRequest.serializer())
            if (req.page == 0) {
                ProtocolJson.encodeToJsonElement(GitLogResponse.serializer(), sampleLog.copy(hasMore = true))
            } else {
                page1Calls++
                if (page1Calls == 1) page1Gate.await()
                // Repeat the last commit of page 0 at the seam of page 1, as happens when a new commit lands meanwhile.
                val overlap = listOf(sampleLog.commits.last()) + sampleLog.commits.map { it.copy(hash = "${it.hash}-p$page1Calls") }
                ProtocolJson.encodeToJsonElement(GitLogResponse.serializer(), GitLogResponse(overlap, hasMore = false))
            }
        }
        val vm = viewModel()
        vm.loadMore()
        assertTrue(vm.state.value.loadingMore)
        // Refresh completes while page 1 is still out; page 1 is re-asked for the new timeline and the stale one is dropped.
        vm.refresh()
        page1Gate.complete(Unit)
        val hashes = vm.state.value.commits.map { it.hash }
        assertEquals("no duplicate hashes", hashes.distinct().size, hashes.size)
        assertEquals(10, hashes.size)
        assertFalse(vm.state.value.loadingMore)
    }

    @Test fun aSecondWriteDuringDirBusyIsIgnored() = runTest(dispatcher) {
        val busyGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        relay.on(Method.DIR_BUSY) {
            busyGate.await()
            ProtocolJson.encodeToJsonElement(DirBusyResponse.serializer(), DirBusyResponse(false))
        }
        relay.respond(Method.GIT_COMMIT, GitCommitResponse.serializer(), GitCommitResponse("a1f3c92e"))
        val vm = viewModel()
        vm.setMessage("提交说明")
        vm.request(GitOp.Commit("提交说明"))
        assertTrue("running starts as soon as the tap lands", vm.state.value.running)
        assertFalse(vm.state.value.canCommit)
        vm.request(GitOp.Commit("提交说明"))
        assertEquals(1, relay.sent(Method.DIR_BUSY).size)
        busyGate.complete(Unit)
        assertEquals(1, relay.sent(Method.GIT_COMMIT).size)
        assertFalse(vm.state.value.running)
    }

    @Test fun aWriteStartedInOneDirectoryRunsThereAfterSwitching() = runTest(dispatcher) {
        val busyGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        relay.on(Method.DIR_BUSY) {
            busyGate.await()
            ProtocolJson.encodeToJsonElement(DirBusyResponse.serializer(), DirBusyResponse(false))
        }
        relay.respond(Method.GIT_COMMIT, GitCommitResponse.serializer(), GitCommitResponse("a1f3c92e"))
        val vm = viewModel(dir = "/p/a")
        vm.setMessage("只在 A 提交")
        vm.request(GitOp.Commit("只在 A 提交"))
        vm.setDir("/p/b")
        busyGate.complete(Unit)
        assertEquals(GitCommitRequest("/p/a", "只在 A 提交"), relay.sent(Method.GIT_COMMIT).single().decode(GitCommitRequest.serializer()))
        assertEquals("/p/b", vm.state.value.dir)
    }

    @Test fun failedLoadMoreStopsSpinningAndRetriesWithoutDuplicates() = runTest(dispatcher) {
        var failNext = true
        relay.on(Method.GIT_LOG) { sent ->
            val req = sent.decode(GitLogRequest.serializer())
            if (req.page == 0) {
                ProtocolJson.encodeToJsonElement(GitLogResponse.serializer(), sampleLog.copy(hasMore = true))
            } else if (failNext) {
                throw clauderc.app.net.RemoteException(ErrorCode.TIMEOUT, "timeout")
            } else {
                val overlap = listOf(sampleLog.commits.last()) + sampleLog.commits.map { it.copy(hash = it.hash + "-2") }
                ProtocolJson.encodeToJsonElement(GitLogResponse.serializer(), GitLogResponse(overlap, hasMore = false))
            }
        }
        val vm = viewModel()
        vm.loadMore()
        assertFalse(vm.state.value.loadingMore)
        assertTrue(vm.state.value.moreFailed)
        assertTrue(vm.state.value.hasMore)

        failNext = false
        vm.loadMore()
        assertFalse(vm.state.value.loadingMore)
        assertFalse(vm.state.value.moreFailed)
        assertFalse(vm.state.value.hasMore)
        val hashes = vm.state.value.commits.map { it.hash }
        assertEquals(hashes.distinct().size, hashes.size)
        assertEquals(10, hashes.size)
    }

    @Test fun gitCommitAndDiffPagesLoadOnceConnected() = runTest(dispatcher) {
        val show = clauderc.app.protocol.GitShowResponse("a1f3c92", emptyList(), "林舟", clauderc.app.testing.T0, "msg", emptyList())
        val diff = clauderc.app.protocol.GitDiffResponse("AGENTS.md", patch = "@@ -1 +1 @@\n-a\n+b\n")
        relay.respond(Method.GIT_SHOW, clauderc.app.protocol.GitShowResponse.serializer(), show)
        relay.respond(Method.GIT_DIFF, clauderc.app.protocol.GitDiffResponse.serializer(), diff)
        relay.state.value = clauderc.app.net.ConnectionState.Waiting(1, 0)

        val git = viewModel(dir = null)
        val commit = CommitViewModel("m-1", "/p/clauderc-app", "a1f3c92", relay)
        val diffVm = DiffViewModel("m-1", clauderc.app.protocol.GitDiffRequest("/p/clauderc-app", "AGENTS.md"), relay)
        assertTrue("nothing sent while disconnected", relay.sent(Method.DIRS_RECENT).isEmpty())
        assertTrue(relay.sent(Method.GIT_STATUS).isEmpty())
        assertTrue(relay.sent(Method.GIT_SHOW).isEmpty())
        assertTrue(relay.sent(Method.GIT_DIFF).isEmpty())
        assertNull(commit.state.value.error)
        assertNull(diffVm.state.value.error)

        relay.state.value = clauderc.app.net.ConnectionState.Connected
        assertEquals("/p/clauderc-app", git.state.value.dir)
        assertEquals(5, git.state.value.commits.size)
        assertEquals(show, commit.state.value.value)
        assertEquals(diff, diffVm.state.value.value)
    }
}
