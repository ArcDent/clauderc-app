package clauderc.app.net

import clauderc.app.data.Credential
import clauderc.app.data.Fleet
import clauderc.app.protocol.Machine
import clauderc.app.protocol.MachinesListResponse
import clauderc.app.protocol.Method
import clauderc.app.protocol.ProtocolJson
import clauderc.app.protocol.SessionList
import clauderc.app.testing.FakeRelay
import clauderc.app.testing.machine
import clauderc.app.testing.sampleSessions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A phone paired with two relays sees one merged machine list and reaches each machine through its own relay. */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayHubTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher + Job())

    private val work = Credential("wss://relay.example.com", "d-1", "t-1")
    private val home = Credential("wss://home.example.net", "d-2", "t-2")
    private val lines = mapOf(work.relay to FakeRelay(), home.relay to FakeRelay())
    private val a = lines.getValue(work.relay)
    private val b = lines.getValue(home.relay)
    private val hub = RelayHub(scope) { lines[it.relay] }

    init {
        a.respond(Method.MACHINES_LIST, MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-1", "工作室 Mac mini"))))
        b.respond(Method.MACHINES_LIST, MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-9", "家里的 iMac"))))
        listOf(a, b).forEach { it.respond(Method.SESSIONS_SUBSCRIBE, SessionList.serializer(), sampleSessions) }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private suspend fun machines(): List<String> =
        ProtocolJson.decodeFromJsonElement(MachinesListResponse.serializer(), hub.request(Method.MACHINES_LIST, null)).machines.map { it.id }

    @Test fun machinesOfBothRelaysAreMergedAndRequestsFollowTheirRelay() = runTest(dispatcher) {
        hub.setCredentials(listOf(work, home))
        assertEquals(listOf("m-1", "m-9"), machines())

        hub.request(Method.SESSIONS_SUBSCRIBE, "m-9")
        assertEquals(listOf("m-9"), b.sent(Method.SESSIONS_SUBSCRIBE).map { it.machine })
        assertTrue("the other relay never sees it", a.sent(Method.SESSIONS_SUBSCRIBE).none { it.machine == "m-9" })
        assertEquals(listOf("relay.example.com", "home.example.net"), hub.links.value.map { it.host })
    }

    @Test fun fleetListsEveryRelayAndDropsARemovedOne() = runTest(dispatcher) {
        val fleet = Fleet(hub, scope)
        hub.setCredentials(listOf(work, home))
        assertEquals(listOf("m-1", "m-9"), fleet.machines.value!!.map { it.id })
        hub.setCredentials(listOf(home))
        assertTrue(a.closed)
        assertEquals(listOf("m-9"), fleet.machines.value!!.map { it.id })
        hub.setCredentials(emptyList())
        assertEquals("no relay left: an empty list, not a spinner", emptyList<String>(), fleet.machines.value!!.map { it.id })
    }

    @Test fun onlyTheRevokingRelayIsReported() = runTest(dispatcher) {
        hub.setCredentials(listOf(work, home))
        val revoked = mutableListOf<String>()
        backgroundScope.launch(dispatcher) { hub.revoked.collect { revoked += it } }
        a.state.value = ConnectionState.Revoked
        assertEquals(listOf(work.relay), revoked)
        assertEquals(ConnectionState.Connected, hub.state.value)
    }

    @Test fun aReconnectingRelayKeepsItsLastListAndItsOwnState() = runTest(dispatcher) {
        hub.setCredentials(listOf(work, home))
        machines()
        a.state.value = ConnectionState.Waiting(1, 0)
        a.fail(Method.MACHINES_LIST, "notConnected")
        assertEquals(listOf("m-1", "m-9"), machines())
        assertEquals(ConnectionState.Waiting(1, 0), hub.stateOf("m-1").first())
        assertEquals(ConnectionState.Connected, hub.stateOf("m-9").first())
    }

    private suspend fun online(): List<Boolean> =
        ProtocolJson.decodeFromJsonElement(MachinesListResponse.serializer(), hub.request(Method.MACHINES_LIST, null)).machines.map { it.online }

    /**
     * Answers machines.list as a relay that built the list before m-1 came online but
     * sent the online event first; [seen] returns once the phone has taken the event in.
     */
    private suspend fun FakeRelay.overtaken(seen: suspend () -> Unit): JsonElement {
        event(Method.MACHINE_STATUS, null, Machine.serializer(), machine("m-1", "工作室 Mac mini"))
        seen()
        return ProtocolJson.encodeToJsonElement(MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-1", "工作室 Mac mini", online = false))))
    }

    @Test fun aStatusThatOvertakesTheListWinsInTheHub() = runTest(dispatcher) {
        hub.setCredentials(listOf(work))
        val tracked = CompletableDeferred<Unit>()
        backgroundScope.launch(dispatcher) {
            hub.events.first { it.method == Method.MACHINE_STATUS }
            tracked.complete(Unit)
        }
        a.on(Method.MACHINES_LIST) { a.overtaken { tracked.await() } }
        assertEquals("m-1 last came online", listOf(true), online())
        a.fail(Method.MACHINES_LIST, "notConnected")
        assertEquals("and stays so in the list kept for a relay that cannot answer", listOf(true), online())
    }

    @Test fun aStatusThatOvertakesTheListWinsInTheFleet() = runTest(dispatcher) {
        lateinit var fleet: Fleet
        // Fleet starts collecting events only after it sent its first machines.list.
        val collecting = CompletableDeferred<Unit>()
        a.on(Method.MACHINES_LIST) {
            collecting.await()
            a.overtaken { fleet.machines.first { list -> list.orEmpty().any { it.online } } }
        }
        fleet = Fleet(a, scope)
        collecting.complete(Unit)
        assertEquals("m-1 last came online", listOf(true), fleet.machines.value!!.map { it.online })
    }

    @Test fun foregroundReachesEveryLineIncludingLaterOnes() = runTest(dispatcher) {
        hub.setForeground(true)
        hub.setCredentials(listOf(work))
        assertEquals(true, a.foreground)
        hub.setCredentials(listOf(work, home))
        assertEquals(true, b.foreground)
        hub.setForeground(false)
        assertEquals(false, a.foreground)
        assertEquals(false, b.foreground)
    }

    @Test fun chatOpenedWithTwoRelaysBeforeMachineListWaitsUntilOwnerIsKnown() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        b.on(Method.MACHINES_LIST) {
            gate.await()
            ProtocolJson.encodeToJsonElement(MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-9", "家里的 iMac"))))
        }
        b.respond(
            Method.CHAT_OPEN,
            clauderc.app.protocol.ChatOpenResponse.serializer(),
            clauderc.app.protocol.ChatOpenResponse(items = listOf(clauderc.app.protocol.ChatItem(1, clauderc.app.protocol.ItemKind.ASSISTANT, text = "你好"))),
        )
        val fleet = Fleet(hub, scope)
        hub.setCredentials(listOf(work, home))
        assertEquals(ConnectionState.Connecting, hub.stateOf("m-9").first())
        val vm = clauderc.app.chat.ChatViewModel("m-9", "s-work", hub, fleet, scope, compress = { it }, background = dispatcher)
        assertTrue("waits until m-9's relay is known", b.sent(Method.CHAT_OPEN).isEmpty())
        assertTrue(a.sent(Method.CHAT_OPEN).isEmpty())

        gate.complete(Unit)
        assertEquals(ConnectionState.Connected, hub.stateOf("m-9").first())
        assertEquals(1, b.sent(Method.CHAT_OPEN).size)
        assertEquals(listOf("你好"), vm.state.value.items.map { it.text })
    }

    @Test fun removedRelaysMachinesDoNotComeBackWhenListFinishesLate() = runTest(dispatcher) {
        val workGate = CompletableDeferred<Unit>()
        a.on(Method.MACHINES_LIST) {
            workGate.await()
            ProtocolJson.encodeToJsonElement(MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-1", "工作室 Mac mini"))))
        }
        hub.setCredentials(listOf(work, home))
        val listing = async { machines() }
        // Remove work while its machines.list is still in flight.
        hub.setCredentials(listOf(home))
        workGate.complete(Unit)
        assertEquals(listOf("m-9"), listing.await())
        assertEquals(listOf("m-9"), machines())
    }

    @Test fun machineOfflineFailsOnlyItsReadOnlyRequests() = runTest(dispatcher) {
        a.respond(Method.MACHINES_LIST, MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-1", "mac-1"), machine("m-2", "mac-2"))))
        hub.setCredentials(listOf(work, home))
        assertEquals(listOf("m-1", "m-2", "m-9"), machines())

        val readM1Gate = CompletableDeferred<Unit>()
        val readM2Gate = CompletableDeferred<Unit>()
        val writeM1Gate = CompletableDeferred<Unit>()
        val readM9Gate = CompletableDeferred<Unit>()

        a.on(Method.CHAT_OPEN) { sent ->
            if (sent.machine == "m-1") readM1Gate.await() else readM2Gate.await()
            kotlinx.serialization.json.JsonObject(emptyMap())
        }
        a.on(Method.CHAT_SEND) {
            writeM1Gate.await()
            kotlinx.serialization.json.JsonObject(emptyMap())
        }
        b.on(Method.CHAT_OPEN) {
            readM9Gate.await()
            kotlinx.serialization.json.JsonObject(emptyMap())
        }

        val readM1 = async { runCatching { hub.request(Method.CHAT_OPEN, "m-1") } }
        val writeM1 = async { runCatching { hub.request(Method.CHAT_SEND, "m-1") } }
        val readM2 = async { runCatching { hub.request(Method.CHAT_OPEN, "m-2") } }
        val readM9 = async { runCatching { hub.request(Method.CHAT_OPEN, "m-9") } }

        // m-1 goes offline: its in-flight read-only request fails immediately; others stay alive.
        a.event(Method.MACHINE_STATUS, "m-1", Machine.serializer(), machine("m-1", "mac-1", online = false))
        val m1Err = readM1.await().exceptionOrNull() as? RemoteException
        assertEquals(clauderc.app.protocol.ErrorCode.MACHINE_OFFLINE, m1Err?.code)
        assertTrue("write request to m-1 is not aborted locally", !writeM1.isCompleted)
        assertTrue("read-only request to another machine on the same relay is untouched", !readM2.isCompleted)
        assertTrue("read-only request to a machine on another relay is untouched", !readM9.isCompleted)

        writeM1Gate.complete(Unit)
        readM2Gate.complete(Unit)
        readM9Gate.complete(Unit)
        assertTrue(writeM1.await().isSuccess)
        assertTrue(readM2.await().isSuccess)
        assertTrue(readM9.await().isSuccess)
    }
}
