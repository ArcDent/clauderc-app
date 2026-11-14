package clauderc.app.pair

import clauderc.app.net.RelayAddress
import clauderc.app.protocol.ErrorBody
import clauderc.app.protocol.PairRequest
import clauderc.app.protocol.PairResponse
import clauderc.app.protocol.ProtocolJson
import java.io.IOException
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The QR code content: clauderc://pair?relay=<wss address>&code=<code>. */
data class PairLink(val relay: String, val code: String) {
    companion object {
        private const val PREFIX = "clauderc://pair?"

        fun parse(text: String): PairLink? {
            val t = text.trim()
            if (!t.startsWith(PREFIX)) return null
            val query = t.removePrefix(PREFIX)
            // The relay sends the address unescaped, so split at the last "&code=".
            val codeAt = query.lastIndexOf("&code=")
            if (!query.startsWith("relay=") || codeAt < 0) return null
            val relay = decode(query.substring("relay=".length, codeAt))
            val code = decode(query.substring(codeAt + "&code=".length))
            if (relay.isEmpty() || code.isEmpty()) return null
            return PairLink(relay, code)
        }

        private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }
}

sealed interface PairResult {
    data class Paired(val response: PairResponse) : PairResult
    /** The relay answered with an error body, e.g. pairCodeExpired. */
    data class Refused(val error: ErrorBody) : PairResult
    data class Unreachable(val message: String) : PairResult
}

/** POST /api/pair. */
fun interface PairingApi {
    suspend fun pair(address: RelayAddress, request: PairRequest): PairResult
}

class OkHttpPairingApi(private val client: OkHttpClient) : PairingApi {
    override suspend fun pair(address: RelayAddress, request: PairRequest): PairResult = withContext(Dispatchers.IO) {
        val body = ProtocolJson.encodeToString(PairRequest.serializer(), request).toRequestBody("application/json".toMediaType())
        val call = client.newCall(Request.Builder().url(address.pairUrl).post(body).build())
        try {
            call.execute().use { res ->
                val text = res.body.string()
                if (res.isSuccessful) {
                    PairResult.Paired(ProtocolJson.decodeFromString(PairResponse.serializer(), text))
                } else {
                    val error = runCatching { ProtocolJson.decodeFromString(ErrorBody.serializer(), text) }
                        .getOrDefault(ErrorBody("http${res.code}", "中转服务器返回 ${res.code}"))
                    PairResult.Refused(error)
                }
            }
        } catch (e: IOException) {
            PairResult.Unreachable(e.message ?: "连不上中转服务器")
        }
    }
}
