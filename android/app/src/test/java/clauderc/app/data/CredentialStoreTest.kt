package clauderc.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import clauderc.app.protocol.ProtocolJson
import java.util.Base64
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Task 11.5: the stored credential never contains the token in plain text. */
class CredentialStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Stands in for the Keystore cipher: reversible, but hides the bytes. */
    private object XorCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }
        override fun decrypt(sealed: ByteArray) = encrypt(sealed)
    }

    private val credential = Credential(
        relay = "wss://relay.example.com",
        deviceId = "d-4be17a03",
        token = "r3Jb0vQ5mZ8yX2kLwN7pT1sD4fG6hJ9aC0eU3iO5qWE",
    )

    @Test fun savesEncryptedAndReadsBack() = runTest {
        val file = File(tmp.root, "credential.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        val store = CredentialStore(dataStore, XorCipher)

        store.add(credential)

        val stored = dataStore.data.first().asMap().values.joinToString()
        assertFalse("token must not be stored in plain text", stored.contains(credential.token))
        assertFalse(file.readBytes().decodeToString().contains(credential.token))
        assertFalse(stored.contains(credential.deviceId))
        assertEquals(listOf(credential), store.all())

        store.clear()
        assertEquals(emptyList<Credential>(), store.all())
    }

    @Test fun keepsOneCredentialPerRelay() = runTest {
        val file = File(tmp.root, "many.preferences_pb")
        val store = CredentialStore(PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }), XorCipher)
        val home = Credential("wss://home.example.net", "d-9", "home-token")
        store.add(credential)
        store.add(home)
        store.add(credential.copy(deviceId = "d-new", token = "new-token"))
        assertEquals(listOf(home, credential.copy(deviceId = "d-new", token = "new-token")), store.all())
        store.remove(home.relay)
        assertEquals(listOf("wss://relay.example.com"), store.all().map { it.relay })
    }

    @Test fun theOldSingleCredentialReadsAsAListOfOne() = runTest {
        val file = File(tmp.root, "legacy.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        // What a phone paired before multiple relays has on disk.
        val plain = ProtocolJson.encodeToString(Credential.serializer(), credential).encodeToByteArray()
        dataStore.edit { it[stringPreferencesKey("credential")] = Base64.getEncoder().encodeToString(XorCipher.encrypt(plain)) }
        val store = CredentialStore(dataStore, XorCipher)
        assertEquals(listOf(credential), store.all())

        val home = Credential("wss://home.example.net", "d-9", "home-token")
        store.add(home)
        assertEquals(listOf(credential, home), store.all())
        assertNull("the old key is gone after the first write", dataStore.data.first()[stringPreferencesKey("credential")])
    }

    @Test fun unreadableValueIsTreatedAsMissing() = runTest {
        val file = File(tmp.root, "broken.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        CredentialStore(dataStore, XorCipher).add(credential)
        val failing = object : SecretCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(sealed: ByteArray): ByteArray = error("key was wiped")
        }
        assertEquals(emptyList<Credential>(), CredentialStore(dataStore, failing).all())
    }
}
