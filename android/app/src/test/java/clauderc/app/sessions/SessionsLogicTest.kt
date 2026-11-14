package clauderc.app.sessions

import clauderc.app.data.Fleet
import clauderc.app.machines.MachinesViewModel
import clauderc.app.net.ConnectionState
import clauderc.app.net.RelayLink
import clauderc.app.protocol.DirsRecentResponse
import clauderc.app.protocol.ErrorCode
import clauderc.app.protocol.Machine
import clauderc.app.protocol.MachinesListResponse
import clauderc.app.protocol.Method
import clauderc.app.protocol.ProfileKind
import clauderc.app.protocol.SessionCreateRequest
import clauderc.app.protocol.SessionCreateResponse
import clauderc.app.protocol.SessionList
import clauderc.app.protocol.SessionState
import clauderc.app.testing.FakeRelay
import clauderc.app.testing.decode
import clauderc.app.testing.eventually
import clauderc.app.testing.machine
import clauderc.app.testing.profileNames
import clauderc.app.testing.sampleSessions
import clauderc.app.testing.session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Tasks 12.1 to 12.4: view model and grouping logic. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsLogicTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher + Job())
    private val relay = FakeRelay()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        relay.respond(Method.MACHINES_LIST, MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-1", "工作室 Mac mini"), machine("m-2", "dev-linux", online = false))))
        relay.respond(Method.SESSIONS_SUBSCRIBE, SessionList.serializer(), sampleSessions)
    }

    @After fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun groupsByProfileWithPendingFirstAndEmptyGroupsKept() {
        val groups = groupSessions(sampleSessions, emptyMap())
        assertEquals(listOf("默认", "api.anthropic.com", "anyrouter.top", "其他"), groups.map { it.title })
        assertEquals(listOf("s-need", "s-work", "s-idle"), groups[0].sessions.map { it.id })
        assertEquals(listOf("s-q", "s-stop"), groups[1].sessions.map { it.id })
        assertTrue("an empty profile still shows", groups[2].sessions.isEmpty())
        assertTrue(groups[1].titleIsHost)
        assertFalse(groups[3].canCreate)
        assertEquals("读不到启动参数", groups[3].note)
    }

    @Test fun sessionsWithAnUnknownProfileLandInOther() {
        val list = SessionList(listOf(session("x", "x", SessionState.Idle, "gone.example.com")), sampleSessions.profiles.filter { it.kind != ProfileKind.Other })
        val groups = groupSessions(list, emptyMap())
        assertEquals("其他", groups.last().title)
        assertEquals(listOf("x"), groups.last().sessions.map { it.id })
    }

    @Test fun machineListShowsCountsAndFollowsStatusEvents() = runTest(dispatcher) {
        val fleet = Fleet(relay, scope)
        val vm = MachinesViewModel(fleet, flowOf(listOf(RelayLink("wss://relay.example.com", "relay.example.com", ConnectionState.Connected))))
        val rows = vm.state.value.rows!!
        assertEquals(listOf("m-1", "m-2"), rows.map { it.machine.id })
        assertEquals(6, rows[0].sessions)
        assertEquals(2, rows[0].pending)
        assertNull("offline machines are not subscribed", rows[1].sessions)
        assertEquals(listOf("m-1"), relay.sent(Method.SESSIONS_SUBSCRIBE).map { it.machine })

        relay.event(Method.MACHINE_STATUS, null, Machine.serializer(), machine("m-1", "工作室 Mac mini", online = false))
        assertFalse(vm.state.value.rows!!.first { it.machine.id == "m-1" }.machine.online)
    }

    @Test fun stopAndRemoveShowAtOnceAndUndoWhenRefused() = runTest(dispatcher) {
        val fleet = Fleet(relay, scope)
        val vm = SessionsViewModel("m-1", fleet, relay, profileNames(tmp.root, scope))
        withTimeout(5_000) { vm.state.first { it.groups != null } }
        fun session(id: String) = vm.state.value.groups!!.flatMap { it.sessions }.firstOrNull { it.id == id }
        val work = session("s-work")!!

        val stopGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        relay.on(Method.SESSION_STOP) { stopGate.await(); throw clauderc.app.net.RemoteException("internal", "停不下来") }
        vm.stop(work)
        assertEquals(SessionState.Stopped, session("s-work")!!.state)
        stopGate.complete(Unit)
        assertEquals("refused: as it was", work.state, session("s-work")!!.state)

        val removeGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        relay.on(Method.SESSION_REMOVE) { removeGate.await(); kotlinx.serialization.json.JsonObject(emptyMap()) }
        vm.remove(work)
        assertNull("the row goes at once", session("s-work"))
        removeGate.complete(Unit)
    }

    @Test fun sessionsChangedUpdatesTheListAndRefreshResubscribes() = runTest(dispatcher) {
        val fleet = Fleet(relay, scope)
        val vm = SessionsViewModel("m-1", fleet, relay, profileNames(tmp.root, scope))
        withTimeout(5_000) { vm.state.first { it.groups != null } }
        assertEquals(3, vm.state.value.groups!![0].sessions.size)

        val changed = sampleSessions.copy(sessions = sampleSessions.sessions.map { if (it.id == "s-work") it.copy(state = SessionState.Idle) else it })
        relay.event(Method.SESSIONS_CHANGED, "m-1", SessionList.serializer(), changed)
        assertEquals(SessionState.Idle, vm.state.value.groups!![0].sessions.first { it.id == "s-work" }.state)

        val before = relay.sent(Method.SESSIONS_SUBSCRIBE).size
        vm.refresh()
        assertEquals(before + 1, relay.sent(Method.SESSIONS_SUBSCRIBE).size)
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun renamedProfileSurvivesRecreatingTheViewModelAndStaysPerMachine() = runTest(dispatcher) {
        val storeScope = CoroutineScope(Dispatchers.IO + Job())
        val names = profileNames(tmp.root, storeScope)
        val fleet = Fleet(relay, scope)
        relay.respond(Method.SESSIONS_SUBSCRIBE, SessionList.serializer(), sampleSessions)
        fleet.refresh("m-2")

        SessionsViewModel("m-1", fleet, relay, names).rename("anyrouter.top", "any")
        val again = SessionsViewModel("m-1", fleet, relay, names)
        val titles = eventually { again.state.first { s -> s.groups?.any { it.title == "any" } == true } }.groups!!.map { it.title }
        assertEquals(listOf("默认", "api.anthropic.com", "any", "其他"), titles)
        assertEquals("anyrouter.top", again.state.value.groups!![2].note)

        val other = SessionsViewModel("m-2", fleet, relay, names)
        assertEquals("anyrouter.top", eventually { other.state.first { it.groups != null } }.groups!![2].title)

        // A new store on the same file, as after an app restart.
        storeScope.cancel()
        val restarted = profileNames(tmp.root, CoroutineScope(Dispatchers.IO + Job()))
        assertEquals("any", eventually { restarted.names("m-1").first { it.isNotEmpty() } }["anyrouter.top"])
    }

    @Test fun createSendsTheChosenParameters() = runTest(dispatcher) {
        relay.respond(Method.DIRS_RECENT, DirsRecentResponse.serializer(), DirsRecentResponse(listOf("/Users/me/github/infra", "/Users/me/notes")))
        relay.respond(Method.SESSION_CREATE, SessionCreateResponse.serializer(), SessionCreateResponse("b2"))
        val fleet = Fleet(relay, scope)
        val vm = CreateSessionViewModel("m-1", "api.anthropic.com", fleet, relay, profileNames(tmp.root, scope))
        assertEquals("迁移脚本审查", vm.state.value.templateName)
        assertEquals(listOf("/Users/me/github/infra", "/Users/me/notes"), vm.state.value.recent)

        vm.pickDir("/Users/me/notes")
        vm.setPrompt("检查测试是否通过")
        vm.submit()
        val req = relay.sent(Method.SESSION_CREATE).single()
        assertEquals("m-1", req.machine)
        assertEquals(SessionCreateRequest("api.anthropic.com", "/Users/me/notes", name = null, prompt = "检查测试是否通过"), req.decode(SessionCreateRequest.serializer()))
        assertEquals("b2", withTimeout(5_000) { vm.created.first() })

        vm.setDir("  /tmp/typed  ")
        vm.setName("check")
        vm.setPrompt("")
        vm.submit()
        assertEquals(SessionCreateRequest("api.anthropic.com", "/tmp/typed", name = "check"), relay.sent(Method.SESSION_CREATE).last().decode(SessionCreateRequest.serializer()))
    }

    @Test fun missingDirectoryShowsAnErrorUnderTheField() = runTest(dispatcher) {
        relay.fail(Method.SESSION_CREATE, ErrorCode.DIR_NOT_FOUND, "no such directory")
        val vm = CreateSessionViewModel("m-1", "default", Fleet(relay, scope), relay, profileNames(tmp.root, scope))
        vm.setDir("/Users/me/github/clauderc-ap")
        vm.submit()
        assertEquals("目标机上没有这个目录", vm.state.value.dirError)
        assertFalse(vm.state.value.submitting)
    }

    @Test fun otherProfileCannotCreate() = runTest(dispatcher) {
        val vm = CreateSessionViewModel("m-1", "other", Fleet(relay, scope), relay, profileNames(tmp.root, scope))
        vm.setDir("/tmp")
        assertFalse(vm.state.value.canSubmit)
        vm.submit()
        assertTrue(relay.sent(Method.SESSION_CREATE).isEmpty())
    }
}
