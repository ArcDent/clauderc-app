package clauderc.app.net

import clauderc.app.protocol.RelayPath
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The relay address is not https:// or wss:// (design D4: encrypted addresses only). */
class InsecureAddressException(val address: String) : IllegalArgumentException("只能连接 https:// 或 wss:// 地址：$address")

/** A relay base address, kept as both its https and wss forms. */
data class RelayAddress(val httpBase: String, val wsBase: String) {
    val appSocketUrl: String get() = wsBase + RelayPath.APP_WS
    val pairUrl: String get() = httpBase + RelayPath.PAIR
    val host: String get() = httpBase.toHttpUrlOrNull()?.host ?: httpBase

    companion object {
        /** Accepts `wss://` and `https://` only; anything else throws [InsecureAddressException]. */
        fun parse(raw: String): RelayAddress {
            val text = raw.trim().trimEnd('/')
            val scheme = text.substringBefore("://", missingDelimiterValue = "").lowercase()
            val rest = text.substringAfter("://", missingDelimiterValue = "")
            if (scheme != "wss" && scheme != "https") throw InsecureAddressException(raw)
            require(rest.isNotEmpty() && "https://$rest".toHttpUrlOrNull() != null) { "无效的中转服务器地址：$raw" }
            return RelayAddress(httpBase = "https://$rest", wsBase = "wss://$rest")
        }
    }
}
