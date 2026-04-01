package com.tamimarafat.ferngeist.feature.serverlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.core.model.ServerConfig
import com.tamimarafat.ferngeist.core.model.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddServerViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val initialServerId: String? = savedStateHandle.get<String>("serverId")
    private val prefillName: String? = savedStateHandle.get<String>("name")
    private val prefillScheme: String? = savedStateHandle.get<String>("scheme")
    private val prefillHost: String? = savedStateHandle.get<String>("host")
    private var persistedPreferredAuthMethodId: String? = null

    private val _name = MutableStateFlow(prefillName.orEmpty())
    val name: StateFlow<String> = _name.asStateFlow()

    private val _scheme = MutableStateFlow(prefillScheme?.ifBlank { "ws" } ?: "ws")
    val scheme: StateFlow<String> = _scheme.asStateFlow()

    private val _host = MutableStateFlow(prefillHost.orEmpty())
    val host: StateFlow<String> = _host.asStateFlow()

    private val _token = MutableStateFlow("")
    val token: StateFlow<String> = _token.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _preferredAuthMethodId = MutableStateFlow<String?>(null)
    val preferredAuthMethodId: StateFlow<String?> = _preferredAuthMethodId.asStateFlow()

    private val _events = MutableSharedFlow<AddServerEvent>()
    val events = _events.asSharedFlow()

    val isEditMode: Boolean = initialServerId != null

    init {
        if (initialServerId != null) {
            loadServer(initialServerId)
        }
    }

    private fun loadServer(id: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val server = serverRepository.getServer(id)
            if (server != null) {
                _name.value = server.name
                _scheme.value = server.scheme
                _host.value = server.host
                _token.value = server.token
                persistedPreferredAuthMethodId = server.preferredAuthMethodId
                _preferredAuthMethodId.value = server.preferredAuthMethodId
            }
            _isLoading.value = false
        }
    }

    fun updateName(value: String) {
        _name.value = value
    }

    fun updateScheme(value: String) {
        _scheme.value = value
    }

    fun updateHost(value: String) {
        _host.value = value
    }

    fun updateToken(value: String) {
        _token.value = value
    }

    fun clearPreferredAuthMethod() {
        persistedPreferredAuthMethodId = null
        _preferredAuthMethodId.value = null
    }

    fun saveServer() {
        viewModelScope.launch {
            if (_name.value.isBlank()) {
                _events.emit(AddServerEvent.ShowError("Name is required"))
                return@launch
            }
            if (_host.value.isBlank()) {
                _events.emit(AddServerEvent.ShowError("Host is required"))
                return@launch
            }

            val server = ServerConfig(
                id = initialServerId ?: java.util.UUID.randomUUID().toString(),
                name = _name.value.trim(),
                scheme = _scheme.value,
                host = _host.value.trim(),
                token = _token.value,
                preferredAuthMethodId = persistedPreferredAuthMethodId,
            )

            if (isEditMode) {
                serverRepository.updateServer(server)
            } else {
                serverRepository.addServer(server)
            }

            _events.emit(AddServerEvent.ServerSaved)
        }
    }
}

sealed interface AddServerEvent {
    data object ServerSaved : AddServerEvent
    data class ShowError(val message: String) : AddServerEvent
}
