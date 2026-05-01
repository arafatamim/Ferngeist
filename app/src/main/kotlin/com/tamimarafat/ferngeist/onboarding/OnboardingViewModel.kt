package com.tamimarafat.ferngeist.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val onboardingPreferences: OnboardingPreferences,
        private val agentRegistryRepository: AgentRegistryRepository,
    ) : ViewModel() {
        val isCompleted: StateFlow<Boolean> = onboardingPreferences.isCompleted

        private val _uiState = MutableStateFlow(OnboardingUiState())
        val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

        fun ensureRegistryLoaded() {
            val state = _uiState.value
            if (state.isLoadingAgents || state.agents.isNotEmpty()) {
                return
            }
            loadRegistry()
        }

        fun retryRegistryLoad() {
            loadRegistry()
        }

        fun selectAgent(agentId: String) {
            _uiState.value = _uiState.value.copy(selectedAgentId = agentId)
        }

        fun selectPlatform(platform: PcPlatform) {
            _uiState.value = _uiState.value.copy(platform = platform)
        }

        fun updatePort(value: String) {
            val sanitized = value.filter(Char::isDigit).take(5)
            _uiState.value = _uiState.value.copy(port = sanitized)
        }

        fun completeOnboarding() {
            onboardingPreferences.markCompleted()
        }

        private fun loadRegistry() {
            viewModelScope.launch {
                _uiState.value =
                    _uiState.value.copy(
                        isLoadingAgents = true,
                        loadError = null,
                    )
                runCatching { agentRegistryRepository.fetchAgents() }
                    .onSuccess { agents ->
                        val currentSelection = _uiState.value.selectedAgentId
                        val selectedAgentId =
                            agents.firstOrNull { it.id == currentSelection }?.id
                                ?: agents.firstOrNull { it.id == "codex-acp" }?.id
                                ?: agents.firstOrNull()?.id
                        _uiState.value =
                            _uiState.value.copy(
                                isLoadingAgents = false,
                                agents = agents,
                                selectedAgentId = selectedAgentId,
                            )
                    }.onFailure { error ->
                        _uiState.value =
                            _uiState.value.copy(
                                isLoadingAgents = false,
                                loadError = error.message ?: "Failed to load the ACP registry.",
                            )
                    }
            }
        }
    }

data class OnboardingUiState(
    val isLoadingAgents: Boolean = false,
    val loadError: String? = null,
    val agents: List<RegistryAgent> = emptyList(),
    val selectedAgentId: String? = null,
    val platform: PcPlatform = PcPlatform.Windows,
    val port: String = DEFAULT_AGENT_PORT.toString(),
) {
    val selectedAgent: RegistryAgent?
        get() = agents.firstOrNull { it.id == selectedAgentId }

    val parsedPort: Int
        get() = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_AGENT_PORT

    val launchInstructions: AgentLaunchInstructions?
        get() =
            selectedAgent?.let { agent ->
                AgentLaunchCommandBuilder.build(
                    agent = agent,
                    platform = platform,
                    port = parsedPort,
                )
            }
}
