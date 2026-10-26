package com.tamimarafat.ferngeist.acp.bridge.connection

import android.util.Log
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.store.AuthEnvValueStore
import com.tamimarafat.ferngeist.gateway.GatewayCredentialExpiredException
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.launch.GatewayRuntimeLauncher
import com.tamimarafat.ferngeist.gateway.refreshGatewaySourceIfNeeded
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LOG_TAG = "GatewayLaunchSupport"

/** The resolved launch context for a gateway-backed target: transport config plus runtime identity. */
data class GatewayLaunchContext(
    val config: AcpConnectionConfig,
    val gatewaySource: GatewaySource,
    val runtimeId: String,
    val sessionId: String,
)

/** The repositories a launch reads its target, gateway source and gateway runtime from. */
class LaunchRepositories(
    val gatewayRepository: GatewayRepository,
    val gatewaySourceRepository: GatewaySourceRepository,
    val launchableTargetRepository: LaunchableTargetRepository,
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val runtimeLauncher = GatewayRuntimeLauncher(gatewayRepository, gatewaySourceRepository, ioDispatcher)
}

/** A gateway runtime a launch left up: the source it was provisioned from and the runtime's id. */
data class GatewayRuntime(
    val source: GatewaySource,
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
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): Map<String, String> {
    val allowedNames =
        authMethods
            .flatMap { method -> method.envVars }
            .mapTo(linkedSetOf()) { envVar -> envVar.name }
    if (allowedNames.isEmpty()) {
        return emptyMap()
    }
    return withContext(ioDispatcher) {
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
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    if (method.envVars.isEmpty()) {
        return
    }
    withContext(ioDispatcher) {
        authEnvValueStore.updateValues(
            serverId = serverId,
            envVarNames = method.envVars.mapTo(linkedSetOf()) { envVar -> envVar.name },
            envValues = envValues.filterKeys { key -> method.envVars.any { envVar -> envVar.name == key } },
        )
    }
}

/**
 * Gateway agents get their runtime and session from the gateway's single connect
 * call, which also returns the runtime-scoped ACP WebSocket handoff.
 */
suspend fun buildGatewayLaunchContext(
    runtimeLauncher: GatewayRuntimeLauncher,
    server: LaunchableTarget.GatewayAgent,
    acpSessionId: String? = null,
): Result<GatewayLaunchContext> =
    runtimeLauncher
        .launch(
            gatewaySource = server.gatewaySource,
            agentId = server.binding.agentId,
            // The server list is a connect path like the chat screen: probe the
            // protocol version first so a mismatch fails here with a clear
            // message instead of opaquely on the connect call.
            requireSupportedProtocol = true,
            acpSessionId = acpSessionId,
        ).map { result ->
            val source = result.gatewaySource
            val handoff = result.handoff
            GatewayLaunchContext(
                config =
                    AcpConnectionConfig.fromGatewayHandoff(
                        gatewaySource = source,
                        handoff = handoff,
                        authMethodId = server.preferredAuthMethodId,
                        displayName = server.name,
                    ),
                gatewaySource = source,
                runtimeId = handoff.runtimeId,
                sessionId = handoff.sessionId,
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
    connectionManager: AcpConnection,
    repositories: LaunchRepositories,
    server: LaunchableTarget,
    gateway: GatewayRuntime?,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): String {
    val diagnosticMessage =
        connectionManager.diagnostics.value.recentErrors
            .lastOrNull { entry -> entry.source == "initialize" || entry.source == "connection" }
            ?.message
            ?.takeIf { it.isNotBlank() }
            ?: "Failed to initialize session with ${server.name}"

    if (gateway == null || gateway.runtimeId.isBlank() || gateway.source.gatewayCredential.isBlank()) {
        return diagnosticMessage
    }

    val runtimeHint = runtimeLogHint(gateway, repositories, ioDispatcher)

    // De-dup by the exact line this function would append. Substring containment
    // suppressed the runtime hint whenever some *shorter* stderr line happened to be a
    // substring of the diagnostic message — throwing away the line that explained the
    // failure. Duplicating a hint costs a line; dropping it costs the diagnosis.
    val hint = runtimeHint?.takeIf { it.isNotBlank() }
    if (hint == null || diagnosticMessage.endsWith("Gateway runtime: $hint", ignoreCase = true)) {
        return diagnosticMessage
    }
    return "$diagnosticMessage\nGateway runtime: $hint"
}

/** The newest stderr or ACP stdout line the gateway runtime logged, or null when none can be fetched. */
private suspend fun runtimeLogHint(
    gateway: GatewayRuntime,
    repositories: LaunchRepositories,
    ioDispatcher: CoroutineDispatcher,
): String? =
    runCatching {
        val refreshedSource =
            try {
                withContext(ioDispatcher) {
                    refreshGatewaySourceIfNeeded(
                        gateway.source,
                        repositories.gatewayRepository,
                        repositories.gatewaySourceRepository,
                    )
                }
            } catch (error: GatewayCredentialExpiredException) {
                // The credential is dead — clear it so the next launch
                // surfaces the pairing flow instead of opaque 401s.
                withContext(ioDispatcher) {
                    repositories.gatewaySourceRepository.deleteGateway(gateway.source.id)
                }
                throw error
            }
        repositories.gatewayRepository.fetchRuntimeLogs(
            scheme = refreshedSource.scheme,
            host = refreshedSource.host,
            gatewayCredential = refreshedSource.gatewayCredential,
            runtimeId = gateway.runtimeId,
        )
    }.getOrNull()
        ?.asReversed()
        ?.firstNotNullOfOrNull { entry ->
            entry.message.trim().takeIf {
                it.isNotEmpty() && (entry.stream == "stderr" || entry.stream == "acp.stdout")
            }
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
