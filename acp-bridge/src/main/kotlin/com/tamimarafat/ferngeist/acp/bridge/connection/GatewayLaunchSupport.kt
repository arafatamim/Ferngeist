package com.tamimarafat.ferngeist.acp.bridge.connection

import android.util.Log
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.store.AuthEnvValueStore
import com.tamimarafat.ferngeist.gateway.GatewayCredentialExpiredException
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.launchGatewayRuntime
import com.tamimarafat.ferngeist.gateway.refreshGatewaySourceIfNeeded
import com.tamimarafat.ferngeist.gateway.resolveGatewayWebSocketUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LOG_TAG = "GatewayLaunchSupport"

/** The resolved launch context for a gateway-backed target: transport config plus runtime identity. */
data class GatewayLaunchContext(
    val config: AcpConnectionConfig,
    val gatewaySource: GatewaySource,
    val runtimeId: String,
)

/**
 * Builds the env var payload for a gateway restart, keeping only values the
 * auth method declares (or non-optional blanks so the runtime reports them).
 */
fun buildEnvPayload(
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
suspend fun loadPersistedEnvValues(
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
suspend fun persistEnvValues(
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
suspend fun buildGatewayLaunchContext(
    gatewayRepository: GatewayRepository,
    gatewaySourceRepository: GatewaySourceRepository,
    server: LaunchableTarget.GatewayAgent,
): Result<GatewayLaunchContext> =
    launchGatewayRuntime(
        gatewayRepository = gatewayRepository,
        gatewaySourceRepository = gatewaySourceRepository,
        gatewaySource = server.gatewaySource,
        agentId = server.binding.agentId,
    ).map { result ->
        val source = result.gatewaySource
        val handoff = result.handoff
        GatewayLaunchContext(
            config =
                AcpConnectionConfig(
                    scheme = source.scheme,
                    host = source.host,
                    webSocketUrl = resolveGatewayWebSocketUrl(source, handoff),
                    webSocketBearerToken = handoff.bearerToken,
                    preferredAuthMethodId = server.preferredAuthMethodId,
                    gatewayRuntimeId = result.runtime.id,
                    gatewaySourceId = source.id,
                    serverDisplayName = server.name,
                    sessionId = handoff.sessionId,
                    attachToken = handoff.attachToken,
                    gatewayScheme = source.scheme,
                    gatewayHost = source.host,
                    gatewayCredential = source.gatewayCredential,
                ),
            gatewaySource = source,
            runtimeId = result.runtime.id,
        )
    }

/**
 * Gateway-backed initialize failures can happen after the gateway has already
 * successfully started and handed off the runtime. Surface the recorded ACP
 * initialization error and, when available, the last gateway runtime stderr
 * or ACP stdout line so the user sees the real agent failure instead of the
 * generic session initialization message.
 */
suspend fun buildInitializeFailureMessage(
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

fun shortInitializeFailureMessage(server: LaunchableTarget): String =
    "Failed to initialize session with ${server.name}. See logcat for details."

fun logConnectionFailure(
    server: LaunchableTarget,
    phase: String,
    message: String,
) {
    runCatching {
        Log.e(LOG_TAG, "${server.name} $phase failed\n$message")
    }
}
