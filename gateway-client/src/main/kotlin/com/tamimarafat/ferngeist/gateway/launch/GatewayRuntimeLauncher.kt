package com.tamimarafat.ferngeist.gateway.launch

import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.gateway.GatewayConnectResponse
import com.tamimarafat.ferngeist.gateway.GatewayCredentialExpiredException
import com.tamimarafat.ferngeist.gateway.GatewayLaunchResult
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.GatewaySessionSummary
import com.tamimarafat.ferngeist.gateway.refreshGatewaySourceIfNeeded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owns the gateway launch boundary; callers supply its repositories and IO dispatcher. */
class GatewayRuntimeLauncher(
    private val gatewayRepository: GatewayRepository,
    private val gatewaySourceRepository: GatewaySourceRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Connects [agentId] on the gateway (protocol v2) and returns the runtime/session
     * handoff. One call does both: the gateway owns the agent process lifetime, so there
     * is no start-then-connect sequence, no runtime selection, and no client-side retry —
     * a launch either gets a runtime with an attached session or fails.
     *
     * Refreshes the stored credential when it is due. Returns [Result.failure]
     * with a user-facing message when the gateway is unpaired or the credential
     * expired; rethrows [CancellationException] but surfaces other launch errors
     * (network, protocol, gateway-side) as failures.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun launch(
        gatewaySource: GatewaySource,
        agentId: String,
        requireSupportedProtocol: Boolean = false,
        acpSessionId: String? = null,
    ): Result<GatewayLaunchResult> =
        try {
            launchRefreshedRuntime(
                gatewaySource = gatewaySource,
                agentId = agentId,
                requireSupportedProtocol = requireSupportedProtocol,
                acpSessionId = acpSessionId,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Broad catch: every step here is a network boundary (credential refresh,
            // protocol probe, agent connect). Callers read the Result on the main
            // dispatcher and render it, so an exception escaping this function does
            // not surface as an error — it kills the chat screen's load coroutine.
            Result.failure(error)
        }

    /**
     * The launch body, free to throw: [launch] folds anything other
     * than cancellation into the [Result] its callers are written against.
     */
    private suspend fun launchRefreshedRuntime(
        gatewaySource: GatewaySource,
        agentId: String,
        requireSupportedProtocol: Boolean,
        acpSessionId: String?,
    ): Result<GatewayLaunchResult> {
        val refreshedSource =
            try {
                withContext(ioDispatcher) {
                    refreshGatewaySourceIfNeeded(gatewaySource, gatewayRepository, gatewaySourceRepository)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: GatewayCredentialExpiredException) {
                withContext(ioDispatcher) {
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
            gatewayRepository.verifyProtocol(refreshedSource.scheme, refreshedSource.host)
        }

        return attemptConnect(refreshedSource, agentId, acpSessionId)
    }

    /**
     * Runs the connect call for an already-refreshed [gatewaySource] and folds the
     * outcome into a [Result].
     *
     * [CancellationException] is rethrown rather than folded into the failure, so a
     * cancelled caller sees cancellation instead of a launch error it would render on a
     * screen that is already gone. Every other exception is the launch failure the
     * function's contract promises to surface — including the gateway's own error text
     * when the agent fails to start (HTTP 502), which reaches the caller verbatim.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun attemptConnect(
        gatewaySource: GatewaySource,
        agentId: String,
        acpSessionId: String?,
    ): Result<GatewayLaunchResult> =
        try {
            val handoff =
                withContext(ioDispatcher) {
                    resumeExistingSession(gatewaySource, agentId, acpSessionId)
                        ?: gatewayRepository.connectAgent(
                            scheme = gatewaySource.scheme,
                            host = gatewaySource.host,
                            gatewayCredential = gatewaySource.gatewayCredential,
                            agentId = agentId,
                        )
                }
            Result.success(GatewayLaunchResult(gatewaySource = gatewaySource, handoff = handoff))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }

    /**
     * Reattaches to a disconnected gateway session for [agentId] (see [reattachTarget]) instead
     * of spawning another process: connect always starts fresh, and the gateway caps sessions
     * per device (409), so sessions that survived an app reinstall must be reused.
     *
     * Null when there is none, or when listing/resuming fails, so the caller falls back to
     * connect.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun resumeExistingSession(
        gatewaySource: GatewaySource,
        agentId: String,
        acpSessionId: String?,
    ): GatewayConnectResponse? =
        try {
            val existing =
                reattachTarget(
                    gatewayRepository.listGatewaySessions(
                        gatewaySource.scheme,
                        gatewaySource.host,
                        gatewaySource.gatewayCredential,
                    ),
                    agentId,
                    acpSessionId,
                )
            existing?.let {
                val resumed =
                    gatewayRepository.resumeSession(
                        gatewaySource.scheme,
                        gatewaySource.host,
                        gatewaySource.gatewayCredential,
                        it.sessionId,
                    )
                val socketScheme = if (gatewaySource.scheme.lowercase() in setOf("https", "wss")) "wss" else "ws"
                GatewayConnectResponse(
                    runtimeId = it.runtimeId,
                    sessionId = it.sessionId,
                    attachToken = resumed.attachToken,
                    webSocketUrl = "$socketScheme://${gatewaySource.host}/v1/acp/${it.runtimeId}",
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
}

/**
 * The gateway session a launch of [agentId] reattaches to: the one already running
 * [acpSessionId] when the gateway lists it, else the newest. Only disconnected ones, since
 * attaching to an active session would steal it from its live client.
 */
internal fun reattachTarget(
    sessions: List<GatewaySessionSummary>,
    agentId: String,
    acpSessionId: String?,
): GatewaySessionSummary? {
    val disconnected = sessions.filter { it.agentId == agentId && it.status == "disconnected" }
    return disconnected.firstOrNull { acpSessionId != null && acpSessionId in it.acpSessionIds }
        ?: disconnected.maxByOrNull { it.createdAt.orEmpty() }
}
