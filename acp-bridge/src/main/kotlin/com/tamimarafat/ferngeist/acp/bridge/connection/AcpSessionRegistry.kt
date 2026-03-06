package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

internal class AcpSessionRegistry {
    private val sessionBridges = ConcurrentHashMap<String, SessionBridge>()
    private val sdkSessions = ConcurrentHashMap<String, ClientSession>()
    private val pendingPermissionRequests = ConcurrentHashMap<String, PendingPermissionRequest>()

    fun getBridge(sessionId: String): SessionBridge? = sessionBridges[sessionId]

    fun storeBridge(sessionId: String, bridge: SessionBridge) {
        sessionBridges[sessionId] = bridge
    }

    fun getSdkSession(sessionId: String): ClientSession? = sdkSessions[sessionId]

    fun storeSdkSession(sessionId: String, session: ClientSession) {
        sdkSessions[sessionId] = session
    }

    fun removeSdkSession(sessionId: String): ClientSession? = sdkSessions.remove(sessionId)

    fun hasBridge(sessionId: String): Boolean = sessionBridges.containsKey(sessionId)

    fun hasSdkSession(sessionId: String): Boolean = sdkSessions.containsKey(sessionId)

    fun bridgeIds(): Set<String> = sessionBridges.keys.toSet()

    fun addPendingPermissionRequest(
        toolCallId: String,
        sessionId: String,
        deferred: CompletableDeferred<RequestPermissionOutcome>,
    ) {
        pendingPermissionRequests[toolCallId] = PendingPermissionRequest(
            sessionId = sessionId,
            deferred = deferred,
        )
    }

    fun takePendingPermissionRequest(toolCallId: String): PendingPermissionRequest? {
        return pendingPermissionRequests.remove(toolCallId)
    }

    fun clearAll(closeBridges: Boolean) {
        val allSessionIds = (sessionBridges.keys + sdkSessions.keys).toSet()
        allSessionIds.forEach { sessionId ->
            clearSession(sessionId, closeBridge = closeBridges)
        }
        pendingPermissionRequests.entries.removeIf { (_, pending) ->
            pending.deferred.cancel()
            true
        }
    }

    fun clearSession(sessionId: String, closeBridge: Boolean) {
        sdkSessions.remove(sessionId)
        if (closeBridge) {
            sessionBridges.remove(sessionId)?.close()
        } else {
            sessionBridges.remove(sessionId)
        }
        pendingPermissionRequests.entries.removeIf { (_, pending) ->
            if (pending.sessionId != sessionId) {
                return@removeIf false
            }
            pending.deferred.cancel()
            true
        }
    }
}

internal data class PendingPermissionRequest(
    val sessionId: String,
    val deferred: CompletableDeferred<RequestPermissionOutcome>,
)
