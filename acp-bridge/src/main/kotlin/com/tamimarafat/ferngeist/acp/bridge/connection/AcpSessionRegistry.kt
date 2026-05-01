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

    fun storeBridge(
        sessionId: String,
        bridge: SessionBridge,
    ) {
        sessionBridges[sessionId] = bridge
    }

    fun getSdkSession(sessionId: String): ClientSession? = sdkSessions[sessionId]

    fun storeSdkSession(
        sessionId: String,
        session: ClientSession,
    ) {
        sdkSessions[sessionId] = session
    }

    fun hasSdkSession(sessionId: String): Boolean = sdkSessions.containsKey(sessionId)

    fun bridgeIds(): Set<String> = sessionBridges.keys.toSet()

    fun addPendingPermissionRequest(
        toolCallId: String,
        sessionId: String,
        deferred: CompletableDeferred<RequestPermissionOutcome>,
    ) {
        pendingPermissionRequests[toolCallId] =
            PendingPermissionRequest(
                sessionId = sessionId,
                deferred = deferred,
            )
    }

    fun takePendingPermissionRequest(toolCallId: String): PendingPermissionRequest? =
        pendingPermissionRequests.remove(toolCallId)

    fun clearAll(closeBridges: Boolean) {
        val allSessionIds = (sessionBridges.keys + sdkSessions.keys).toSet()
        allSessionIds.forEach { sessionId ->
            clearSession(sessionId, closeBridge = closeBridges)
        }
        val pendingIterator = pendingPermissionRequests.entries.iterator()
        while (pendingIterator.hasNext()) {
            val pending = pendingIterator.next().value
            pending.deferred.cancel()
            pendingIterator.remove()
        }
    }

    fun clearSession(
        sessionId: String,
        closeBridge: Boolean,
    ) {
        sdkSessions.remove(sessionId)
        if (closeBridge) {
            sessionBridges.remove(sessionId)?.close()
        } else {
            sessionBridges.remove(sessionId)
        }
        val pendingIterator = pendingPermissionRequests.entries.iterator()
        while (pendingIterator.hasNext()) {
            val entry = pendingIterator.next()
            if (entry.value.sessionId != sessionId) continue
            entry.value.deferred.cancel()
            pendingIterator.remove()
        }
    }
}

internal data class PendingPermissionRequest(
    val sessionId: String,
    val deferred: CompletableDeferred<RequestPermissionOutcome>,
)
