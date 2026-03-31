package com.tamimarafat.ferngeist.feature.serverlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.core.model.ServerConfig
import com.tamimarafat.ferngeist.core.model.repository.ServerRepository
import com.tamimarafat.ferngeist.feature.serverlist.auth.AuthEnvValueStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lists paired desktop companions separately from launchable agents so the main
 * server list can stay focused on user-startable entries only.
 */
@HiltViewModel
class DesktopCompanionListViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val authEnvValueStore: AuthEnvValueStore,
) : ViewModel() {

    val companions: StateFlow<List<ServerConfig>> = serverRepository.getServers()
        .map { servers -> servers.filter { it.isDesktopCompanion } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteCompanion(companion: ServerConfig) {
        viewModelScope.launch {
            val allServers = serverRepository.getServers().first()
            allServers
                .filter { it.helperSourceId == companion.id }
                .forEach { server ->
                    authEnvValueStore.deleteValues(server.id)
                    serverRepository.deleteServer(server.id)
                }
            authEnvValueStore.deleteValues(companion.id)
            serverRepository.deleteServer(companion.id)
        }
    }
}
