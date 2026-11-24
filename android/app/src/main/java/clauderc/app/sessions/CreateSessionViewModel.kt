package clauderc.app.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import clauderc.app.data.Fleet
import clauderc.app.data.ProfileNameStore
import clauderc.app.net.ConnectionState
import clauderc.app.net.NotConnectedException
import clauderc.app.net.Relay
import clauderc.app.net.RemoteException
import clauderc.app.net.call
import clauderc.app.protocol.DirsRecentResponse
import clauderc.app.protocol.Empty
import clauderc.app.protocol.ErrorCode
import clauderc.app.protocol.Method
import clauderc.app.protocol.ModelTier
import clauderc.app.protocol.Profile
import clauderc.app.protocol.ProfileKind
import clauderc.app.protocol.SessionCreateRequest
import clauderc.app.protocol.SessionCreateResponse
import clauderc.app.protocol.SessionList
import clauderc.app.ui.profileTitle
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How long session.create waits: creating a session or answering a trust prompt can exceed the usual 30 s. */
const val CREATE_SESSION_TIMEOUT_MILLIS = 120_000L

/** The profile the account login's sessions fall into; as a preset it means the account login (design D8). */
const val ACCOUNT_PRESET = "api.anthropic.com"

/** The 「自己填」 choice: a new endpoint address and API key. No profile id is empty. */
const val CUSTOM_PRESET = ""

/** One choice of the new-session page's 「档位」 list. */
data class PresetOption(
    /** A profile id, [ACCOUNT_PRESET] for the account login, or [CUSTOM_PRESET]. */
    val id: String,
    val title: String,
    /** The second line: what launching with it means. */
    val sub: String,
    /** True when the title is the raw API host, shown in mono like the group header. */
    val titleIsHost: Boolean = false,
    /** What the profile's most recent session runs on; the model fields start from these. */
    val models: Map<ModelTier, String> = emptyMap(),
)

/** A field of the form that can be marked red. */
enum class CreateField { Dir, Url, Key }

/** The floating card under the bar; [serial] grows each time it is raised, so the card can nudge when raised again. */
data class FormNote(val text: String, val serial: Int)

data class CreateUiState(
    val dir: String = "",
    val recent: List<String> = emptyList(),
    val showAllDirs: Boolean = false,
    val presets: List<PresetOption> = emptyList(),
    val preset: String = ACCOUNT_PRESET,
    val baseUrl: String = "",
    val apiKey: String = "",
    val keyShown: Boolean = false,
    /** What each tier's field holds; a chosen profile fills them with its models. */
    val models: Map<ModelTier, String> = emptyMap(),
    /** The chosen profile's models, to tell edited fields apart; null while 「自己填」 is chosen. */
    val presetModels: Map<ModelTier, String>? = emptyMap(),
    val modelsOpen: Boolean = false,
    val name: String = "",
    val prompt: String = "",
    val badFields: Set<CreateField> = emptySet(),
    val badModels: Set<ModelTier> = emptySet(),
    val note: FormNote? = null,
    val submitting: Boolean = false,
    /** The directory Claude Code does not trust yet, asked about in a sheet; null when none. */
    val untrusted: String? = null,
    /** True while the agent says yes to Claude Code's trust prompt and then starts the session. */
    val trusting: Boolean = false,
) {
    val custom get() = preset == CUSTOM_PRESET
    val canSubmit get() = dir.isNotBlank() && !submitting

    /** The filled model fields, trimmed, as the request carries them. */
    val filledModels: Map<ModelTier, String>
        get() = models.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }

    /** The 「模型」 row: how many tiers keep or change the chosen profile's models, or are filled while 「自己填」. */
    val modelsSummary: String
        get() {
            val filled = filledModels
            val base = presetModels
            val changed = base?.let { b -> ModelTier.entries.count { (filled[it] ?: "") != (b[it] ?: "") } } ?: 0
            return when {
                changed > 0 -> "改了 $changed 项"
                filled.isEmpty() -> "跟随目标机"
                base != null -> "沿用档位的 ${filled.size} 项"
                else -> "已填 ${filled.size} 项"
            }
        }
}

