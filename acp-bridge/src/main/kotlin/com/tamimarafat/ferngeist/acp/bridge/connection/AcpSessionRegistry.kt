package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientSession
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

internal class AcpSessionRegistry(
    private val scope: CoroutineScope,
    private val shouldCloseSdkSession: () -> Boolean = { true },
) {
    private val sessionBridges = ConcurrentHashMap<String, SessionBridge>()
    private val sdkSessions = ConcurrentHashMap<String, ClientSession>()

    fun getBridge(sessionId: String): SessionBridge? = sessionBridges[sessionId]

    /**
     * Returns the session as a [SessionPort].
     *
     * This is the public accessor for external consumers (e.g. [ChatSessionCoordinator]).
     * Internal callers that need bridge-specific methods ([emitEvent], [beginHydration],
     * etc.) should use [getBridge] instead.
     */
    fun getPort(sessionId: String): SessionPort? = sessionBridges[sessionId]

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

    fun clearAll(closeBridges: Boolean) {
        val allSessionIds = (sessionBridges.keys + sdkSessions.keys).toSet()
        allSessionIds.forEach { sessionId ->
            clearSession(sessionId, closeBridge = closeBridges)
        }
    }

    @OptIn(UnstableApi::class)
    fun clearSession(
        sessionId: String,
        closeBridge: Boolean,
    ) {
        val sdkSession = sdkSessions.remove(sessionId)
        if (sdkSession != null && shouldCloseSdkSession()) {
            scope.launch { runCatching { sdkSession.close() } }
        }
        if (closeBridge) {
            sessionBridges.remove(sessionId)?.close()
        } else {
            sessionBridges.remove(sessionId)
        }
    }
}
