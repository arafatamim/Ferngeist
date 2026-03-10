package com.tamimarafat.ferngeist.feature.sessionlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAgentCapabilities
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectionDiagnostics
import com.tamimarafat.ferngeist.acp.bridge.connection.formatAcpErrorMessage
import com.tamimarafat.ferngeist.core.model.ServerConfig
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.ServerRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    serverRepository: ServerRepository,
    private val sessionRepository: SessionRepository,
    private val connectionManager: AcpConnectionManager,
) : ViewModel() {

    val serverId: String = savedStateHandle.get<String>("serverId") ?: ""

    val server: StateFlow<ServerConfig?> = serverRepository.getServers()
        .map { servers -> servers.find { it.id == serverId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val sessions: StateFlow<List<SessionSummary>> = sessionRepository.getSessions(serverId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    val connectionState: StateFlow<AcpConnectionState> = connectionManager.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AcpConnectionState.Disconnected)
    val agentCapabilities: StateFlow<AcpAgentCapabilities?> = connectionManager.agentCapabilities
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val connectionDiagnostics: StateFlow<ConnectionDiagnostics> = connectionManager.diagnostics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionDiagnostics())

    private val _events = MutableSharedFlow<SessionListEvent>()
    val events = _events.asSharedFlow()

    init {
        refreshSessions()
    }

    fun importSessions(sessions: List<SessionSummary>) {
        viewModelScope.launch {
            replaceSessions(sessions)
        }
    }

    fun refreshSessions() {
        viewModelScope.launch {
            if (!connectionManager.isConnected) {
                _isLoading.value = false
                return@launch
            }

            val capabilities = connectionManager.agentCapabilities.value
            if (capabilities != null && !capabilities.session.list) {
                _isLoading.value = false
                return@launch
            }

            _isLoading.value = true
            runCatching {
                connectionManager.listSessions()
            }.onSuccess { remoteSessions ->
                replaceSessions(remoteSessions)
            }.onFailure {
                _events.emit(SessionListEvent.ShowError(formatAcpErrorMessage(it, "Failed to load sessions")))
            }
            _isLoading.value = false
        }
    }

    fun createSession(cwd: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val normalizedCwd = cwd.trim().ifBlank { "/" }
            runCatching {
                connectionManager.createSession(normalizedCwd)
            }.onSuccess { bridge ->
                if (bridge == null) {
                    _events.emit(SessionListEvent.ShowError("Failed to create a new session"))
                } else {
                    val summary = SessionSummary(
                        id = bridge.sessionId,
                        title = null,
                        cwd = normalizedCwd,
                        updatedAt = System.currentTimeMillis()
                    )
                    sessionRepository.upsertSession(serverId, summary)
                    _events.emit(
                        SessionListEvent.NavigateToChat(
                            serverId = serverId,
                            sessionId = summary.id,
                            cwd = summary.cwd ?: "/",
                            updatedAt = summary.updatedAt,
                            title = summary.title,
                        )
                    )
                }
            }.onFailure {
                _events.emit(SessionListEvent.ShowError(formatAcpErrorMessage(it, "Failed to create a new session")))
            }
            _isLoading.value = false
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            sessionRepository.deleteSession(serverId, sessionId)
        }
    }

    private suspend fun replaceSessions(newSessions: List<SessionSummary>) {
        sessionRepository.clearSessions(serverId)
        newSessions.forEach { session ->
            sessionRepository.upsertSession(serverId, session)
        }
    }
}

sealed interface SessionListEvent {
    data class NavigateToChat(
        val serverId: String,
        val sessionId: String,
        val cwd: String,
        val updatedAt: Long?,
        val title: String?,
    ) : SessionListEvent
    data class ShowError(val message: String) : SessionListEvent
}
