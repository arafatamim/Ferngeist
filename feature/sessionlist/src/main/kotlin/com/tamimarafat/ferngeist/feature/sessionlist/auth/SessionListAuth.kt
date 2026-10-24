package com.tamimarafat.ferngeist.feature.sessionlist.auth

import com.tamimarafat.ferngeist.acp.bridge.catalog.CatalogAuthMethod
import com.tamimarafat.ferngeist.acp.bridge.catalog.SessionCatalog
import com.tamimarafat.ferngeist.acp.bridge.catalog.SessionCatalog.ListSessionsResult
import com.tamimarafat.ferngeist.acp.bridge.catalog.SessionCatalog.ListingAuthentication
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.store.AuthEnvValuesUnreadableException
import com.tamimarafat.ferngeist.feature.sessionlist.PendingAuthAction
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListEvent
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListOutputs
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListPendingAuthentication
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListRepositories
import com.tamimarafat.ferngeist.gateway.GatewayCredentialExpiredException
import com.tamimarafat.ferngeist.gateway.refreshGatewaySourceIfNeeded
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * A sign-in that succeeded: the target and method to remember as preferred, and the action that
 * was blocked on it. The view model persists the preference and retries the action.
 */
internal data class AuthCompletion(
    val targetId: String,
    val methodId: String,
    val retry: PendingAuthAction,
)

/**
 * ACP and gateway auth for one session list: the pending challenge the dialog shows, and the
 * steps that answer it. Each step returns what the caller should retry instead of launching it,
 * so the view model keeps ownership of its coroutine scope.
 */
