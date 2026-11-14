package clauderc.app.pair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import clauderc.app.data.Credential
import clauderc.app.net.InsecureAddressException
import clauderc.app.net.RelayAddress
import clauderc.app.protocol.ErrorCode
import clauderc.app.protocol.PairRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PairUiState {
    data object Scanning : PairUiState
    data object Pairing : PairUiState
    data object Paired : PairUiState
    /** The code belongs to a relay this phone is already paired with; nothing was redeemed. */
    data class Known(val host: String) : PairUiState
    data object Expired : PairUiState
    data object Used : PairUiState
    data class Insecure(val address: String) : PairUiState
    data object RateLimited : PairUiState
    data class Failed(val message: String) : PairUiState
}

/**
 * Turns a scanned QR code into one more stored credential (task 11.5). A code of a
 * relay that is already paired is left unused. Scans are ignored unless the camera
 * page is waiting for one.
 */
class PairViewModel(
    private val api: PairingApi,
    private val deviceName: String,
    private val onPaired: suspend (Credential) -> Unit,
    /** Relay keys (wss:// bases) this phone already holds a credential for. */
    private val paired: suspend () -> Set<String> = { emptySet() },
) : ViewModel() {
    private val _state = MutableStateFlow<PairUiState>(PairUiState.Scanning)
    val state: StateFlow<PairUiState> = _state.asStateFlow()

    fun onScanned(text: String) {
        if (_state.value != PairUiState.Scanning) return
        val link = PairLink.parse(text)
        if (link == null) {
            _state.value = PairUiState.Failed("这不是 clauderc 的配对二维码")
            return
        }
        val address = try {
            RelayAddress.parse(link.relay)
        } catch (e: InsecureAddressException) {
            _state.value = PairUiState.Insecure(link.relay)
            return
        } catch (e: IllegalArgumentException) {
            _state.value = PairUiState.Failed(e.message ?: "二维码里的地址无效")
            return
        }
        _state.value = PairUiState.Pairing
        viewModelScope.launch {
            if (address.wsBase in paired()) {
                _state.value = PairUiState.Known(address.host)
                return@launch
            }
            _state.value = when (val result = api.pair(address, PairRequest(link.code, deviceName))) {
                is PairResult.Paired -> {
                    onPaired(Credential(address.wsBase, result.response.deviceId, result.response.token))
                    PairUiState.Paired
                }
                is PairResult.Refused -> when (result.error.code) {
                    ErrorCode.PAIR_CODE_EXPIRED -> PairUiState.Expired
                    ErrorCode.PAIR_CODE_INVALID -> PairUiState.Used
                    ErrorCode.RATE_LIMITED -> PairUiState.RateLimited
                    else -> PairUiState.Failed(result.error.message)
                }
                is PairResult.Unreachable -> PairUiState.Failed(result.message)
            }
        }
    }

    fun rescan() {
        _state.value = PairUiState.Scanning
    }
}
