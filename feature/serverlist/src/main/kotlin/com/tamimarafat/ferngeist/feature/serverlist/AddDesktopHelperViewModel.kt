package com.tamimarafat.ferngeist.feature.serverlist

import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.core.model.ServerConfig
import com.tamimarafat.ferngeist.core.model.ServerSourceKind
import com.tamimarafat.ferngeist.core.model.repository.ServerRepository
import com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperPairingChallenge
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
    val pairingChallenge: DesktopHelperPairingChallenge? = null,
    val importedPairingPayload: DesktopHelperPairingPayload? = null,
    val isCheckingStatus: Boolean = false,
    val isPairing: Boolean = false,
    val isSaving: Boolean = false,
)

enum class DesktopHelperPairingInputMode {
    QR_PAYLOAD,
    MANUAL_CODE,
}

/**
 * Drives desktop companion pairing. It supports three pairing paths:
 * helper-started local pairing, imported QR payloads, and manual host+code.
 */
@HiltViewModel
class AddDesktopHelperViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val helperRepository: DesktopHelperRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialServerId: String? = savedStateHandle.get<String>("serverId")

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _scheme = MutableStateFlow("http")
    val scheme: StateFlow<String> = _scheme.asStateFlow()

    private val _host = MutableStateFlow("")
    val host: StateFlow<String> = _host.asStateFlow()

    private val _deviceName = MutableStateFlow(defaultDeviceName())
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _pairingInputMode = MutableStateFlow(DesktopHelperPairingInputMode.QR_PAYLOAD)
    val pairingInputMode: StateFlow<DesktopHelperPairingInputMode> = _pairingInputMode.asStateFlow()

    private val _pairingQrPayload = MutableStateFlow("")
    val pairingQrPayload: StateFlow<String> = _pairingQrPayload.asStateFlow()

    private val _pairingCode = MutableStateFlow("")
    val pairingCode: StateFlow<String> = _pairingCode.asStateFlow()

    private val _uiState = MutableStateFlow(AddDesktopHelperUiState())
    val uiState: StateFlow<AddDesktopHelperUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<AddDesktopHelperEvent>()
    val events = _events.asSharedFlow()

    val isEditMode: Boolean = initialServerId != null

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
    }

    fun updateHost(value: String) {
        _host.value = value
    }

    fun updateDeviceName(value: String) {
        _deviceName.value = value
    }

    fun updatePairingInputMode(value: DesktopHelperPairingInputMode) {
        _pairingInputMode.value = value
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
            viewModelScope.launch { emitError("QR payload is invalid") }
            return
        }
        _scheme.value = payload.scheme
        _host.value = payload.host
        _pairingCode.value = payload.code
        _uiState.value = _uiState.value.copy(importedPairingPayload = payload)
    }

    fun checkStatus() {
        viewModelScope.launch {
            val helperHost = normalizedHost()
            if (helperHost == null) {
                emitError("Helper host is required")
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
                emitError("Could not reach helper: ${error.message ?: "unknown error"}")
            }
        }
    }

    fun startPairing() {
        viewModelScope.launch {
            val helperHost = normalizedHost()
            if (helperHost == null) {
                emitError("Helper host is required")
                return@launch
            }
            _uiState.value = _uiState.value.copy(isPairing = true)
            runCatching {
                helperRepository.startPairing(_scheme.value, helperHost)
            }.onSuccess { challenge ->
                _uiState.value = _uiState.value.copy(
                    pairingChallenge = challenge,
                    importedPairingPayload = null,
                    isPairing = false,
                )
                _pairingCode.value = challenge.code
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(isPairing = false)
                emitError("Could not start pairing: ${error.message ?: "unknown error"}")
            }
        }
    }

    fun completePairingAndSave() {
        viewModelScope.launch {
            val helperHost = normalizedHost()
            if (helperHost == null) {
                emitError("Helper host is required")
                return@launch
            }
            val helperName = _name.value.trim()
            if (helperName.isBlank()) {
                emitError("Name is required")
                return@launch
            }
            val challenge = _uiState.value.pairingChallenge
            val importedPayload = _uiState.value.importedPairingPayload
            val manualCode = _pairingCode.value.trim()
            val resolvedCode = when {
                manualCode.isNotBlank() -> manualCode
                importedPayload != null -> importedPayload.code
                challenge != null -> challenge.code
                else -> ""
            }
            if (resolvedCode.isBlank()) {
                emitError("Start pairing, scan a QR payload, or enter a code manually")
                return@launch
            }

            _uiState.value = _uiState.value.copy(isSaving = true)
            runCatching {
                helperRepository.completePairing(
                    scheme = _scheme.value,
                    host = helperHost,
                    challengeId = importedPayload?.challengeId ?: challenge?.challengeId.orEmpty(),
                    code = resolvedCode,
                    deviceName = _deviceName.value.trim().ifBlank { defaultDeviceName() },
                )
            }.onSuccess { pairing ->
                val helperStatus = _uiState.value.status
                val server = ServerConfig(
                    id = initialServerId ?: java.util.UUID.randomUUID().toString(),
                    name = helperName,
                    sourceKind = ServerSourceKind.DESKTOP_HELPER,
                    scheme = _scheme.value,
                    host = helperHost,
                    helperCredential = pairing.token,
                    helperCredentialExpiresAt = pairing.expiresAt.toEpochMillisOrNull(),
                    helperRemoteMode = helperStatus?.remote?.mode,
                    token = "",
                    workingDirectory = "/",
                )
                if (isEditMode) {
                    serverRepository.updateServer(server)
                } else {
                    serverRepository.addServer(server)
                }
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
            val server = serverRepository.getServer(id) ?: return@launch
            _name.value = server.name
            _scheme.value = server.scheme
            _host.value = server.host
            _pairingCode.value = ""
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
