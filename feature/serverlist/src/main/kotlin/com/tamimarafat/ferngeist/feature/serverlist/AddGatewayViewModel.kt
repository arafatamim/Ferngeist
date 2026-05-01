package com.tamimarafat.ferngeist.feature.serverlist

import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.gateway.GatewayPairingPayload
import com.tamimarafat.ferngeist.gateway.GatewayPairingPayloadParser
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.GatewayStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddGatewayUiState(
    val status: GatewayStatus? = null,
    val importedPairingPayload: GatewayPairingPayload? = null,
    val isCheckingStatus: Boolean = false,
    val isSaving: Boolean = false,
)

@HiltViewModel
class AddGatewayViewModel @Inject constructor(
    private val gatewaySourceRepository: GatewaySourceRepository,
    private val gatewayRepository: GatewayRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialServerId: String? = savedStateHandle.get<String>("serverId")
    private var existingGateway: GatewaySource? = null

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _scheme = MutableStateFlow("http")
    val scheme: StateFlow<String> = _scheme.asStateFlow()

    private val _host = MutableStateFlow("")
    val host: StateFlow<String> = _host.asStateFlow()

    private val _deviceName = MutableStateFlow(defaultDeviceName())
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _pairingQrPayload = MutableStateFlow("")
    val pairingQrPayload: StateFlow<String> = _pairingQrPayload.asStateFlow()

    private val _pairingCode = MutableStateFlow("")
    val pairingCode: StateFlow<String> = _pairingCode.asStateFlow()

    private val _uiState = MutableStateFlow(AddGatewayUiState())
    val uiState: StateFlow<AddGatewayUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<AddGatewayEvent>()
    val events = _events.asSharedFlow()

    val isEditMode: Boolean = initialServerId != null

    private var activeChallengeId: String? = null

    init {
        if (initialServerId != null) {
            loadExisting(initialServerId)
        }
    }

    fun updateName(value: String) {
        _name.value = value
    }

    fun updateScheme(value: String) {
        _scheme.value = value
        clearStatus()
    }

    fun updateHost(value: String) {
        _host.value = value
        clearStatus()
    }

    fun updateDeviceName(value: String) {
        _deviceName.value = value
    }

    fun updatePairingQrPayload(value: String) {
        _pairingQrPayload.value = value
    }

    fun updatePairingCode(value: String) {
        _pairingCode.value = value
    }

    fun applyPairingPayload() {
        val payload = GatewayPairingPayloadParser.parse(_pairingQrPayload.value)
        if (payload == null) {
            viewModelScope.launch { emitError("Pairing payload is invalid. Scan the QR from `ferngeist pair` or paste the full payload.") }
            return
        }
        _scheme.value = payload.scheme
        _host.value = payload.host
        _pairingCode.value = payload.code
        activeChallengeId = payload.challengeId
        _name.value = extractHostnameFromHost(payload.host)
        _uiState.value = _uiState.value.copy(importedPairingPayload = payload, status = null)
        checkStatus()
    }

    fun checkStatus() {
        viewModelScope.launch {
            val gatewayHost = normalizedHost()
            if (gatewayHost == null) {
                emitError("Gateway host is required")
                return@launch
            }
            _uiState.value = _uiState.value.copy(isCheckingStatus = true)
            runCatching {
                gatewayRepository.fetchStatus(_scheme.value, gatewayHost)
            }.onSuccess { status ->
                _uiState.value = _uiState.value.copy(status = status, isCheckingStatus = false)
                _name.value = status.name
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(isCheckingStatus = false)
                emitError("Could not reach gateway: ${error.message ?: "unknown error"}")
            }
        }
    }

    fun pairAndSaveWithCode(codeFromDialog: String) {
        viewModelScope.launch {
            val gatewayHost = normalizedHost()
            if (gatewayHost == null) {
                emitError("Gateway host is required")
                return@launch
            }
            val gatewayName = _name.value.trim()
            if (gatewayName.isBlank()) {
                emitError("Name is required")
                return@launch
            }
            val trimmedCode = codeFromDialog.trim()
            if (trimmedCode.isBlank()) {
                emitError("Pairing code is required")
                return@launch
            }

            _uiState.value = _uiState.value.copy(isSaving = true)
            val challengeResult = runCatching {
                val pairingResponse = gatewayRepository.startPairing(_scheme.value, gatewayHost)
                pairingResponse.challengeId
            }
            if (challengeResult.isFailure) {
                _uiState.value = _uiState.value.copy(isSaving = false)
                emitError("Could not start pairing: ${challengeResult.exceptionOrNull()?.message ?: "unknown error"}")
                return@launch
            }
            val challengeId = challengeResult.getOrThrow()

            runCatching {
                gatewayRepository.completePairing(
                    scheme = _scheme.value,
                    host = gatewayHost,
                    challengeId = challengeId,
                    code = trimmedCode,
                    deviceName = _deviceName.value.trim().ifBlank { defaultDeviceName() },
                )
            }.onSuccess { pairing ->
                val gateway = GatewaySource(
                    id = java.util.UUID.randomUUID().toString(),
                    name = gatewayName,
                    scheme = _scheme.value,
                    host = gatewayHost,
                    gatewayCredential = pairing.gatewayCredential,
                    gatewayCredentialExpiresAt = pairing.expiresAt.toEpochMillisOrNull(),
                    gatewayRemoteMode = _uiState.value.status?.remote?.mode,
                )
                gatewaySourceRepository.addGateway(gateway)
                _uiState.value = _uiState.value.copy(isSaving = false)
                _events.emit(AddGatewayEvent.GatewaySaved)
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(isSaving = false)
                emitError("Could not complete pairing: ${error.message ?: "unknown error"}")
            }
        }
    }

    fun saveGateway() {
        viewModelScope.launch {
            val gatewayHost = normalizedHost()
            if (gatewayHost == null) {
                emitError("Gateway host is required")
                return@launch
            }
            val gatewayName = _name.value.trim()
            if (gatewayName.isBlank()) {
                emitError("Name is required")
                return@launch
            }

            if (isEditMode) {
                val currentGateway = existingGateway
                if (currentGateway == null) {
                    emitError("Gateway could not be loaded")
                    return@launch
                }
                _uiState.value = _uiState.value.copy(isSaving = true)
                runCatching {
                    val updatedGateway = currentGateway.copy(
                        name = gatewayName,
                        scheme = _scheme.value,
                        host = gatewayHost,
                        gatewayRemoteMode = _uiState.value.status?.remote?.mode ?: currentGateway.gatewayRemoteMode,
                    )
                    gatewaySourceRepository.updateGateway(updatedGateway)
                    existingGateway = updatedGateway
                }.onSuccess {
                    _uiState.value = _uiState.value.copy(isSaving = false)
                    _events.emit(AddGatewayEvent.GatewaySaved)
                }.onFailure { error ->
                    _uiState.value = _uiState.value.copy(isSaving = false)
                    emitError("Could not save gateway: ${error.message ?: "unknown error"}")
                }
                return@launch
            }

            val importedPayload = _uiState.value.importedPairingPayload
            val manualCode = _pairingCode.value.trim()
            val resolvedCode = importedPayload?.code ?: manualCode
            var challengeId = activeChallengeId ?: importedPayload?.challengeId

            if (challengeId.isNullOrBlank() && resolvedCode.isNotBlank()) {
                _uiState.value = _uiState.value.copy(isCheckingStatus = true)
                runCatching {
                    val pairingResponse = gatewayRepository.startPairing(_scheme.value, gatewayHost)
                    challengeId = pairingResponse.challengeId
                    activeChallengeId = pairingResponse.challengeId
                    _uiState.value = _uiState.value.copy(isCheckingStatus = false)
                }.onFailure { error ->
                    _uiState.value = _uiState.value.copy(isCheckingStatus = false)
                    emitError("Could not start pairing: ${error.message ?: "unknown error"}")
                    return@launch
                }
            }

            if (resolvedCode.isBlank() || challengeId.isNullOrBlank()) {
                emitError("Scan QR, paste payload, or type pairing code first")
                return@launch
            }

            _uiState.value = _uiState.value.copy(isSaving = true)
            runCatching {
                gatewayRepository.completePairing(
                    scheme = _scheme.value,
                    host = gatewayHost,
                    challengeId = challengeId,
                    code = resolvedCode,
                    deviceName = _deviceName.value.trim().ifBlank { defaultDeviceName() },
                )
            }.onSuccess { pairing ->
                val gatewayStatus = _uiState.value.status
                val gateway = GatewaySource(
                    id = initialServerId ?: java.util.UUID.randomUUID().toString(),
                    name = gatewayName,
                    scheme = _scheme.value,
                    host = gatewayHost,
                    gatewayCredential = pairing.gatewayCredential,
                    gatewayCredentialExpiresAt = pairing.expiresAt.toEpochMillisOrNull(),
                    gatewayRemoteMode = gatewayStatus?.remote?.mode,
                )
                gatewaySourceRepository.addGateway(gateway)
                _uiState.value = _uiState.value.copy(isSaving = false)
                _events.emit(AddGatewayEvent.GatewaySaved)
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(isSaving = false)
                emitError("Could not complete pairing: ${error.message ?: "unknown error"}")
            }
        }
    }

    private fun loadExisting(id: String) {
        viewModelScope.launch {
            val gateway = gatewaySourceRepository.getGateway(id)
            if (gateway != null) {
                existingGateway = gateway
                _name.value = gateway.name
                _scheme.value = gateway.scheme
                _host.value = gateway.host
            } else if (isEditMode) {
                _events.emit(AddGatewayEvent.ShowError("Gateway could not be loaded. The credential may be corrupted. Please remove and re-pair."))
            }
        }
    }

    private suspend fun emitError(message: String) {
        _events.emit(AddGatewayEvent.ShowError(message))
    }

    fun showMessage(message: String) {
        viewModelScope.launch {
            emitError(message)
        }
    }

    private fun clearStatus() {
        _uiState.value = _uiState.value.copy(status = null)
    }

    private fun normalizedHost(): String? {
        return _host.value.trim().ifBlank { null }
    }

    private fun extractHostnameFromHost(host: String): String {
        val hostname = host.substringBefore(':')
        return hostname.ifBlank { "Gateway" }
    }

    private fun defaultDeviceName(): String {
        return listOfNotNull(Build.MANUFACTURER, Build.MODEL)
            .joinToString(" ")
            .trim()
            .ifBlank { "Ferngeist Android" }
    }
}

sealed interface AddGatewayEvent {
    data object GatewaySaved : AddGatewayEvent
    data class ShowError(val message: String) : AddGatewayEvent
}

private fun String.toEpochMillisOrNull(): Long? {
    return runCatching { java.time.Instant.parse(this).toEpochMilli() }.getOrNull()
}
