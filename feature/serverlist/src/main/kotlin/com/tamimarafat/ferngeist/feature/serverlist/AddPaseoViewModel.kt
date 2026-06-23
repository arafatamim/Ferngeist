package com.tamimarafat.ferngeist.feature.serverlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Paseo daemon pairing screen: collects a local-network endpoint (typed or
 * scanned), pairs via [PaseoPairingCoordinator], and persists the resulting targets.
 */
@HiltViewModel
class AddPaseoViewModel
    @Inject
    constructor(
        private val pairingCoordinator: PaseoPairingCoordinator,
    ) : ViewModel() {
        data class UiState(
            val name: String = "",
            val endpoint: String = "",
            val password: String = "",
            val isPairing: Boolean = false,
            val message: String? = null,
            val paired: Boolean = false,
        )

        private val _uiState = MutableStateFlow(UiState())
        val uiState: StateFlow<UiState> = _uiState.asStateFlow()

        fun updateName(value: String) = _uiState.update { it.copy(name = value, message = null) }

        fun updateEndpoint(value: String) = _uiState.update { it.copy(endpoint = value, message = null) }

        fun updatePassword(value: String) = _uiState.update { it.copy(password = value, message = null) }

        fun onQrScanned(raw: String) = _uiState.update { it.copy(endpoint = raw, message = null) }

        fun showMessage(message: String) = _uiState.update { it.copy(message = message) }

        fun pair() {
            val state = _uiState.value
            if (state.endpoint.isBlank() || state.isPairing) return
            _uiState.update { it.copy(isPairing = true, message = null) }
            viewModelScope.launch {
                val result =
                    pairingCoordinator.pair(
                        name = state.name,
                        input = state.endpoint.trim(),
                        password = state.password,
                    )
                _uiState.update {
                    when (result) {
                        is PaseoPairingCoordinator.Result.Paired ->
                            it.copy(isPairing = false, paired = true)
                        is PaseoPairingCoordinator.Result.Failed ->
                            it.copy(isPairing = false, message = result.message)
                    }
                }
            }
        }
    }
