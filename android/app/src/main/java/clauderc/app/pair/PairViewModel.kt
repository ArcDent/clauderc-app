package clauderc.app.pair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import clauderc.app.data.Credential
import clauderc.app.net.InsecureAddressException
import clauderc.app.net.RelayAddress
import clauderc.app.protocol.ErrorCode
import clauderc.app.protocol.PairRequest
import kotlinx.coroutines.CoroutineScope
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
    /** The code was redeemed by a pairing in the last 5 minutes (pairCodeUsed). */
    data object Redeemed : PairUiState
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
    /** Outlives the page so a pairing already sent still stores its credential if the user went back. */
    private val appScope: CoroutineScope? = null,
    /** Reports the outcome after the page has already closed. */
    private val onNotice: (String) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow<PairUiState>(PairUiState.Scanning)
    val state: StateFlow<PairUiState> = _state.asStateFlow()

    @Volatile private var cleared = false

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
        (appScope ?: viewModelScope).launch {
            if (address.wsBase in paired()) {
                _state.value = PairUiState.Known(address.host)
                if (cleared) onNotice("${address.host} 上的目标机已经都在列表里")
                return@launch
            }
            val next = when (val result = api.pair(address, PairRequest(link.code, deviceName))) {
                is PairResult.Paired -> {
                    onPaired(Credential(address.wsBase, result.response.deviceId, result.response.token))
                    if (cleared) onNotice("已配对 ${address.host}")
                    PairUiState.Paired
                }
                is PairResult.Refused -> when (result.error.code) {
                    ErrorCode.PAIR_CODE_EXPIRED -> PairUiState.Expired
                    ErrorCode.PAIR_CODE_INVALID -> PairUiState.Used
                    ErrorCode.PAIR_CODE_USED -> PairUiState.Redeemed
                    ErrorCode.RATE_LIMITED -> PairUiState.RateLimited
                    else -> PairUiState.Failed(result.error.message)
                }.also { if (cleared) onNotice(result.error.message.ifEmpty { "配对失败" }) }
                is PairResult.Unreachable -> PairUiState.Failed(result.message).also { if (cleared) onNotice(result.message) }
            }
            _state.value = next
        }
    }

    fun rescan() {
        _state.value = PairUiState.Scanning
    }

    override fun onCleared() {
        cleared = true
    }
}
