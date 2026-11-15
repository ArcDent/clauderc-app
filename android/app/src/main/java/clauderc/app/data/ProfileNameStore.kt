package clauderc.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * What the user set for profile groups on this phone, per machine id and profile id
 * (design D10): custom names, and which groups and directories are folded on the session page.
 */
class ProfileNameStore(private val store: DataStore<Preferences>) {
    private fun key(machineId: String, profileId: String) = stringPreferencesKey("profileName|$machineId|$profileId")

    /** Profile id to custom name, for one machine. */
    fun names(machineId: String): Flow<Map<String, String>> = store.data.map { prefs ->
        val prefix = "profileName|$machineId|"
        prefs.asMap().entries
            .filter { it.key.name.startsWith(prefix) }
            .associate { it.key.name.removePrefix(prefix) to it.value as String }
    }

    private fun foldKey(machineId: String) = stringSetPreferencesKey("collapsed|$machineId")

    /** Profile ids whose group is folded on this machine's session page. */
    fun collapsed(machineId: String): Flow<Set<String>> = store.data.map { it[foldKey(machineId)].orEmpty() }

    suspend fun setCollapsed(machineId: String, profileId: String, collapsed: Boolean) {
        store.edit { prefs ->
            val k = foldKey(machineId)
            val next = prefs[k].orEmpty().let { if (collapsed) it + profileId else it - profileId }
            if (next.isEmpty()) prefs.remove(k) else prefs[k] = next
        }
    }

    private fun dirFoldPrefix(machineId: String) = "collapsedDirs|$machineId|"

    /** Profile id to the working directories folded inside its group on this machine's session page. */
    fun collapsedDirs(machineId: String): Flow<Map<String, Set<String>>> = store.data.map { prefs ->
        val prefix = dirFoldPrefix(machineId)
        prefs.asMap().entries
            .filter { it.key.name.startsWith(prefix) }
            .associate { e -> e.key.name.removePrefix(prefix) to (e.value as Set<*>).filterIsInstance<String>().toSet() }
    }

    suspend fun setDirCollapsed(machineId: String, profileId: String, cwd: String, collapsed: Boolean) {
        store.edit { prefs ->
            val k = stringSetPreferencesKey(dirFoldPrefix(machineId) + profileId)
            val next = prefs[k].orEmpty().let { if (collapsed) it + cwd else it - cwd }
            if (next.isEmpty()) prefs.remove(k) else prefs[k] = next
        }
    }

    /** A blank name restores the default. */
    suspend fun rename(machineId: String, profileId: String, name: String?) {
        store.edit { prefs ->
            val k = key(machineId, profileId)
            if (name.isNullOrBlank()) prefs.remove(k) else prefs[k] = name.trim()
        }
    }
}
