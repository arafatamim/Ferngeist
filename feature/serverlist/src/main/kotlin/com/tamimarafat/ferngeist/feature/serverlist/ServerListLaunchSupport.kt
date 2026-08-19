package com.tamimarafat.ferngeist.feature.serverlist

import android.util.Log
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthMethodInfo
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.feature.serverlist.auth.AuthEnvValueStore
import com.tamimarafat.ferngeist.gateway.GatewayCredentialExpiredException
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.refreshGatewaySourceIfNeeded
import com.tamimarafat.ferngeist.gateway.resolveGatewayWebSocketUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

private const val LOG_TAG = "ServerListViewModel"

/**
 * Builds the env var payload for a gateway restart, keeping only values the
 * auth method declares (or non-optional blanks so the runtime reports them).
 */
internal fun buildEnvPayload(
    method: AcpAuthMethodInfo,
    envValues: Map<String, String>,
): Map<String, String> =
    buildMap {
        method.envVars.forEach { envVar ->
            val value = envValues[envVar.name]?.trim().orEmpty()
            if (value.isNotEmpty() || !envVar.optional) {
                put(envVar.name, value)
            }
        }
    }

/** Loads persisted env values that belong to any of the given auth methods. */
internal suspend fun loadPersistedEnvValues(
    authEnvValueStore: AuthEnvValueStore,
    serverId: String,
    authMethods: List<AcpAuthMethodInfo>,
): Map<String, String> {
    val allowedNames =
        authMethods
            .flatMap { method -> method.envVars }
            .mapTo(linkedSetOf()) { envVar -> envVar.name }
    if (allowedNames.isEmpty()) {
        return emptyMap()
    }
    return withContext(Dispatchers.IO) {
        authEnvValueStore
            .getValues(serverId)
            .filterKeys { key -> key in allowedNames }
    }
}

/** Persists env values for the auth method's declared env var names. */
internal suspend fun persistEnvValues(
    authEnvValueStore: AuthEnvValueStore,
    serverId: String,
    method: AcpAuthMethodInfo,
    envValues: Map<String, String>,
) {
    if (method.envVars.isEmpty()) {
        return
    }
    withContext(Dispatchers.IO) {
        authEnvValueStore.updateValues(
            serverId = serverId,
            envVarNames = method.envVars.mapTo(linkedSetOf()) { envVar -> envVar.name },
            envValues = envValues.filterKeys { key -> method.envVars.any { envVar -> envVar.name == key } },
        )
    }
}

/**
 * Gateway agents need a two-stage launch: start or reuse the gateway runtime,
 * then request a runtime-scoped ACP WebSocket handoff.
 */
internal suspend fun buildGatewayLaunchContext(
    gatewayRepository: GatewayRepository,
    gatewaySourceRepository: GatewaySourceRepository,
    server: LaunchableTarget.GatewayAgent,
): Result<GatewayLaunchContext> {
    val gatewaySource =
        try {
            withContext(Dispatchers.IO) {
                refreshGatewaySourceIfNeeded(server.gatewaySource, gatewayRepository, gatewaySourceRepository)
            }
        } catch (_: GatewayCredentialExpiredException) {
            withContext(Dispatchers.IO) {
                gatewaySourceRepository.deleteGateway(server.gatewaySource.id)
            }
            return Result.failure(
                IllegalStateException("Gateway credential expired. Please pair this gateway again."),
            )
        }
    if (gatewaySource.gatewayCredential.isBlank()) {
        return Result.failure(IllegalStateException("Gateway is not paired"))
    }

    return runCatching {
        val runtime =
            withContext(Dispatchers.IO) {
                gatewayRepository.startAgent(
                    scheme = gatewaySource.scheme,
                    host = gatewaySource.host,
                    gatewayCredential = gatewaySource.gatewayCredential,
                    agentId = server.binding.agentId,
                )
            }
        val handoff =
            withContext(Dispatchers.IO) {
                gatewayRepository.connectRuntime(
                    scheme = gatewaySource.scheme,
                    host = gatewaySource.host,
                    gatewayCredential = gatewaySource.gatewayCredential,
                    runtimeId = runtime.id,
                    sessionMode = "resilient",
                )
            }
        GatewayLaunchContext(
            config =
                AcpConnectionConfig(
                    scheme = gatewaySource.scheme,
                    host = gatewaySource.host,
                    webSocketUrl = resolveGatewayWebSocketUrl(gatewaySource, handoff),
                    webSocketBearerToken = handoff.bearerToken,
                    preferredAuthMethodId = server.preferredAuthMethodId,
                    gatewayRuntimeId = runtime.id,
                    gatewaySourceId = gatewaySource.id,
                    serverDisplayName = server.name,
                    sessionId = handoff.sessionId,
                    attachToken = handoff.attachToken,
                    gatewayScheme = gatewaySource.scheme,
                    gatewayHost = gatewaySource.host,
                    gatewayCredential = gatewaySource.gatewayCredential,
                ),
            gatewaySource = gatewaySource,
            runtimeId = runtime.id,
        )
    }
}

/**
 * Gateway-backed initialize failures can happen after the gateway has already
 * successfully started and handed off the runtime. Surface the recorded ACP
 * initialization error and, when available, the last gateway runtime stderr
 * or ACP stdout line so the user sees the real agent failure instead of the
 * generic session initialization message.
 */
