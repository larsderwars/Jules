package dev.therealashik.jules.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.therealashik.jules.KeyValueStore
import dev.therealashik.jules.sdk.JulesApiClient
import dev.therealashik.jules.sdk.models.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import dev.therealashik.jules.NotificationService
import dev.therealashik.jules.gallery.PromptGalleryRepository
import dev.therealashik.jules.gallery.PromptItem

sealed interface Screen {
    data object SessionList : Screen
    data object CreateSession : Screen
    data class SessionDetail(val sessionId: String, val title: String, val prompt: String = "") : Screen
    data class CodeReview(val sessionId: String, val title: String) : Screen
    data object Settings : Screen
    data object PromptGallery : Screen
}

enum class ThemePreference { SYSTEM, LIGHT, DARK }

data class UiState(
    val sessions: List<Session> = emptyList(),
    val sessionsById: Map<String, Session> = emptyMap(),
    val activities: List<Activity> = emptyList(),
    val promptItems: List<PromptItem> = emptyList(),
    val selectedGalleryPrompts: List<PromptItem> = emptyList(),
    val sources: List<Source> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val screen: Screen = Screen.SessionList,
    val apiKey: String = "",
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val pageSize: Int = 30,
    val filterStates: Set<SessionState> = emptySet(),
    val filterRepo: String? = null,
    val sessionListCompact: Boolean = false,
    val isSelectionMode: Boolean = false,
    val selectedSessionIds: Set<String> = emptySet()
) {
    val filteredSessions: List<Session>
        get() = sessions.filter { session ->
            val matchState = filterStates.isEmpty() || filterStates.contains(session.state)
            val matchRepo = filterRepo == null || session.sourceContext?.source == filterRepo
            matchState && matchRepo
        }
}

private fun String.normalizeSessionId() = substringAfter("sessions/").takeIf { it.isNotBlank() } ?: this

