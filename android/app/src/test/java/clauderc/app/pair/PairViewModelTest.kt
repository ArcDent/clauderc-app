package clauderc.app.pair

import clauderc.app.data.Credential
import clauderc.app.protocol.ErrorBody
import clauderc.app.protocol.PairRequest
import clauderc.app.protocol.PairResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
}