internal suspend fun buildInitializeFailureMessage(
    connectionManager: AcpConnectionManager,
    gatewayRepository: GatewayRepository,
    gatewaySourceRepository: GatewaySourceRepository,
    server: LaunchableTarget,
    gatewaySource: GatewaySource?,
    runtimeId: String?,
): String {
    val diagnosticMessage =
        connectionManager.diagnostics.value.recentErrors
            .lastOrNull { entry -> entry.source == "initialize" || entry.source == "connection" }
            ?.message
            ?.takeIf { it.isNotBlank() }
            ?: "Failed to initialize session with ${server.name}"

    if (gatewaySource == null || runtimeId.isNullOrBlank() || gatewaySource.gatewayCredential.isBlank()) {
        return diagnosticMessage
    }

    val runtimeHint =
        runCatching {
            val refreshedSource =
                try {
                    withContext(Dispatchers.IO) {
                        refreshGatewaySourceIfNeeded(gatewaySource, gatewayRepository, gatewaySourceRepository)
                    }
                } catch (error: GatewayCredentialExpiredException) {
                    // The credential is dead — clear it so the next launch
                    // surfaces the pairing flow instead of opaque 401s.
                    withContext(Dispatchers.IO) {
                        gatewaySourceRepository.deleteGateway(gatewaySource.id)
                    }
                    throw error
                }
            gatewayRepository.fetchRuntimeLogs(
                scheme = refreshedSource.scheme,
                host = refreshedSource.host,
                gatewayCredential = refreshedSource.gatewayCredential,
                runtimeId = runtimeId,
            )
        }.getOrNull()
            ?.asReversed()
            ?.firstNotNullOfOrNull { entry ->
                entry.message.trim().takeIf {
                    it.isNotEmpty() && (entry.stream == "stderr" || entry.stream == "acp.stdout")
                }
            }

    if (runtimeHint.isNullOrBlank() || diagnosticMessage.contains(runtimeHint, ignoreCase = true)) {
        return diagnosticMessage
    }
    return "$diagnosticMessage\nGateway runtime: $runtimeHint"
}

internal fun shortInitializeFailureMessage(server: LaunchableTarget): String =
    "Failed to initialize session with ${server.name}. See logcat for details."

internal fun logConnectionFailure(
    server: LaunchableTarget,
    phase: String,
    message: String,
) {
    runCatching {
        Log.e(LOG_TAG, "${server.name} $phase failed\n$message")
    }
}

internal data class GatewayLaunchContext(
    val config: AcpConnectionConfig,
    val gatewaySource: GatewaySource,
    val runtimeId: String,
)

internal data class GatewayAuthContext(
    val server: LaunchableTarget,
    val gatewaySource: GatewaySource,
)

/**
 * Resolves the server and a valid gateway source for an in-flight
 * authentication, deleting expired credentials. Returns null after
 * surfacing the error that stopped authentication.
 */
internal suspend fun resolveGatewayAuthContext(
    pending: PendingAuthentication,
    uiState: kotlinx.coroutines.flow.MutableStateFlow<ServerListUiState>,
    launchableTargetRepository: com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository,
    gatewayRepository: GatewayRepository,
    gatewaySourceRepository: GatewaySourceRepository,
): GatewayAuthContext? {
    val server = resolveAuthTarget(pending, uiState, launchableTargetRepository) ?: return null
    val gatewayTarget =
        server as? LaunchableTarget.GatewayAgent
            ?: return failGatewayAuth(uiState, pending, "Gateway was not found for ${server.name}.")
    val gatewaySource =
        resolveAuthGatewaySource(pending, uiState, gatewayTarget, gatewayRepository, gatewaySourceRepository)
            ?: return null
    return GatewayAuthContext(server, gatewaySource)
}

/** Resolves the pending server, or surfaces the removed-server error. */
internal suspend fun resolveAuthTarget(
    pending: PendingAuthentication,
    uiState: kotlinx.coroutines.flow.MutableStateFlow<ServerListUiState>,
    launchableTargetRepository: com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository,
): LaunchableTarget? =
    withContext(Dispatchers.IO) {
        launchableTargetRepository.getTarget(pending.serverId)
    }
        ?: failGatewayAuth(uiState, pending, "Server was removed before authentication could complete.")

/**
 * Refreshes the gateway source, deleting the stored credential when it has
 * expired. Returns null after surfacing the failure.
 */
internal suspend fun resolveAuthGatewaySource(
    pending: PendingAuthentication,
    uiState: kotlinx.coroutines.flow.MutableStateFlow<ServerListUiState>,
    gatewayTarget: LaunchableTarget.GatewayAgent,
    gatewayRepository: GatewayRepository,
    gatewaySourceRepository: GatewaySourceRepository,
): GatewaySource? {
    val source =
        try {
            withContext(Dispatchers.IO) {
                refreshGatewaySourceIfNeeded(gatewayTarget.gatewaySource, gatewayRepository, gatewaySourceRepository)
            }
        } catch (_: GatewayCredentialExpiredException) {
            // The stored credential is dead — clear it so the user is
            // forced through the pairing flow again.
            withContext(Dispatchers.IO) {
                gatewaySourceRepository.deleteGateway(gatewayTarget.gatewaySource.id)
            }
            return failGatewayAuth(uiState, pending, "Gateway credential expired. Please pair this gateway again.")
        }
    if (source.gatewayCredential.isBlank()) {
        return failGatewayAuth(uiState, pending, "Gateway is not paired.")
    }
    return source
}

/** Surfaces the auth failure and signals the caller to stop. */
internal fun failGatewayAuth(
    uiState: kotlinx.coroutines.flow.MutableStateFlow<ServerListUiState>,
    pending: PendingAuthentication,
    message: String,
): Nothing? {
    uiState.update {
        it.copy(
            connectingServerId = null,
            pendingAuthentication = pending.copy(authErrorMessage = message),
        )
    }
    return null
}
