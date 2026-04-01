package com.tamimarafat.ferngeist.feature.serverlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.core.model.DesktopHelperSource
import com.tamimarafat.ferngeist.core.model.repository.DesktopHelperSourceRepository
import com.tamimarafat.ferngeist.core.model.repository.HelperAgentBindingRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetSessionSettingsRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.feature.serverlist.auth.AuthEnvValueStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lists paired desktop companions separately from launchable agents so the main
 * server list can stay focused on user-startable entries only.
 */
@HiltViewModel
class DesktopCompanionListViewModel @Inject constructor(
    private val helperSourceRepository: DesktopHelperSourceRepository,
    private val helperAgentBindingRepository: HelperAgentBindingRepository,
    private val sessionRepository: SessionRepository,
    private val authEnvValueStore: AuthEnvValueStore,
    private val sessionSettingsRepository: LaunchableTargetSessionSettingsRepository,
) : ViewModel() {

    val companions: StateFlow<List<DesktopHelperSource>> = helperSourceRepository.getHelpers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteCompanion(companion: DesktopHelperSource) {
        viewModelScope.launch {
            helperAgentBindingRepository.getBindingsForHelper(companion.id).forEach { binding ->
                authEnvValueStore.deleteValues(binding.id)
                sessionRepository.clearSessions(binding.id)
                sessionSettingsRepository.deleteSettings(binding.id)
                helperAgentBindingRepository.deleteBinding(binding.id)
            }
            authEnvValueStore.deleteValues(companion.id)
            helperSourceRepository.deleteHelper(companion.id)
        }
    }
}
