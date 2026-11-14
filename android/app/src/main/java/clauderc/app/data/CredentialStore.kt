package clauderc.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import clauderc.app.protocol.ProtocolJson
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** The long-lived device credential obtained by pairing. */
@Serializable
data class Credential(val relay: String, val deviceId: String, val token: String)

/** Encrypts small secrets before they reach disk; tests substitute a fake. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray): ByteArray
}

/** AES-GCM with a non-exportable key in the Android Keystore (design D10). Output is IV + ciphertext. */
class KeystoreCipher(private val alias: String = "clauderc.credential") : SecretCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private companion object {
        const val IV_BYTES = 12
    }
}

/**
 * Keeps one credential per paired relay, encrypted in DataStore; tokens never touch disk
 * in plain text. A phone paired before relays could be added has a single credential
 * under the old key, read as a list of one and moved to the new key on the next write.
 */
class CredentialStore(private val store: DataStore<Preferences>, private val cipher: SecretCipher) {
    private val legacyKey = stringPreferencesKey("credential")
    private val key = stringPreferencesKey("credentials")
    private val listSerializer = ListSerializer(Credential.serializer())

    val credentials: Flow<List<Credential>> = store.data.map(::read)

    suspend fun all(): List<Credential> = credentials.first()

    /** Adds [value], replacing the credential of the same relay. */
    suspend fun add(value: Credential) = edit { list -> list.filter { it.relay != value.relay } + value }

    /** Forgets the credential of [relay], e.g. after it revoked this phone. */
    suspend fun remove(relay: String) = edit { list -> list.filter { it.relay != relay } }

    suspend fun clear() {
        store.edit {
            it.remove(key)
            it.remove(legacyKey)
        }
    }

    private suspend fun edit(change: (List<Credential>) -> List<Credential>) {
        store.edit { prefs ->
            val next = change(read(prefs))
            prefs.remove(legacyKey)
            if (next.isEmpty()) prefs.remove(key) else prefs[key] = seal(ProtocolJson.encodeToString(listSerializer, next))
        }
    }

    private fun read(prefs: Preferences): List<Credential> =
        prefs[key]?.let { open(it)?.let { text -> runCatching { ProtocolJson.decodeFromString(listSerializer, text) }.getOrNull() } }
            ?: prefs[legacyKey]?.let { open(it)?.let { text -> runCatching { ProtocolJson.decodeFromString(Credential.serializer(), text) }.getOrNull() } }?.let(::listOf)
            ?: emptyList()

    private fun seal(plain: String): String = Base64.getEncoder().encodeToString(cipher.encrypt(plain.encodeToByteArray()))

    private fun open(sealed: String): String? = runCatching { cipher.decrypt(Base64.getDecoder().decode(sealed)).decodeToString() }.getOrNull()
}
