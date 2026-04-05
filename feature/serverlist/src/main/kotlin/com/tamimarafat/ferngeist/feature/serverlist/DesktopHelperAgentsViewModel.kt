package com.tamimarafat.ferngeist.feature.serverlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.core.model.DesktopHelperSource
import com.tamimarafat.ferngeist.core.model.repository.DesktopHelperSourceRepository
import com.tamimarafat.ferngeist.core.model.HelperAgentBinding
import com.tamimarafat.ferngeist.core.model.repository.HelperAgentBindingRepository
import com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperAgent
import com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperRepository
import com.tamimarafat.ferngeist.feature.serverlist.helper.refreshHelperSourceIfNeeded
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DesktopHelperAgentsUiState(
    val companion: DesktopHelperSource? = null,
    val agents: List<DesktopHelperAgent> = emptyList(),
    val addedAgentIds: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val loadError: String? = null,
)

/**
 * Loads helper-visible agents for one paired desktop companion and lets the
 * user promote selected agents into the main launchable agent list.
 */
@HiltViewModel
class DesktopHelperAgentsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val helperSourceRepository: DesktopHelperSourceRepository,
    private val helperAgentBindingRepository: HelperAgentBindingRepository,
    private val helperRepository: DesktopHelperRepository,
) : ViewModel() {

    private val companionId: String = savedStateHandle.get<String>("serverId").orEmpty()

    private val _uiState = MutableStateFlow(DesktopHelperAgentsUiState())
    val uiState: StateFlow<DesktopHelperAgentsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>()
    val events = _events.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val bindings = helperAgentBindingRepository.getBindingsForHelper(companionId)
            val storedCompanion = helperSourceRepository.getHelper(companionId)
            if (storedCompanion == null) {
                _uiState.value = DesktopHelperAgentsUiState(
                    isLoading = false,
                    loadError = "Desktop companion not found",
                )
                return@launch
            }
			val companion = refreshHelperSourceIfNeeded(storedCompanion, helperRepository, helperSourceRepository)

            _uiState.value = _uiState.value.copy(
                companion = companion,
                isLoading = true,
                loadError = null,
            )
            runCatching {
                helperRepository.fetchAgents(companion.scheme, companion.host, companion.helperCredential)
            }.onSuccess { agents ->
                _uiState.value = DesktopHelperAgentsUiState(
                    companion = companion,
                    agents = agents,
                    addedAgentIds = bindings
                        .map { it.agentId }
                        .toSet(),
                    isLoading = false,
                    loadError = null,
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    companion = companion,
                    isLoading = false,
                    loadError = "Could not connect to desktop companion: ${error.message ?: "unknown error"}",
                )
            }
        }
    }

    fun addAgent(agent: DesktopHelperAgent) {
        val state = _uiState.value
        val companion = state.companion ?: return
        if (agent.id in state.addedAgentIds) return

        viewModelScope.launch {
            val binding = HelperAgentBinding(
                name = agent.displayName,
                helperSourceId = companion.id,
                agentId = agent.id,
            )
            helperAgentBindingRepository.addBinding(binding)
            _uiState.value = state.copy(addedAgentIds = state.addedAgentIds + agent.id)
            _events.emit("Added ${agent.displayName}")
        }
    }
}
