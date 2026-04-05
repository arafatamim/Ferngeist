package com.tamimarafat.ferngeist.feature.serverlist

import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.core.model.DesktopHelperSource
import com.tamimarafat.ferngeist.core.model.repository.DesktopHelperSourceRepository
import com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperPairingPayload
import com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperPairingPayloadParser
import com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperRepository
import com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddDesktopHelperUiState(
    val status: DesktopHelperStatus? = null,
    val importedPairingPayload: DesktopHelperPairingPayload? = null,
    val isCheckingStatus: Boolean = false,
    val isSaving: Boolean = false,
)

@HiltViewModel
class AddDesktopHelperViewModel @Inject constructor(
    private val helperSourceRepository: DesktopHelperSourceRepository,
    private val helperRepository: DesktopHelperRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialServerId: String? = savedStateHandle.get<String>("serverId")
    private var existingHelper: DesktopHelperSource? = null

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

    private val _uiState = MutableStateFlow(AddDesktopHelperUiState())
    val uiState: StateFlow<AddDesktopHelperUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<AddDesktopHelperEvent>()
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
        val payload = DesktopHelperPairingPayloadParser.parse(_pairingQrPayload.value)
        if (payload == null) {
            viewModelScope.launch { emitError("Pairing payload is invalid. Scan the QR from `ferngeist pair` or paste the full payload.") }
            return
        }
        _scheme.value = payload.scheme
        _host.value = payload.host
        _pairingCode.value = payload.code
        activeChallengeId = payload.challengeId
        _uiState.value = _uiState.value.copy(importedPairingPayload = payload, status = null)
    }

    fun checkStatus() {
        viewModelScope.launch {
            val helperHost = normalizedHost()
            if (helperHost == null) {
                emitError("Desktop companion host is required")
                return@launch
            }
            _uiState.value = _uiState.value.copy(isCheckingStatus = true)
            runCatching {
                helperRepository.fetchStatus(_scheme.value, helperHost)
            }.onSuccess { status ->
                _uiState.value = _uiState.value.copy(status = status, isCheckingStatus = false)
                if (_name.value.isBlank()) {
                    _name.value = status.name
                }
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(isCheckingStatus = false)
                emitError("Could not reach desktop companion: ${error.message ?: "unknown error"}")
            }
        }
    }

    fun saveDesktopCompanion() {
        viewModelScope.launch {
            val helperHost = normalizedHost()
            if (helperHost == null) {
                emitError("Desktop companion host is required")
                return@launch
            }
            val helperName = _name.value.trim()
            if (helperName.isBlank()) {
                emitError("Name is required")
                return@launch
            }

            if (isEditMode) {
                val currentHelper = existingHelper
                if (currentHelper == null) {
                    emitError("Desktop companion could not be loaded")
                    return@launch
                }
                _uiState.value = _uiState.value.copy(isSaving = true)
                runCatching {
                    val updatedHelper = currentHelper.copy(
                        name = helperName,
                        scheme = _scheme.value,
                        host = helperHost,
                        helperRemoteMode = _uiState.value.status?.remote?.mode ?: currentHelper.helperRemoteMode,
                    )
                    helperSourceRepository.updateHelper(updatedHelper)
                    existingHelper = updatedHelper
                }.onSuccess {
                    _uiState.value = _uiState.value.copy(isSaving = false)
                    _events.emit(AddDesktopHelperEvent.HelperSaved)
                }.onFailure { error ->
                    _uiState.value = _uiState.value.copy(isSaving = false)
                    emitError("Could not save desktop companion: ${error.message ?: "unknown error"}")
                }
                return@launch
            }

            val importedPayload = _uiState.value.importedPairingPayload
            val manualCode = _pairingCode.value.trim()
            val resolvedCode = importedPayload?.code ?: manualCode
            val challengeId = activeChallengeId ?: importedPayload?.challengeId
            if (resolvedCode.isBlank() || challengeId.isNullOrBlank()) {
                emitError("Scan the QR, paste the payload, or type the pairing code first")
                return@launch
            }

            _uiState.value = _uiState.value.copy(isSaving = true)
            runCatching {
                helperRepository.completePairing(
                    scheme = _scheme.value,
                    host = helperHost,
                    challengeId = challengeId,
                    code = resolvedCode,
                    deviceName = _deviceName.value.trim().ifBlank { defaultDeviceName() },
                )
            }.onSuccess { pairing ->
                val helperStatus = _uiState.value.status
                val helper = DesktopHelperSource(
                    id = initialServerId ?: java.util.UUID.randomUUID().toString(),
                    name = helperName,
                    scheme = _scheme.value,
                    host = helperHost,
                    helperCredential = pairing.helperCredential,
                    helperCredentialExpiresAt = pairing.expiresAt.toEpochMillisOrNull(),
                    helperRemoteMode = helperStatus?.remote?.mode,
                )
                helperSourceRepository.addHelper(helper)
                _uiState.value = _uiState.value.copy(isSaving = false)
                _events.emit(AddDesktopHelperEvent.HelperSaved)
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(isSaving = false)
                emitError("Could not complete pairing: ${error.message ?: "unknown error"}")
            }
        }
    }

    private fun loadExisting(id: String) {
        viewModelScope.launch {
            val helper = helperSourceRepository.getHelper(id) ?: return@launch
            existingHelper = helper
            _name.value = helper.name
            _scheme.value = helper.scheme
            _host.value = helper.host
        }
    }

    private suspend fun emitError(message: String) {
        _events.emit(AddDesktopHelperEvent.ShowError(message))
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

    private fun defaultDeviceName(): String {
        return listOfNotNull(Build.MANUFACTURER, Build.MODEL)
            .joinToString(" ")
            .trim()
            .ifBlank { "Ferngeist Android" }
    }
}

sealed interface AddDesktopHelperEvent {
    data object HelperSaved : AddDesktopHelperEvent
    data class ShowError(val message: String) : AddDesktopHelperEvent
}

private fun String.toEpochMillisOrNull(): Long? {
    return runCatching { java.time.Instant.parse(this).toEpochMilli() }.getOrNull()
}
