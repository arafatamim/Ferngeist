package com.tamimarafat.ferngeist.feature.serverlist

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.gateway.GatewayCredentialExpiredException
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import com.tamimarafat.ferngeist.gateway.gatewayErrorMessage
import com.tamimarafat.ferngeist.gateway.refreshGatewaySourceIfNeeded
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

internal const val CUSTOM_AGENT_MAX_NAME_LENGTH = 80
internal const val CUSTOM_AGENT_MAX_COMMAND_LENGTH = 1024
internal const val CUSTOM_AGENT_MAX_ARGS = 20
internal const val CUSTOM_AGENT_MAX_ARG_LENGTH = 1024

/** A host-absolute path, POSIX or Windows. The gateway host may be either. */
private fun String.isAbsoluteHostPath(): Boolean = startsWith("/") || WINDOWS_ABSOLUTE_PATH.matches(this)

private val WINDOWS_ABSOLUTE_PATH = Regex("""^[A-Za-z]:[\\/].*""")

/**
 * Mirrors the gateway's own rules (`customagents.validateCustomAgentFields` +
 * `catalog.validateExecutableName`) so a rejected form never needs a round trip.
 * Returns the message resource, or null when the input is acceptable.
 */
@StringRes
internal fun validateCustomAgent(
    displayName: String,
    command: String,
    args: List<String>,
): Int? {
    val name = displayName.trim()
    val cmd = command.trim()
    return when {
        name.isBlank() -> R.string.serverlist_custom_agent_error_name_required
        name.length > CUSTOM_AGENT_MAX_NAME_LENGTH -> R.string.serverlist_custom_agent_error_name_too_long
        cmd.isBlank() -> R.string.serverlist_custom_agent_error_command_required
        cmd.length > CUSTOM_AGENT_MAX_COMMAND_LENGTH -> R.string.serverlist_custom_agent_error_command_too_long
        args.size > CUSTOM_AGENT_MAX_ARGS -> R.string.serverlist_custom_agent_error_too_many_args
        args.any { it.length > CUSTOM_AGENT_MAX_ARG_LENGTH } -> R.string.serverlist_custom_agent_error_arg_too_long
        else -> commandError(cmd)
    }
}

@StringRes
private fun commandError(command: String): Int? =
    when {
        command.isAbsoluteHostPath() -> null
        command.contains(' ') || command.contains('\t') -> R.string.serverlist_custom_agent_error_command_single_name
        command.contains('/') || command.contains('\\') ->
            R.string.serverlist_custom_agent_error_command_absolute_or_name
        else -> null
    }

/** Splits the arguments field on whitespace; the gateway rejects empty entries. */
internal fun splitCustomAgentArguments(value: String): List<String> =
    value.split(' ', '\t', '\n', '\r').filter { it.isNotBlank() }

sealed interface AddCustomAgentEvent {
    /** Created and resolvable on the gateway host. */
    data object Created : AddCustomAgentEvent

    /** Created, but the command is not on the host's PATH: starting it will fail. */
    data class CreatedNotDetected(
        val command: String,
    ) : AddCustomAgentEvent

    data class ShowError(
        val message: String,
    ) : AddCustomAgentEvent
}

@HiltViewModel
class AddCustomAgentViewModel
    @Inject
    constructor(
        private val application: Application,
        private val gatewaySourceRepository: GatewaySourceRepository,
        private val gatewayRepository: GatewayRepository,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val gatewayId: String = savedStateHandle.get<String>("serverId").orEmpty()
        private val resources get() = application.resources

        private val _displayName = MutableStateFlow("")
        val displayName: StateFlow<String> = _displayName.asStateFlow()

        private val _command = MutableStateFlow("")
        val command: StateFlow<String> = _command.asStateFlow()

        private val _arguments = MutableStateFlow("")
        val arguments: StateFlow<String> = _arguments.asStateFlow()

        private val _hint = MutableStateFlow("")
        val hint: StateFlow<String> = _hint.asStateFlow()

        private val _isSubmitting = MutableStateFlow(false)
        val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

        private val _events = MutableSharedFlow<AddCustomAgentEvent>()
        val events: SharedFlow<AddCustomAgentEvent> = _events.asSharedFlow()

        fun updateDisplayName(value: String) {
            _displayName.value = value
        }

        fun updateCommand(value: String) {
            _command.value = value
        }

        fun updateArguments(value: String) {
            _arguments.value = value
        }

        fun updateHint(value: String) {
            _hint.value = value
        }

        fun submit() {
            val name = _displayName.value.trim()
            val command = _command.value.trim()
            val args = splitCustomAgentArguments(_arguments.value)
            val rejection = validateCustomAgent(name, command, args)
            if (rejection != null) {
                // emit() suspends until the screen collects; never block the caller.
                viewModelScope.launch { emitError(resources.getString(rejection)) }
            } else if (!_isSubmitting.value) {
                _isSubmitting.value = true
                viewModelScope.launch { create(name, command, args) }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun create(
            name: String,
            command: String,
            args: List<String>,
        ) {
            try {
                val stored = gatewaySourceRepository.getGateway(gatewayId)
                if (stored == null) {
                    emitError(resources.getString(R.string.serverlist_custom_agent_error_gateway_missing))
                } else {
                    val gateway =
                        refreshGatewaySourceIfNeeded(stored, gatewayRepository, gatewaySourceRepository)
                    val created =
                        gatewayRepository.createCustomAgent(
                            scheme = gateway.scheme,
                            host = gateway.host,
                            gatewayCredential = gateway.gatewayCredential,
                            displayName = name,
                            command = command,
                            args = args,
                            hint = _hint.value.trim(),
                        )
                    _events.emit(
                        if (created.detected) {
                            AddCustomAgentEvent.Created
                        } else {
                            AddCustomAgentEvent.CreatedNotDetected(command)
                        },
                    )
                }
            } catch (_: GatewayCredentialExpiredException) {
                gatewaySourceRepository.deleteGateway(gatewayId)
                emitError(resources.getString(R.string.serverlist_custom_agent_error_credential_expired))
            } catch (error: GatewayRequestException) {
                emitError(
                    gatewayErrorMessage(error.responseBody)
                        ?: resources.getString(R.string.serverlist_custom_agent_error_create_failed),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A transport fault (host unreachable, TLS, malformed body) must not escape
                // viewModelScope.launch: an uncaught exception there kills the process.
                emitError(resources.getString(R.string.serverlist_custom_agent_error_create_failed))
            } finally {
                _isSubmitting.value = false
            }
        }

        private suspend fun emitError(message: String) {
            _events.emit(AddCustomAgentEvent.ShowError(message))
        }
    }
