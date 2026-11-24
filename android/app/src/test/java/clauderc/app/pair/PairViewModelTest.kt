package clauderc.app.pair

import clauderc.app.data.Credential
import clauderc.app.protocol.ErrorBody
import clauderc.app.protocol.PairRequest
import clauderc.app.protocol.PairResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Task 11.5: each pairing outcome maps to its own page state. */
@OptIn(ExperimentalCoroutinesApi::class)
class PairViewModelTest {
    private val qr = "clauderc://pair?relay=wss://relay.example.com&code=9f2c4e1a7b3d5f6081a2c3e4f5061728"
    private val requests = mutableListOf<Pair<String, PairRequest>>()
    private var saved: Credential? = null

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(result: PairResult, paired: Set<String> = emptySet()) = PairViewModel(
        api = { address, request -> requests += address.pairUrl to request; result },
        deviceName = "Pixel 9",
        onPaired = { saved = it },
        paired = { paired },
    )

    @Test fun aRelayAlreadyPairedIsNotRedeemedAgain() = runTest {
        val vm = viewModel(PairResult.Paired(PairResponse("d-2", "t-2")), paired = setOf("wss://relay.example.com"))
        vm.onScanned(qr)
        assertEquals(PairUiState.Known("relay.example.com"), vm.state.value)
        assertTrue("the one-time code stays unused", requests.isEmpty())
        assertNull(saved)
    }

    @Test fun anotherRelayIsAddedNextToTheFirst() = runTest {
        val vm = viewModel(PairResult.Paired(PairResponse("d-2", "t-2")), paired = setOf("wss://home.example.net"))
        vm.onScanned(qr)
        assertEquals(PairUiState.Paired, vm.state.value)
        assertEquals(Credential("wss://relay.example.com", "d-2", "t-2"), saved)
    }

    @Test fun successStoresTheCredential() = runTest {
        val vm = viewModel(PairResult.Paired(PairResponse("d-4be17a03", "secret-token")))
        vm.onScanned(qr)
        assertEquals(PairUiState.Paired, vm.state.value)
        assertEquals("https://relay.example.com/api/pair" to PairRequest("9f2c4e1a7b3d5f6081a2c3e4f5061728", "Pixel 9"), requests.single())
        assertEquals(Credential("wss://relay.example.com", "d-4be17a03", "secret-token"), saved)
    }

    @Test fun expiredCode() = runTest {
        val vm = viewModel(PairResult.Refused(ErrorBody("pairCodeExpired", "配对码已过期")))
        vm.onScanned(qr)
        assertEquals(PairUiState.Expired, vm.state.value)
        assertNull(saved)
    }

    @Test fun usedCode() = runTest {
        val vm = viewModel(PairResult.Refused(ErrorBody("pairCodeInvalid", "配对码无效")))
        vm.onScanned(qr)
        assertEquals(PairUiState.Used, vm.state.value)
    }

    @Test fun codeAlreadyRedeemed() = runTest {
        val vm = viewModel(PairResult.Refused(ErrorBody("pairCodeUsed", "这个二维码已经配对过，请在目标机上重新生成")))
        vm.onScanned(qr)
        assertEquals(PairUiState.Redeemed, vm.state.value)
        assertNull(saved)
    }

    @Test fun plaintextAddressIsRefusedWithoutCallingTheRelay() = runTest {
        val vm = viewModel(PairResult.Paired(PairResponse("d", "t")))
        vm.onScanned("clauderc://pair?relay=ws://relay.example.com&code=abc")
        assertEquals(PairUiState.Insecure("ws://relay.example.com"), vm.state.value)
        assertTrue(requests.isEmpty())
        assertNull(saved)
    }

    @Test fun rateLimitedAndForeignCodes() = runTest {
        val vm = viewModel(PairResult.Refused(ErrorBody("rateLimited", "")))
        vm.onScanned(qr)
        assertEquals(PairUiState.RateLimited, vm.state.value)
        vm.rescan()
        assertEquals(PairUiState.Scanning, vm.state.value)
        vm.onScanned("https://example.com")
        assertTrue(vm.state.value is PairUiState.Failed)
    }

    @Test fun parsesTheRelayQrFormat() {
        assertEquals(PairLink("wss://relay.example.com", "9f2c"), PairLink.parse("clauderc://pair?relay=wss://relay.example.com&code=9f2c"))
        assertEquals(PairLink("wss://r.example.com/a?b", "c1"), PairLink.parse("clauderc://pair?relay=wss%3A%2F%2Fr.example.com%2Fa%3Fb&code=c1"))
        assertNull(PairLink.parse("clauderc://pair?code=1"))
    }

    @Test fun pairingGoingOnStillStoresTheCredentialAndListsMachinesAfterLeaving() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<PairResult>()
        val dispatcher = UnconfinedTestDispatcher()
        val appScope = kotlinx.coroutines.CoroutineScope(dispatcher + kotlinx.coroutines.Job())
        val line = clauderc.app.testing.FakeRelay().apply {
            respond(
                clauderc.app.protocol.Method.MACHINES_LIST,
                clauderc.app.protocol.MachinesListResponse.serializer(),
                clauderc.app.protocol.MachinesListResponse(listOf(clauderc.app.testing.machine("m-1", "工作室 Mac mini"))),
            )
            respond(
                clauderc.app.protocol.Method.SESSIONS_SUBSCRIBE,
                clauderc.app.protocol.SessionList.serializer(),
                clauderc.app.testing.sampleSessions,
            )
        }
        val hub = clauderc.app.net.RelayHub(appScope) { line }
        val fleet = clauderc.app.data.Fleet(hub, appScope)
        val store = androidx.lifecycle.ViewModelStore()
        try {
            val vm = androidx.lifecycle.ViewModelProvider(store, object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = PairViewModel(
                    api = { _, _ -> gate.await() },
                    deviceName = "Pixel 9",
                    onPaired = {
                        saved = it
                        hub.setCredentials(listOf(it))
                    },
                    appScope = appScope,
                ) as T
            })[PairViewModel::class.java]
            vm.onScanned(qr)
            assertEquals(PairUiState.Pairing, vm.state.value)
            // The user goes back before the relay answers.
            store.clear()
            gate.complete(PairResult.Paired(PairResponse("d-9", "tok-9")))
            assertEquals(Credential("wss://relay.example.com", "d-9", "tok-9"), saved)
            assertEquals(listOf("m-1"), fleet.machines.value!!.map { it.id })
        } finally {
            appScope.cancel()
        }
    }
}