/**
 * The new-session form (task 12.4, design D8): an existing profile's endpoint, the account login, or a
 * new endpoint, with each tier's model. Mistakes mark the field red and raise a floating card.
 */
class CreateSessionViewModel(
    private val machineId: String,
    private val fleet: Fleet,
    private val relay: Relay,
    private val names: ProfileNameStore,
    /** Outlives the page so a session.create already sent finishes and reports through [onNotice]. */
    private val appScope: CoroutineScope? = null,
    private val onNotice: (String) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(CreateUiState())
    val state: StateFlow<CreateUiState> = _state.asStateFlow()

    private val _created = Channel<String>(Channel.BUFFERED)
    /** Emits the new session id once the agent has started it. */
    val created: Flow<String> = _created.receiveAsFlow()

    private var noteSerial = 0
    @Volatile private var cleared = false

    init {
        presetsChanged(presetOptions(fleet.sessions.value[machineId], emptyMap()))
        viewModelScope.launch {
            combine(fleet.sessions.map { it[machineId] }.distinctUntilChanged(), names.names(machineId)) { list, custom -> presetOptions(list, custom) }
                .collect(::presetsChanged)
        }
        viewModelScope.launch {
            val dirs = runCatching {
                relay.call(Method.DIRS_RECENT, machineId, Empty(), Empty.serializer(), DirsRecentResponse.serializer()).dirs
            }.getOrDefault(emptyList())
            _state.update { s -> s.copy(recent = dirs, dir = s.dir.ifBlank { dirs.firstOrNull().orEmpty() }) }
        }
    }

    /** New choices keep the chosen one; untouched model fields follow its models if they changed. */
    private fun presetsChanged(options: List<PresetOption>) = _state.update { s ->
        val chosen = options.firstOrNull { it.id == s.preset }
        val fresh = chosen != null && s.presetModels != null && s.models == s.presetModels && chosen.models != s.presetModels
        if (chosen == null) {
            s.copy(presets = options, preset = ACCOUNT_PRESET, models = options.models(ACCOUNT_PRESET), presetModels = options.models(ACCOUNT_PRESET))
        } else if (fresh) {
            s.copy(presets = options, models = chosen.models, presetModels = chosen.models)
        } else {
            s.copy(presets = options)
        }
    }

    fun setDir(text: String) = edit { it.copy(dir = text, badFields = it.badFields - CreateField.Dir) }
    fun pickDir(dir: String) = setDir(dir)
    fun showAllDirs() = _state.update { it.copy(showAllDirs = true) }
    fun setName(text: String) = _state.update { it.copy(name = text) }
    fun setPrompt(text: String) = _state.update { it.copy(prompt = text) }

    /** A profile fills the model fields with its models and drops what was typed; 「自己填」 leaves them be. */
    fun pickPreset(id: String) = edit { s ->
        if (id == CUSTOM_PRESET) {
            s.copy(preset = id, presetModels = null)
        } else {
            val models = s.presets.models(id)
            s.copy(preset = id, models = models, presetModels = models, badModels = emptySet(), badFields = s.badFields - CreateField.Url - CreateField.Key)
        }
    }

    fun setUrl(text: String) = edit { it.copy(baseUrl = text, badFields = it.badFields - CreateField.Url - CreateField.Key) }
    fun setKey(text: String) = edit { it.copy(apiKey = text, badFields = it.badFields - CreateField.Url - CreateField.Key) }
    fun toggleKey() = _state.update { it.copy(keyShown = !it.keyShown) }
    fun toggleModels() = _state.update { it.copy(modelsOpen = !it.modelsOpen) }
    fun setModel(tier: ModelTier, text: String) = edit { it.copy(models = it.models + (tier to text), badModels = it.badModels - tier) }
    fun dismissNote() = _state.update { it.copy(note = null) }

    /** An edit; once no field is red any more, the card goes too. */
    private fun edit(change: (CreateUiState) -> CreateUiState) = _state.update { s ->
        change(s).let { if (it.badFields.isEmpty() && it.badModels.isEmpty()) it.copy(note = null) else it }
    }

    private fun note(text: String) = FormNote(text, ++noteSerial)

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        val bad = mutableSetOf<CreateField>()
        var message: String? = null
        if (s.custom) {
            if (s.baseUrl.isBlank()) bad += CreateField.Url
            if (s.apiKey.isBlank()) bad += CreateField.Key
            if (bad.isNotEmpty()) {
                message = "地址和 key 都要填"
            } else if (!URL_START.containsMatchIn(s.baseUrl.trim())) {
                bad += CreateField.Url
                message = "地址要以 http:// 或 https:// 开头"
            }
        }
        val badModels = ModelTier.entries.filter { t -> s.models[t].orEmpty().trim().any { it.isWhitespace() } }.toSet()
        if (badModels.isNotEmpty()) message = message ?: "模型名不能有空格"
        if (message != null) {
            _state.update {
                it.copy(badFields = it.badFields + bad, badModels = badModels, modelsOpen = it.modelsOpen || badModels.isNotEmpty(), note = note(message))
            }
            return
        }
        _state.update { it.copy(submitting = true, note = null) }
        create(s, trust = false)
    }

    /**
     * The sheet's 「信任并新建」: the same session.create with trust set; the sheet stays, working, until it answers.
     * The form's own button is starting too meanwhile, so a tap on it sends no second session.create.
     */
    fun trustAndCreate() {
        val s = _state.value
        if (s.untrusted == null || s.trusting) return
        _state.update { it.copy(trusting = true, submitting = true, note = null) }
        create(s, trust = true)
    }

    /** Closes the trust sheet; a trust request already in flight keeps [submitting] true until it answers. */
    fun dismissTrust() = _state.update { it.copy(untrusted = null) }

    private fun create(s: CreateUiState, trust: Boolean) {
        val existingIds = fleet.sessions.value[machineId]?.sessions?.mapTo(HashSet()) { it.id }.orEmpty()
        (appScope ?: viewModelScope).launch {
            val request = SessionCreateRequest(
                cwd = s.untrusted ?: s.dir.trim(),
                name = s.name.trim().ifEmpty { null },
                preset = s.preset.takeIf { it != CUSTOM_PRESET && it != ACCOUNT_PRESET },
                baseUrl = s.baseUrl.trim().takeIf { s.custom },
                apiKey = s.apiKey.trim().takeIf { s.custom },
                models = s.filledModels.ifEmpty { null },
                prompt = s.prompt.trim().ifEmpty { null },
                trust = true.takeIf { trust },
                clientReqId = java.util.UUID.randomUUID().toString(),
            )
            try {
                val res = relay.call(
                    Method.SESSION_CREATE, machineId, request, SessionCreateRequest.serializer(), SessionCreateResponse.serializer(),
                    timeoutMillis = CREATE_SESSION_TIMEOUT_MILLIS,
                )
                fleet.refresh(machineId)
                _state.update { it.copy(submitting = false, untrusted = null, trusting = false) }
                if (cleared) onNotice("已新建会话") else _created.send(res.sessionId)
            } catch (e: RemoteException) {
                if (e.code == ErrorCode.MACHINE_OFFLINE) {
                    reconcileCreate(request, existingIds)
                    return@launch
                }
                val msg = when (e.code) {
                    ErrorCode.NOT_TRUSTED -> e.message ?: "目标目录还没被信任"
                    ErrorCode.DIR_NOT_FOUND -> "目标机上没有这个目录"
                    else -> e.message ?: "启动失败"
                }
                _state.update {
                    val done = it.copy(submitting = false, untrusted = null, trusting = false)
                    when (e.code) {
                        ErrorCode.NOT_TRUSTED -> if (trust) done.copy(note = note(e.message ?: "启动失败")) else done.copy(untrusted = request.cwd)
                        ErrorCode.DIR_NOT_FOUND -> done.copy(badFields = done.badFields + CreateField.Dir, note = note("目标机上没有这个目录"))
                        else -> done.copy(note = note(e.message ?: "启动失败"))
                    }
                }
                if (cleared) onNotice(msg)
            } catch (e: TimeoutCancellationException) {
                reconcileCreate(request, existingIds)
            } catch (e: NotConnectedException) {
                reconcileCreate(request, existingIds)
            } catch (e: IOException) {
                reconcileCreate(request, existingIds)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val msg = "启动失败：${e.message}"
                _state.update { it.copy(submitting = false, untrusted = null, trusting = false, note = note(msg)) }
                if (cleared) onNotice(msg)
            }
        }
    }

    /**
     * When the connection drops or times out while session.create is in flight, wait until the machine is
     * reachable again, resubscribe its session list, and treat a newly listed matching session as success.
     */
    private suspend fun reconcileCreate(request: SessionCreateRequest, existingIds: Set<String>) {
        combine(relay.stateOf(machineId), fleet.machines) { conn, machines ->
            conn == ConnectionState.Connected && machines?.firstOrNull { it.id == machineId }?.online != false
        }.first { it }
        fleet.refresh(machineId)
        val sessions = fleet.sessions.value[machineId]?.sessions.orEmpty()
        val match = sessions.firstOrNull { cand ->
            cand.id !in existingIds &&
                (request.name == null || cand.name == request.name) &&
                (request.worktree == null || cand.worktree?.name == request.worktree) &&
                (cand.dir == request.cwd || cand.cwd == request.cwd)
        }
        if (match != null) {
            _state.update { it.copy(submitting = false, untrusted = null, trusting = false) }
            if (cleared) onNotice("已新建会话") else _created.send(match.id)
        } else {
            val msg = "连接已断开，没有找到新建的会话，请重试"
            _state.update { it.copy(submitting = false, untrusted = null, trusting = false, note = note(msg)) }
            if (cleared) onNotice(msg)
        }
    }

    override fun onCleared() {
        cleared = true
    }

    private companion object {
        /** The agent takes only http and https addresses. */
        val URL_START = Regex("""^https?://[^\s/]+""")
    }
}

private fun List<PresetOption>.models(id: String) = firstOrNull { it.id == id }?.models.orEmpty()

/**
 * The 「档位」 choices, in the session page's group order: every profile a session can start in, the
 * account login even before any session used it, and 「自己填」 last.
 */
fun presetOptions(list: SessionList?, custom: Map<String, String>): List<PresetOption> {
    val groups = list?.let { groupSessions(it, custom) }.orEmpty().filter { it.canCreate }
    val options = groups.map { g ->
        val sub = when {
            g.profile.kind == ProfileKind.Default -> "跟目标机的全局设置"
            g.profile.id == ACCOUNT_PRESET -> "账户登录"
            else -> "沿用这个档位的地址和 key"
        }
        PresetOption(g.profile.id, g.title, sub, g.titleIsHost, g.profile.models.orEmpty())
    }.toMutableList()
    if (options.none { it.id == ACCOUNT_PRESET }) {
        val name = custom[ACCOUNT_PRESET]
        options += PresetOption(ACCOUNT_PRESET, profileTitle(Profile(ACCOUNT_PRESET, ProfileKind.Endpoint), name), "账户登录", titleIsHost = name == null)
    }
    return options + PresetOption(CUSTOM_PRESET, "自己填", "新的接口地址和 API key")
}
