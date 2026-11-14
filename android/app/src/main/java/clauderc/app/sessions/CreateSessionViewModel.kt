package clauderc.app.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import clauderc.app.data.Fleet
import clauderc.app.data.ProfileNameStore
import clauderc.app.net.Relay
import clauderc.app.net.RemoteException
import clauderc.app.net.call
import clauderc.app.protocol.DirsRecentResponse
import clauderc.app.protocol.Empty
import clauderc.app.protocol.ErrorCode
import clauderc.app.protocol.Method
import clauderc.app.protocol.ProfileKind
import clauderc.app.protocol.SessionCreateRequest
import clauderc.app.protocol.SessionCreateResponse
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreateUiState(
    val profileTitle: String = "",
    val profileHost: String? = null,
    val templateName: String? = null,
    val canCreate: Boolean = true,
    val dir: String = "",
    val recent: List<String> = emptyList(),
    val showAllDirs: Boolean = false,
    val name: String = "",
    val prompt: String = "",
    val dirError: String? = null,
    val error: String? = null,
    val submitting: Boolean = false,
) {
    val canSubmit get() = canCreate && dir.isNotBlank() && !submitting
    val visibleDirs get() = if (showAllDirs) recent else recent.take(5)
}

/** The new-session form of one profile (task 12.4, design D8). */
class CreateSessionViewModel(
    private val machineId: String,
    private val profileId: String,
    private val fleet: Fleet,
    private val relay: Relay,
    private val names: ProfileNameStore,
) : ViewModel() {
    private val _state = MutableStateFlow(CreateUiState())
    val state: StateFlow<CreateUiState> = _state.asStateFlow()

    private val _created = Channel<String>(Channel.BUFFERED)
    /** Emits the new session id once the agent has started it. */
    val created: Flow<String> = _created.receiveAsFlow()

    init {
        val group = fleet.sessions.value[machineId]?.let { list -> groupSessions(list, emptyMap()).firstOrNull { it.profile.id == profileId } }
        val template = group?.template
        _state.value = CreateUiState(
            profileTitle = group?.title ?: profileId,
            profileHost = profileId.takeIf { group?.profile?.kind == ProfileKind.Endpoint },
            templateName = template?.let(::display),
            canCreate = group?.canCreate ?: (profileId != "other"),
            dir = template?.cwd.orEmpty(),
        )
        viewModelScope.launch {
            names.names(machineId).first()[profileId]?.let { custom -> _state.update { it.copy(profileTitle = custom) } }
        }
        viewModelScope.launch {
            val dirs = runCatching {
                relay.call(Method.DIRS_RECENT, machineId, Empty(), Empty.serializer(), DirsRecentResponse.serializer()).dirs
            }.getOrDefault(emptyList())
            _state.update { s -> s.copy(recent = dirs, dir = s.dir.ifBlank { dirs.firstOrNull().orEmpty() }) }
        }
    }

    fun setDir(text: String) = _state.update { it.copy(dir = text, dirError = null) }
    fun pickDir(dir: String) = setDir(dir)
    fun showAllDirs() = _state.update { it.copy(showAllDirs = true) }
    fun setName(text: String) = _state.update { it.copy(name = text) }
    fun setPrompt(text: String) = _state.update { it.copy(prompt = text) }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(submitting = true, error = null, dirError = null) }
        viewModelScope.launch {
            val request = SessionCreateRequest(
                profileId = profileId,
                cwd = s.dir.trim(),
                name = s.name.trim().ifEmpty { null },
                prompt = s.prompt.trim().ifEmpty { null },
            )
            try {
                val res = relay.call(Method.SESSION_CREATE, machineId, request, SessionCreateRequest.serializer(), SessionCreateResponse.serializer())
                fleet.refresh(machineId)
                _state.update { it.copy(submitting = false) }
                _created.send(res.sessionId)
            } catch (e: RemoteException) {
                _state.update {
                    if (e.code == ErrorCode.DIR_NOT_FOUND) it.copy(submitting = false, dirError = "目标机上没有这个目录")
                    else it.copy(submitting = false, error = e.message)
                }
            } catch (e: Exception) {
                _state.update { it.copy(submitting = false, error = "启动失败：${e.message}") }
            }
        }
    }
}
