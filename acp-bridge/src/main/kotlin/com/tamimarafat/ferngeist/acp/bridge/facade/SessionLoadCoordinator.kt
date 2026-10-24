package com.tamimarafat.ferngeist.acp.bridge.facade

import com.agentclientprotocol.model.AgentCapabilities
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticationRequiredException
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnection
import com.tamimarafat.ferngeist.acp.bridge.connection.SessionAttachRpc
import com.tamimarafat.ferngeist.acp.bridge.connection.formatAcpErrorMessage
import com.tamimarafat.ferngeist.acp.bridge.connection.sessionAttachRpc
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Attaches one session over the connection: an already-registered bridge is reused,
 * else the attach RPC the agent advertises (`session/load` replays history,
 * `session/resume` only reattaches) runs under [sessionLoadTimeoutMs].
 *
 * No recovery lives here: reconnect belongs to the connection layer
 * (`resume` -> attach -> `session/load`) and session recovery to the gateway, so a
 * failure is reported as-is instead of being retried or answered with a fresh session.
 */
internal class SessionLoadCoordinator(
    private val connectionManager: AcpConnection,
    private val opening: ChatOpening,
    private val sessionLoadTimeoutMs: Long,
    private val currentCapabilitiesProvider: () -> AgentCapabilities?,
    private val onAttachBridge: suspend (SessionPort) -> Unit,
    private val onSessionReady: suspend () -> Unit,
) {
    /**
     * [sessionId] defaults to the chat's own session; a fresh connect passes the id
     * the gateway just minted.
     *
     * [announceReady] controls whether [onSessionReady] fires on the fast path so the
     * UI keeps the already-painted cached snapshot instead of flashing loading states.
     * Live bridge snapshots still flow through observers regardless.
     */
    suspend fun run(
        sessionId: String = opening.sessionId,
        announceReady: Boolean = true,
    ): SessionLoadOutcome {
        connectionManager.sessions.getSession(sessionId)?.let { existing ->
            onAttachBridge(existing)
            if (announceReady) onSessionReady()
            return SessionLoadOutcome.Attached
        }

        // `session/load` replays history, `session/resume` only reattaches. Agents
        // that advertise neither have no way back into an existing session.
        val rpc =
            currentCapabilitiesProvider().sessionAttachRpc()
                ?: return SessionLoadOutcome.Failed("This agent does not advertise session/load support.")

        return loadWithTimeout(sessionId, rpc)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadWithTimeout(
        sessionId: String,
        rpc: SessionAttachRpc,
    ): SessionLoadOutcome {
        val bridge =
            try {
                withTimeout(sessionLoadTimeoutMs) {
                    connectionManager.sessions.attachSession(rpc, sessionId, opening.cwd)
                }
            } catch (_: AcpAuthenticationRequiredException) {
                return SessionLoadOutcome.Failed(
                    "ACP authentication is required for this server. " +
                        "Return to the session list and authenticate first.",
                )
            } catch (_: TimeoutCancellationException) {
                return SessionLoadOutcome.Failed("Session load timed out. Check server connection and retry.")
            } catch (error: CancellationException) {
                // Screen closed mid-load: never degrade cancellation into a
                // load failure (or into the fresh-session fallback).
                throw error
            } catch (error: Exception) {
                // Broad catch: session/load can fail on transport, auth, or SDK
                // protocol errors; each is surfaced as a load failure instead of
                // crashing the loading coroutine.
                return SessionLoadOutcome.Failed(formatAcpErrorMessage(error, rpc.failureLabel))
            }
        return if (bridge != null) {
            onAttachBridge(bridge)
            SessionLoadOutcome.Attached
        } else {
            SessionLoadOutcome.Failed("Could not load this session. Check connection and retry.")
        }
    }
}

/**
 * Wall-clock of each step of one chat open (or server-list launch, or session listing), logged as a single line under the
 * `FerngeistLoad` tag and to the diagnostics RPC log, next to the per-attach timing. `launch` is the
 * protocol probe and the gateway connect; `load` is session/load.
 */
internal class OpenStepTimer {
    private val startNanos = System.nanoTime()
    private var markNanos = startNanos
    private val steps = StringBuilder()

    fun mark(step: String) {
        val now = System.nanoTime()
        steps.append("$step=${(now - markNanos) / NANOS_PER_MS}ms ")
        markNanos = now
    }

    /** Logs the [label]led flow (a chat open by default) and returns its summary for the diagnostics log. */
    fun report(
        sessionId: String,
        label: String = "open",
    ): String {
        val total = (System.nanoTime() - startNanos) / NANOS_PER_MS
        val summary = "${steps}total=${total}ms"
        runCatching { android.util.Log.i("FerngeistLoad", "$label sid=$sessionId $summary") }
        return summary
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}

/** What one session attach produced: a live bridge, or the message to show. */
internal sealed interface SessionLoadOutcome {
    data object Attached : SessionLoadOutcome

    data class Failed(
        val message: String,
    ) : SessionLoadOutcome
}