internal class SessionListAuth(
    private val serverId: String,
    private val server: StateFlow<LaunchableTarget?>,
    private val outputs: SessionListOutputs,
    private val sessionCatalog: SessionCatalog,
    private val repos: SessionListRepositories,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _pendingAuthentication = MutableStateFlow<SessionListPendingAuthentication?>(null)
    val pendingAuthentication: StateFlow<SessionListPendingAuthentication?> = _pendingAuthentication.asStateFlow()

    fun dismiss() {
        _pendingAuthentication.value = null
        outputs.loading.value = false
    }

    /**
     * Builds and surfaces a pending authentication model from a hub
     * listing result that hit an auth challenge.
     */
    suspend fun handleAuthenticationRequired(
        auth: ListSessionsResult.AuthRequired,
        action: PendingAuthAction,
    ) {
        val currentServer =
            server.value
                ?: withContext(ioDispatcher) { repos.launchableTargetRepository.getTarget(serverId) }
                ?: run {
                    outputs.events.emit(
                        SessionListEvent.ShowError(
                            "Server was removed before authentication could complete.",
                        ),
                    )
                    outputs.loading.value = false
                    return
                }
        _pendingAuthentication.value =
            SessionListPendingAuthentication(
                serverId = serverId,
                serverName = currentServer.name,
                agentName = auth.agentName,
                authMethods = auth.authMethods,
                preferredAuthMethodId = currentServer.preferredAuthMethodId,
                persistedEnvValues = sessionCatalog.persistedEnvValues(currentServer.id, auth.authMethods),
                authErrorMessage = auth.challengeMessage,
                gatewayRuntimeId = auth.gatewayRuntimeId,
                pendingAction = action,
            )
        outputs.loading.value = false
    }

    /** Completes the pending challenge with [methodId]; null when there is none or it did not succeed. */
    suspend fun authenticate(
        methodId: String,
        envValues: Map<String, String>,
    ): AuthCompletion? {
        val pending = _pendingAuthentication.value ?: return null
        val method = pending.authMethods.firstOrNull { it.id == methodId } ?: return null

        outputs.loading.value = true
        _pendingAuthentication.update { it?.copy(authErrorMessage = null) }

        // Env-based gateway auth is handled by restarting the gateway runtime with env vars.
        return when {
            method.type == "env" && pending.gatewayRuntimeId != null ->
                authenticateGatewayEnvVar(pending, method, envValues)

            else -> authenticateListing(pending, methodId)
        }
    }

    private suspend fun authenticateListing(
        pending: SessionListPendingAuthentication,
        methodId: String,
    ): AuthCompletion? {
        // The catalog routes the call to the transport that raised the
        // challenge (a borrowed warm chat transport when warm-borrowed).
        val result = sessionCatalog.authenticateListing(serverId, methodId)
        if (result is ListingAuthentication.Failed) return fail(result.message)
        _pendingAuthentication.value = null
        return AuthCompletion(serverId, methodId, pending.pendingAction)
    }

    /**
     * For manual env-var auth Ferngeist cannot inject credentials into the
     * external process, so the user restarts it outside the app and this method
     * reconnects before retrying the blocked session action.
     *
     * Reconnects to a manual ACP server after the user applied env vars.
     */
    suspend fun reconnect(): PendingAuthAction? {
        val pending = _pendingAuthentication.value ?: return null
        val current = server.value ?: return null
        outputs.loading.value = true
        _pendingAuthentication.update { it?.copy(authErrorMessage = null) }

        reconnectFailure(current)?.let { return fail(it) }

        _pendingAuthentication.value = null
        return pending.pendingAction
    }

    /** The message to show when [current] will not reconnect and initialize, else null. */
    private suspend fun reconnectFailure(current: LaunchableTarget): String? {
        val connected =
            when (current) {
                is LaunchableTarget.Manual ->
                    sessionCatalog.reconnectListingToManual(serverId, current)

                is LaunchableTarget.GatewayAgent -> false
            }
        return when {
            !connected -> "Failed to reconnect to ${current.name}"
            !sessionCatalog.initializeListing(serverId) -> "Failed to initialize ${current.name}"
            else -> null
        }
    }

    /** Puts [message] on the challenge and ends the loading state; returns null for the caller to return. */
    private fun fail(message: String): Nothing? {
        _pendingAuthentication.update { it?.copy(authErrorMessage = message) }
        outputs.loading.value = false
        return null
    }

    /**
     * Handles gateway-backed env authentication by restarting the runtime with env vars.
     *
     * Each failure path exits early — no auth context, an unreadable stored copy, a failed
     * restart, a failed reconnect — so the exit count is inherent to the sequence rather
     * than a sign of nested conditions that want flattening.
     */
    private suspend fun authenticateGatewayEnvVar(
        pending: SessionListPendingAuthentication,
        method: CatalogAuthMethod,
        envValues: Map<String, String>,
    ): AuthCompletion? {
        // Gateway-backed env auth requires a fresh process environment. Restart
        // the gateway runtime with the saved values, reconnect, authenticate,
        // then retry the original session action.
        val context = resolveAuthContext() ?: return null
        val currentServer = context.server
        if (!persistEnvValues(currentServer, method, envValues)) return null
        val runtimeId =
            restartAndReconnect(
                pending = pending,
                currentServer = currentServer,
                gatewaySource = context.gatewaySource,
                method = method,
                envValues = envValues,
            ) ?: return null
        return authenticateRestartedRuntime(pending, currentServer, method, runtimeId)
    }

    /** False after surfacing the error when the stored env values cannot be read back. */
    private suspend fun persistEnvValues(
        currentServer: LaunchableTarget,
        method: CatalogAuthMethod,
        envValues: Map<String, String>,
    ): Boolean =
        try {
            sessionCatalog.persistEnvValues(currentServer.id, method, envValues)
            true
        } catch (error: AuthEnvValuesUnreadableException) {
            // The stored copy decrypts but cannot be parsed, so this write's base was an
            // empty map. Continuing would destroy secrets the user still has; the stored
            // blob is left untouched for them to replace.
            outputs.events.emit(
                SessionListEvent.ShowError(
                    error.message ?: "Saved environment values could not be read on this device.",
                ),
            )
            false
        }

    /** Initializes and authenticates the listing on the restarted runtime. */
    private suspend fun authenticateRestartedRuntime(
        pending: SessionListPendingAuthentication,
        currentServer: LaunchableTarget,
        method: CatalogAuthMethod,
        runtimeId: String,
    ): AuthCompletion? {
        // Keep the whole env-auth sequence on the transport that owns the
        // socket — the same transport the catalog just reconnected.
        if (!sessionCatalog.initializeListing(serverId)) {
            _pendingAuthentication.update {
                it?.copy(
                    authErrorMessage = "Failed to initialize ${currentServer.name}",
                    gatewayRuntimeId = runtimeId,
                )
            }
            outputs.loading.value = false
            return null
        }

        val result = sessionCatalog.authenticateListing(serverId, method.id)
        if (result is ListingAuthentication.Failed) {
            _pendingAuthentication.update {
                it?.copy(authErrorMessage = result.message, gatewayRuntimeId = runtimeId)
            }
            outputs.loading.value = false
            return null
        }

        _pendingAuthentication.value = null
        return AuthCompletion(currentServer.id, method.id, pending.pendingAction)
    }

    private data class AuthContext(
        val server: LaunchableTarget,
        val gatewaySource: GatewaySource,
    )

    /**
     * Resolves the server + gateway source for an in-flight authentication,
     * deleting expired credentials. Returns null after surfacing the error.
     */
    private suspend fun resolveAuthContext(): AuthContext? {
        val currentServer =
            server.value ?: run {
                _pendingAuthentication.update {
                    it?.copy(authErrorMessage = "Server was removed before authentication could complete.")
                }
                outputs.loading.value = false
                return null
            }
        val gatewayTarget =
            currentServer as? LaunchableTarget.GatewayAgent ?: run {
                _pendingAuthentication.update {
                    it?.copy(authErrorMessage = "Gateway was not found for ${currentServer.name}.")
                }
                outputs.loading.value = false
                return null
            }
        val gatewaySource = resolveAuthGatewaySource(gatewayTarget) ?: return null
        return AuthContext(currentServer, gatewaySource)
    }

    /**
     * Refreshes the gateway source, deleting the stored credential when it
     * has expired. Returns null after surfacing the failure.
     */
    private suspend fun resolveAuthGatewaySource(gatewayTarget: LaunchableTarget.GatewayAgent): GatewaySource? {
        val source =
            try {
                withContext(ioDispatcher) {
                    refreshGatewaySourceIfNeeded(
                        gatewayTarget.gatewaySource,
                        repos.gatewayRepository,
                        repos.gatewaySourceRepository,
                    )
                }
            } catch (_: GatewayCredentialExpiredException) {
                withContext(ioDispatcher) {
                    repos.gatewaySourceRepository.deleteGateway(gatewayTarget.gatewaySource.id)
                }
                return fail("Gateway credential expired. Please pair this gateway again.")
            }
        if (source.gatewayCredential.isBlank()) return fail("Gateway is not paired.")
        return source
    }

    /**
     * Restarts the gateway runtime with the env payload, then asks the hub
     * to reconnect the raising transport to the restarted runtime.
     * Returns the new runtime id the restart launched, or null after
     * surfacing the failure.
     */
    private suspend fun restartAndReconnect(
        pending: SessionListPendingAuthentication,
        currentServer: LaunchableTarget,
        gatewaySource: GatewaySource,
        method: CatalogAuthMethod,
        envValues: Map<String, String>,
    ): String? {
        val runtimeId =
            pending.gatewayRuntimeId ?: return fail("Gateway runtime context is missing for ${currentServer.name}.")
        val restart =
            runCatching {
                withContext(ioDispatcher) {
                    repos.gatewayRepository.restartRuntime(
                        scheme = gatewaySource.scheme,
                        host = gatewaySource.host,
                        gatewayCredential = gatewaySource.gatewayCredential,
                        runtimeId = runtimeId,
                        envVars = sessionCatalog.envPayload(method, envValues),
                    )
                }
            }.getOrElse { error ->
                return fail(error.message ?: "Failed to restart ${currentServer.name}.")
            }

        // The transport that owns the socket is the one that raised the
        // challenge (a borrowed warm chat transport when warm-borrowed);
        // restart its transport against the new session through the catalog,
        // which retains that transport.
        val reconnected =
            sessionCatalog.reconnectListingToGateway(
                serverId = serverId,
                gatewaySource = gatewaySource,
                restart = restart,
                authMethodId = method.id,
                displayName = currentServer.name,
            )
        if (!reconnected) {
            _pendingAuthentication.update {
                it?.copy(
                    authErrorMessage = "Failed to reconnect to ${currentServer.name}",
                    // The gateway closed the old runtime once the new one started.
                    gatewayRuntimeId = restart.runtimeId,
                )
            }
            outputs.loading.value = false
            return null
        }
        return restart.runtimeId
    }
}