class JulesViewModel(
    private var apiClient: JulesApiClient,
    initialApiKey: String = "",
    private val store: KeyValueStore? = null,
    private val promptGalleryRepository: PromptGalleryRepository? = null
) : ViewModel() {

    private val initialTheme = store?.getString("theme_preference")
        ?.let { runCatching { ThemePreference.valueOf(it) }.getOrNull() }
        ?: ThemePreference.SYSTEM
    private val initialPageSize = store?.getString("page_size")
        ?.toIntOrNull()?.coerceIn(10, 100) ?: 30
    private val initialSessionListCompact = store?.getString("session_list_compact")?.toBooleanStrictOrNull() ?: false

    private val _state = MutableStateFlow(
        UiState(
            apiKey = initialApiKey,
            screen = if (initialApiKey.isBlank()) Screen.Settings else Screen.SessionList,
            themePreference = initialTheme,
            pageSize = initialPageSize,
            sessionListCompact = initialSessionListCompact
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pollingJob: Job? = null
    private val notificationService = NotificationService()
    private val sessionPollJobs = mutableMapOf<String, Job>()

    fun saveApiKey(key: String) {
        store?.putString("api_key", key)
        apiClient.close()
        apiClient = JulesApiClient(key)
        _state.update { it.copy(apiKey = key) }
        navigate(Screen.SessionList)
    }

    fun saveThemePreference(theme: ThemePreference) {
        store?.putString("theme_preference", theme.name)
        _state.update { it.copy(themePreference = theme) }
    }

    fun savePageSize(size: Int) {
        store?.putString("page_size", size.toString())
        _state.update { it.copy(pageSize = size) }
    }

    fun saveSessionListCompact(compact: Boolean) {
        store?.putString("session_list_compact", compact.toString())
        _state.update { it.copy(sessionListCompact = compact) }
    }

    fun navigate(screen: Screen) {
        if (screen !is Screen.CreateSession) {
            _state.update { it.copy(selectedGalleryPrompts = emptyList()) }
        }
        _state.update { it.copy(screen = screen, error = null) }

        stopPolling()

        when (screen) {
            is Screen.SessionList -> loadSessions()
            is Screen.SessionDetail -> {
                loadActivities(screen.sessionId)
                startPolling(screen.sessionId)
            }
            is Screen.CodeReview -> Unit
            is Screen.PromptGallery -> loadPrompts()
            Screen.CreateSession -> {
                loadPrompts()
                loadSources()
            }
            Screen.Settings -> Unit
        }
    }

    private fun startPolling(sessionId: String) {
        stopPolling()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(5000)
                try {
                    val session = apiClient.getSession(sessionId.normalizeSessionId(), forceRefresh = true)
                    _state.update { state ->
                        state.copy(sessionsById = state.sessionsById + (sessionId to session))
                    }
                    loadActivities(sessionId, forceRefresh = true, showLoading = false, appendNew = true)

                    if (session.state == SessionState.COMPLETED ||
                        session.state == SessionState.FAILED ||
                        session.state == SessionState.STATE_UNSPECIFIED
                    ) {
                        break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Ignore transient polling errors; the next cycle retries.
                }
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun toggleStateFilter(state: SessionState) {
        _state.update { current ->
            val newStates = if (current.filterStates.contains(state)) {
                current.filterStates - state
            } else {
                current.filterStates + state
            }
            current.copy(filterStates = newStates)
        }
    }

    fun setRepoFilter(repo: String?) {
        _state.update { it.copy(filterRepo = repo) }
    }

    fun loadSources() {
        viewModelScope.launch {
            try {
                val response = apiClient.listSources()
                _state.update { it.copy(sources = response.sources) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ignore errors for sources.
            }
        }
    }

    fun toggleGalleryPrompt(item: PromptItem) {
        _state.update { state ->
            val current = state.selectedGalleryPrompts.toMutableList()
            if (current.contains(item)) current.remove(item) else current.add(item)
            state.copy(selectedGalleryPrompts = current)
        }
    }

    fun loadPrompts() {
        val prompts = promptGalleryRepository?.getAll() ?: emptyList()
        _state.update { it.copy(promptItems = prompts) }
    }

    fun savePrompt(title: String, prompt: String) {
        val id = title.hashCode().toString() + "_" + prompt.hashCode().toString()
        promptGalleryRepository?.save(PromptItem(id, title, prompt))
        loadPrompts()
    }

    fun deletePrompt(id: String) {
        promptGalleryRepository?.delete(id)
        loadPrompts()
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                apiClient.deleteSession(sessionId)
                loadSessions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to delete session") }
            }
        }
    }

    fun enterSelectionMode(sessionId: String) {
        _state.update { it.copy(isSelectionMode = true, selectedSessionIds = setOf(sessionId)) }
    }

    fun toggleSessionSelection(sessionId: String) {
        _state.update { current ->
            val newSelected = if (current.selectedSessionIds.contains(sessionId)) {
                current.selectedSessionIds - sessionId
            } else {
                current.selectedSessionIds + sessionId
            }
            current.copy(selectedSessionIds = newSelected, isSelectionMode = newSelected.isNotEmpty())
        }
    }

    fun selectAllFiltered() {
        _state.update { current ->
            val allIds = current.filteredSessions.map { it.name.substringAfter("sessions/").takeIf { it.isNotBlank() } ?: it.id }.toSet()
            current.copy(selectedSessionIds = allIds)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(isSelectionMode = false, selectedSessionIds = emptySet()) }
    }

    fun deleteSelectedSessions() {
        val idsToDelete = state.value.selectedSessionIds
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                idsToDelete.map { id -> launch { apiClient.deleteSession(id) } }.forEach { it.join() }
                loadSessions()
                clearSelection()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to delete sessions") }
            }
        }
    }

    fun loadSessions() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiClient.listSessions(pageSize = _state.value.pageSize)
                val sessionsById = response.sessions.associateBy { it.id } +
                    response.sessions.associateBy { it.name.normalizeSessionId() }
                _state.update { it.copy(isLoading = false, sessions = response.sessions, sessionsById = sessionsById) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to load sessions") }
            }
        }
    }

    fun createSession(
        prompt: String,
        title: String,
        sourceContext: SourceContext? = null,
        requirePlanApproval: Boolean? = null,
        automationMode: AutomationMode? = null
    ) {
        val selectedPromptsText = state.value.selectedGalleryPrompts.joinToString("\n\n") { it.prompt }
        val finalPrompt = if (selectedPromptsText.isNotBlank()) {
            if (prompt.isNotBlank()) "$selectedPromptsText\n\n$prompt" else selectedPromptsText
        } else prompt

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val session = apiClient.createSession(
                    CreateSessionRequest(
                        prompt = finalPrompt,
                        title = title.takeIf { it.isNotBlank() },
                        sourceContext = sourceContext,
                        requirePlanApproval = requirePlanApproval,
                        automationMode = automationMode
                    )
                )
                _state.update { it.copy(isLoading = false) }
                val sessionId = session.name.normalizeSessionId()
                navigate(Screen.SessionDetail(sessionId, session.title, finalPrompt))
                pollSessionStatus(sessionId, session.title.takeIf { it.isNotBlank() } ?: sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to create session") }
            }
        }
    }

    fun loadActivities(
        sessionId: String,
        forceRefresh: Boolean = false,
        showLoading: Boolean = true,
        appendNew: Boolean = false
    ) {
        viewModelScope.launch {
            if (showLoading) _state.update { it.copy(isLoading = true, error = null) }
            try {
                val latestCreateTime = if (appendNew) {
                    state.value.activities.maxOfOrNull { it.createTime }?.takeIf { it.isNotBlank() }
                } else null
                val response = apiClient.listActivities(
                    sessionId.normalizeSessionId(),
                    pageSize = _state.value.pageSize,
                    createTime = latestCreateTime,
                    forceRefresh = forceRefresh
                )
                _state.update { state ->
                    val activities = if (appendNew) {
                        val existingIds = state.activities.map { it.id }.toSet()
                        state.activities + response.activities.filter { it.id !in existingIds }
                    } else {
                        response.activities
                    }
                    state.copy(activities = activities, isLoading = if (showLoading) false else state.isLoading)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (showLoading) _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to load activities") }
            }
        }
    }

    fun sendMessage(sessionId: String, prompt: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                apiClient.sendMessage(sessionId.normalizeSessionId(), SendMessageRequest(prompt = prompt))
                _state.update { it.copy(isLoading = false) }
                loadActivities(sessionId, forceRefresh = true, showLoading = false, appendNew = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to send message") }
            }
        }
    }

    private fun pollSessionStatus(sessionId: String, sessionTitle: String) {
        sessionPollJobs[sessionId]?.cancel()
        sessionPollJobs[sessionId] = viewModelScope.launch {
            var lastState: SessionState? = null
            while (true) {
                try {
                    val session = apiClient.getSession(sessionId, forceRefresh = true)
                    if (session.state != lastState) {
                        lastState = session.state
                        val statusText = when (session.state) {
                            SessionState.QUEUED -> "Queued"
                            SessionState.PLANNING -> "Planning"
                            SessionState.AWAITING_PLAN_APPROVAL -> "Waiting for your approval"
                            SessionState.AWAITING_USER_FEEDBACK -> "Needs user feedback"
                            SessionState.IN_PROGRESS -> "Jules is working…"
                            SessionState.PAUSED -> "Paused"
                            SessionState.COMPLETED -> "Completed"
                            SessionState.FAILED -> "Failed"
                            SessionState.STATE_UNSPECIFIED -> "Unknown"
                        }
                        notificationService.notify(sessionId, sessionTitle, statusText)
                    }

                    if (session.state == SessionState.COMPLETED || session.state == SessionState.FAILED) break
                } catch (e: Exception) {
                    // Ignore transient polling errors.
                }
                delay(5000)
            }
        }
    }

    fun approvePlan(sessionId: String, plan: Plan? = null) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                apiClient.approvePlan(sessionId.normalizeSessionId(), plan)
                _state.update { it.copy(isLoading = false) }
                loadActivities(sessionId, forceRefresh = true, showLoading = false, appendNew = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to approve plan") }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        sessionPollJobs.values.forEach { it.cancel() }
        sessionPollJobs.clear()
        apiClient.close()
    }
}
