package clauderc.app.net

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.testing.TestLifecycleOwner
import clauderc.app.protocol.Envelope
import clauderc.app.protocol.ErrorBody
import clauderc.app.protocol.Kind
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Task 11.4, against a TLS MockWebServer standing in for the relay. */
class RelayConnectionTest {

    /** The relay side of one WebSocket. */
    private class Peer : WebSocketListener() {
        val opened = LinkedBlockingQueue<WebSocket>()
        val messages = LinkedBlockingQueue<Envelope>()
        val closed = LinkedBlockingQueue<Int>()
        lateinit var socket: WebSocket
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            opened.add(webSocket)
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            messages.add(Envelope.decode(text))
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            closed.add(code)
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed.add(-1)
        }
        fun awaitOpen() = requireNotNull(opened.poll(5, TimeUnit.SECONDS)) { "relay side never opened" }
        fun nextMessage() = requireNotNull(messages.poll(5, TimeUnit.SECONDS)) { "no message reached the relay" }
    }

    private val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setUp() {
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.start()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
    }

    @After fun tearDown() {
        scope.cancel()
        server.close()
    }

    private val address get() = RelayAddress.parse("wss://localhost:${server.port}")

    private fun connection() = RelayConnection(client, scope, reconnectDelaysMillis = listOf(50, 100), requestTimeoutMillis = 5_000)

    private fun upgrade(peer: Peer) = MockResponse.Builder().webSocketUpgrade(peer).build()

    private suspend fun RelayConnection.awaitState(predicate: (ConnectionState) -> Boolean) =
        withTimeout(5_000) { state.first(predicate) }

    private fun connected(conn: RelayConnection) = runBlocking {
        conn.setCredential(address, "device-token")
        conn.setForeground(true)
        conn.awaitState { it == ConnectionState.Connected }
    }

    @Test fun pairsResponsesWithRequestsById() = runBlocking {
        val peer = Peer()
        server.enqueue(upgrade(peer))
        val conn = connection()
        connected(conn)
        peer.awaitOpen()

        val a = async(Dispatchers.Default) { conn.request("git.status", "m-1", buildJsonObject { put("dir", "/a") }) }
        val b = async(Dispatchers.Default) { conn.request("git.status", "m-1", buildJsonObject { put("dir", "/b") }) }
        val first = peer.nextMessage()
        val second = peer.nextMessage()
        assertEquals("m-1", first.machine)
        assertTrue(first.id != second.id)
        // Answer out of order; each caller must still get its own body.
        for (env in listOf(second, first)) {
            val dir = env.body!!.jsonObject["dir"]!!
            peer.socket.send(Envelope(kind = Kind.RESPONSE, id = env.id, method = env.method, body = buildJsonObject { put("echo", dir) }).encode())
        }
        assertEquals("/a", a.await().jsonObject["echo"]!!.jsonPrimitive.content)
        assertEquals("/b", b.await().jsonObject["echo"]!!.jsonPrimitive.content)

        val upgradeRequest = server.takeRequest()
        assertEquals("Bearer device-token", upgradeRequest.headers["Authorization"])
        assertEquals("/ws/app", upgradeRequest.url.encodedPath)
    }

    @Test fun errorResponsesThrowRemoteException() = runBlocking {
        val peer = Peer()
        server.enqueue(upgrade(peer))
        val conn = connection()
        connected(conn)
        peer.awaitOpen()
        val call = async(Dispatchers.Default) { runCatching { conn.request("git.push", "m-1", null) } }
        val req = peer.nextMessage()
        peer.socket.send(Envelope(kind = Kind.RESPONSE, id = req.id, method = req.method, error = ErrorBody("needsConfirmation", "没有上游")).encode())
        val error = call.await().exceptionOrNull() as RemoteException
        assertEquals("needsConfirmation", error.code)
    }

    // A message with pictures passes its own, longer wait; the others keep the connection's.
    @Test fun aRequestCanWaitLongerThanUsual() = runBlocking {
        val peer = Peer()
        server.enqueue(upgrade(peer))
        val conn = RelayConnection(client, scope, reconnectDelaysMillis = listOf(50, 100), requestTimeoutMillis = 200)
        connected(conn)
        peer.awaitOpen()
        val usual = async(Dispatchers.Default) { runCatching { conn.request("chat.send", "m-1", JsonPrimitive("usual")) } }
        val longer = async(Dispatchers.Default) { runCatching { conn.request("chat.send", "m-1", JsonPrimitive("longer"), timeoutMillis = 2_000) } }
        val sent = listOf(peer.nextMessage(), peer.nextMessage())
        delay(600)
        for (env in sent) peer.socket.send(Envelope(kind = Kind.RESPONSE, id = env.id, method = env.method, body = env.body).encode())
        assertTrue(usual.await().exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
        assertEquals(JsonPrimitive("longer"), longer.await().getOrThrow())
    }

    @Test fun eventsReachTheEventFlow() = runBlocking {
        val peer = Peer()
        server.enqueue(upgrade(peer))
        val conn = connection()
        connected(conn)
        peer.awaitOpen()
        val evt = async(Dispatchers.Default) { withTimeout(5_000) { conn.events.first() } }
        delay(100)
        peer.socket.send(Envelope(kind = Kind.EVENT, method = "machine.status", body = JsonPrimitive("x")).encode())
        assertEquals("machine.status", evt.await().method)
    }

    @Test fun reconnectsAfterTheRelayDrops() = runBlocking {
        val first = Peer()
        val second = Peer()
        server.enqueue(upgrade(first))
        server.enqueue(upgrade(second))
        val conn = connection()
        connected(conn)
        first.awaitOpen().close(1011, "restart")
        conn.awaitState { it is ConnectionState.Waiting }
        conn.awaitState { it == ConnectionState.Connected }
        second.awaitOpen()
        assertEquals(2, server.requestCount)
    }

    @Test fun requestsFailWhileDisconnected() {
        val conn = connection()
        assertThrows(NotConnectedException::class.java) { runBlocking { conn.request("machines.list", null) } }
    }

    @Test fun revokedCloseCodeStopsReconnecting() = runBlocking {
        val peer = Peer()
        server.enqueue(upgrade(peer))
        server.enqueue(upgrade(Peer()))
        val conn = connection()
        connected(conn)
        peer.awaitOpen().close(1008, "revoked")
        conn.awaitState { it == ConnectionState.Revoked }
        delay(300)
        assertEquals(ConnectionState.Revoked, conn.state.value)
        assertEquals(1, server.requestCount)
    }

    @Test fun unauthorizedUpgradeMeansRevoked() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("""{"code":"unauthenticated","message":"x"}""").build())
        val conn = connection()
        conn.setCredential(address, "stale-token")
        conn.setForeground(true)
        conn.awaitState { it == ConnectionState.Revoked }
        Unit
    }

    @Test fun disconnectsInBackgroundAndReconnectsInForeground() = runBlocking {
        val first = Peer()
        val second = Peer()
        server.enqueue(upgrade(first))
        server.enqueue(upgrade(second))
        val conn = connection()
        val owner = TestLifecycleOwner(Lifecycle.State.CREATED, UnconfinedTestDispatcher())
        owner.lifecycle.addObserver(ForegroundBinder(conn))
        conn.setCredential(address, "device-token")
        assertEquals(ConnectionState.Idle, conn.state.value)

        owner.currentState = Lifecycle.State.STARTED
        conn.awaitState { it == ConnectionState.Connected }
        first.awaitOpen()

        owner.currentState = Lifecycle.State.CREATED
        conn.awaitState { it == ConnectionState.Idle }
        assertEquals(1000, first.closed.poll(5, TimeUnit.SECONDS))

        owner.currentState = Lifecycle.State.STARTED
        conn.awaitState { it == ConnectionState.Connected }
        second.awaitOpen()
        assertEquals(2, server.requestCount)
    }

    @Test fun rejectsPlaintextAddresses() {
        assertThrows(InsecureAddressException::class.java) { RelayAddress.parse("ws://relay.example.com") }
        assertThrows(InsecureAddressException::class.java) { RelayAddress.parse("http://relay.example.com") }
        assertThrows(InsecureAddressException::class.java) { RelayAddress.parse("relay.example.com") }
        val wss = RelayAddress.parse("wss://relay.example.com/")
        assertEquals("wss://relay.example.com/ws/app", wss.appSocketUrl)
        assertEquals("https://relay.example.com/api/pair", wss.pairUrl)
        assertEquals("wss://relay.example.com/ws/app", RelayAddress.parse("https://relay.example.com").appSocketUrl)
    }
}
