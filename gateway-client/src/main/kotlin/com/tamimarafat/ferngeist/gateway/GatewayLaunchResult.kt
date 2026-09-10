package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The raw result of launching a gateway agent runtime: the (possibly
 * refreshed) gateway source, the started runtime, and the WebSocket handoff.
 *
 * This deliberately contains no [AcpConnectionConfig] — that type lives in
 * acp-bridge, and this module must not depend upward. Callers compose their
 * own transport config from these pieces.
 */
data class GatewayLaunchResult(
    val gatewaySource: GatewaySource,
    val runtime: GatewayRuntime,
    val handoff: GatewayConnectResponse,
)

/** Runtime status the gateway reports for a process it is currently holding. */
private const val RUNTIME_STATUS_RUNNING = "running"

/**
 * Starts (or reuses) the gateway runtime for [agentId] and requests the
 * runtime-scoped ACP WebSocket handoff — the two-stage launch every
 * gateway-backed agent needs.
 *
 * Pass `fresh = true` to start an isolated agent process instead of reusing
 * an existing one for [agentId].
 *
 * Pass [reuseRuntimeId] to skip runtime selection entirely and attach to a
 * specific runtime the caller has already identified — the case for a chat
 * returning to the runtime holding its own live gateway session. Without it,
 * the gateway picks the runtime by its own reuse heuristic, which hands back
 * whichever session the chosen runtime already holds.
 *
 * Refreshes the stored credential when it is due. Returns [Result.failure]
 * with a user-facing message when the gateway is unpaired or the credential
 * expired; rethrows [CancellationException] but surfaces other launch errors
 * (network, protocol, gateway-side) as failures.
 */
suspend fun launchGatewayRuntime(
    gatewayRepository: GatewayRepository,
    gatewaySourceRepository: GatewaySourceRepository,
    gatewaySource: GatewaySource,
    agentId: String,
    requireSupportedProtocol: Boolean = false,
    fresh: Boolean = false,
    reuseRuntimeId: String? = null,
): Result<GatewayLaunchResult> {
    val refreshedSource =
        try {
            withContext(Dispatchers.IO) {
                refreshGatewaySourceIfNeeded(gatewaySource, gatewayRepository, gatewaySourceRepository)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: GatewayCredentialExpiredException) {
            withContext(Dispatchers.IO) {
                gatewaySourceRepository.deleteGateway(gatewaySource.id)
            }
            return Result.failure(
                IllegalStateException("Gateway credential expired. Please pair this gateway again."),
            )
        }
    if (refreshedSource.gatewayCredential.isBlank()) {
        return Result.failure(IllegalStateException("Gateway is not paired"))
    }

    if (requireSupportedProtocol) {
        gatewayRepository
            .fetchStatus(refreshedSource.scheme, refreshedSource.host)
            .requireSupportedProtocol()
    }

    return runCatching {
        val runtime =
            if (reuseRuntimeId != null) {
                GatewayRuntime(id = reuseRuntimeId, status = RUNTIME_STATUS_RUNNING, agentId = agentId)
            } else {
                withContext(Dispatchers.IO) {
                    gatewayRepository.startAgent(
                        scheme = refreshedSource.scheme,
                        host = refreshedSource.host,
                        gatewayCredential = refreshedSource.gatewayCredential,
                        agentId = agentId,
                        new = fresh,
                    )
                }
            }
        val handoff =
            withContext(Dispatchers.IO) {
                gatewayRepository.connectRuntime(
                    scheme = refreshedSource.scheme,
                    host = refreshedSource.host,
                    gatewayCredential = refreshedSource.gatewayCredential,
                    runtimeId = runtime.id,
                    sessionMode = "resilient",
                )
            }
        GatewayLaunchResult(
            gatewaySource = refreshedSource,
            runtime = runtime,
            handoff = handoff,
        )
    }
}
