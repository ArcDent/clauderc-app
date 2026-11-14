package clauderc.app

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.preferencesDataStore
import clauderc.app.chat.ChatCache
import clauderc.app.data.CredentialStore
import clauderc.app.data.Fleet
import clauderc.app.data.KeystoreCipher
import clauderc.app.data.ProfileNameStore
import clauderc.app.net.RelayAddress
import clauderc.app.net.RelayConnection
import clauderc.app.net.RelayHub
import clauderc.app.pair.OkHttpPairingApi
import clauderc.app.pair.PairingApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

private val Context.credentialData by preferencesDataStore("credential")
private val Context.settingsData by preferencesDataStore("settings")

enum class MainTab { Machines, Sessions, Git }

/** What the three top-level tabs share: the open tab, the chosen machine and each machine's git directory. */
class Selection {
    val tab = MutableStateFlow(MainTab.Machines)
    val machineId = MutableStateFlow<String?>(null)
    val gitDirs = MutableStateFlow<Map<String, String>>(emptyMap())

    fun openSessions(machineId: String) {
        this.machineId.value = machineId
        tab.value = MainTab.Sessions
    }

    fun setGitDir(machineId: String, dir: String) = gitDirs.update { it + (machineId to dir) }

    fun openGit(machineId: String, dir: String) {
        this.machineId.value = machineId
        setGitDir(machineId, dir)
        tab.value = MainTab.Git
    }
}

/** Hand-wired dependencies for the whole app. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()
    val credentials = CredentialStore(context.credentialData, KeystoreCipher())

    /** One connection per paired relay; view models talk to it as a single relay. */
    val connection = RelayHub(scope) { c ->
        runCatching { RelayAddress.parse(c.relay) }.getOrNull()?.let { address ->
            RelayConnection(http, scope).apply { setCredential(address, c.token) }
        }
    }
    val profileNames = ProfileNameStore(context.settingsData)
    val pairing: PairingApi = OkHttpPairingApi(http)
    val fleet = Fleet(connection, scope)
    val selection = Selection()
    val chatCache = ChatCache()

    private val _notices = Channel<String>(Channel.BUFFERED)

    /** Short notes for the machine page, e.g. a relay that revoked this phone; kept until the page reads them. */
    val notices: Flow<String> = _notices.receiveAsFlow()

    fun notice(text: String) {
        _notices.trySend(text)
    }

    init {
        scope.launch { credentials.credentials.collect(connection::setCredentials) }
        // A relay that refuses the token forgets this phone; drop only that credential (machine-access spec).
        scope.launch {
            connection.revoked.collect { relay ->
                credentials.remove(relay)
                notice("${runCatching { RelayAddress.parse(relay).host }.getOrDefault(relay)} 吊销了这台手机的访问")
            }
        }
    }
}

/** The name the relay lists for this phone, e.g. 「Pixel 9」. */
fun deviceName(): String {
    val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
    val model = Build.MODEL.orEmpty()
    return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
}
