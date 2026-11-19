package clauderc.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Text typed into a session's composer and not sent yet, kept on the phone per machine id and
 * session id. Only the text: pictures waiting to go out are not kept.
 */
class DraftStore(private val store: DataStore<Preferences>) {
    private fun prefix(machineId: String) = "draft|$machineId|"
    private fun key(machineId: String, sessionId: String) = stringPreferencesKey(prefix(machineId) + sessionId)

    /** Session id to draft, for one machine. */
    fun drafts(machineId: String): Flow<Map<String, String>> = store.data.map { prefs ->
        val prefix = prefix(machineId)
        prefs.asMap().entries
            .filter { it.key.name.startsWith(prefix) }
            .associate { it.key.name.removePrefix(prefix) to it.value as String }
    }

    fun draft(machineId: String, sessionId: String): Flow<String> = store.data.map { it[key(machineId, sessionId)].orEmpty() }

    /** Blank text removes the draft. */
    suspend fun set(machineId: String, sessionId: String, text: String) {
        store.edit { prefs ->
            val k = key(machineId, sessionId)
            if (text.isBlank()) prefs.remove(k) else prefs[k] = text
        }
    }

    /** Removes the draft when it is still [text], so words typed after it was sent stay. */
    suspend fun clear(machineId: String, sessionId: String, text: String) {
        store.edit { prefs ->
            val k = key(machineId, sessionId)
            if (prefs[k]?.trim() == text.trim()) prefs.remove(k)
        }
    }
}
