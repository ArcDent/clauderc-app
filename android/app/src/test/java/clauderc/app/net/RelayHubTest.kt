package clauderc.app.net

import clauderc.app.data.Credential
import clauderc.app.data.Fleet
import clauderc.app.protocol.MachinesListResponse
import clauderc.app.protocol.Method
import clauderc.app.protocol.ProtocolJson
import clauderc.app.protocol.SessionList
import clauderc.app.testing.FakeRelay
import clauderc.app.testing.machine
import clauderc.app.testing.sampleSessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @After fun tearDown() = scope.cancel()

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
}
