package com.tamimarafat.ferngeist.acp.bridge.facade.auth

import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthChallenge
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticateResult
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticationRequiredException
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnection
import com.tamimarafat.ferngeist.acp.bridge.connection.LaunchRepositories
import com.tamimarafat.ferngeist.acp.bridge.connection.formatAcpErrorMessage
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPort
import com.tamimarafat.ferngeist.core.model.auth.AuthenticationEnvVar
import com.tamimarafat.ferngeist.core.model.auth.AuthenticationMethod
import com.tamimarafat.ferngeist.core.model.auth.AuthenticationRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Owns the challenge on this chat's connection; never authenticates on a list transport. */
internal class ChatAuthentication(
    private val connection: AcpConnection,
    private val repositories: LaunchRepositories,
    private val serverId: String,
) {
    private val pending = MutableStateFlow<AuthenticationRequest?>(null)
    val request = pending.asStateFlow()

    fun show(challenge: AcpAuthChallenge) {
        pending.value =
            AuthenticationRequest(
                agentName = challenge.agentInfo.name,
                methods =
                    challenge.authMethods.map { method ->
                        AuthenticationMethod(
                            id = method.id,
                            name = method.name,
                            description = method.description,
                            type = method.type,
                            envVars =
                                method.envVars.map {
                                    AuthenticationEnvVar(
                                        it.name,
                                        it.label,
                                        it.secret,
                                        it.optional,
                                    )
                                },
                            link = method.link,
                            args = method.args,
                        )
                    },
                errorMessage = challenge.message,
            )
    }

    fun dismiss() {
        if (pending.value?.isAuthenticating != true) pending.value = null
    }

    suspend fun createSession(cwd: String): SessionPort? =
        try {
            connection.sessions.createSession(cwd)
        } catch (error: AcpAuthenticationRequiredException) {
            show(error.challenge)
            null
        }

    suspend fun authenticate(methodId: String): Boolean {
        val challenge = pending.value ?: return false
        // Terminal auth was not advertised by this client; env auth needs a runtime restart.
        if (challenge.isAuthenticating ||
            challenge.methods.none { it.id == methodId && it.type == "agent" }
        ) {
            return false
        }
        pending.value = challenge.copy(isAuthenticating = true, errorMessage = null)
        return runCatching {
            val result = connection.authenticate(methodId)
            when (result) {
                is AcpAuthenticateResult.Failure -> {
                    pending.value = challenge.copy(errorMessage = result.message)
                    false
                }
                AcpAuthenticateResult.Success -> {
                    withContext(repositories.ioDispatcher) {
                        repositories.launchableTargetRepository.updatePreferredAuthMethod(serverId, methodId)
                    }
                    pending.value = null
                    true
                }
            }
        }.getOrElse { error ->
            if (error is CancellationException || error !is Exception) throw error
            pending.value = challenge.copy(errorMessage = formatAcpErrorMessage(error, "Authentication failed"))
            false
        }.also {
            pending.value = pending.value?.copy(isAuthenticating = false)
        }
    }
}
