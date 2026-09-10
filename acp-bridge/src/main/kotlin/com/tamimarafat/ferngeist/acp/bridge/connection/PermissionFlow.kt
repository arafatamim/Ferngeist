package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.model.RequestPermissionOutcome
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

internal class PermissionFlow {
    private val pendingPermissionRequests = ConcurrentHashMap<String, PendingPermissionRequest>()

    fun addPending(
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

    fun takePending(toolCallId: String): PendingPermissionRequest? = pendingPermissionRequests.remove(toolCallId)

    // Explicit iterator with remove() to avoid ConcurrentModificationException
    // from mutating the ConcurrentHashMap during iteration.
    fun cancelAll() {
        val iterator = pendingPermissionRequests.entries.iterator()
        while (iterator.hasNext()) {
            iterator
                .next()
                .value.deferred
                .cancel()
            iterator.remove()
        }
    }

    // Single-pass iteration: avoids snapshot + second pass by filtering and
    // removing matching entries in one loop using explicit iterator.remove().
    fun cancelForSession(sessionId: String) {
        val iterator = pendingPermissionRequests.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.sessionId != sessionId) continue
            entry.value.deferred.cancel()
            iterator.remove()
        }
    }

    /**
     * Completes every pending permission for [sessionId] with
     * [RequestPermissionOutcome.Cancelled] and removes it, returning the affected
     * tool call ids. Used when a turn is cancelled: `requestPermissions` suspends on
     * the deferred, so leaving it pending would block the agent's tool call forever.
     */
    fun cancelPendingForSession(sessionId: String): List<String> {
        val cancelled = mutableListOf<String>()
        val iterator = pendingPermissionRequests.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.sessionId != sessionId) continue
            entry.value.deferred.complete(RequestPermissionOutcome.Cancelled)
            cancelled += entry.key
            iterator.remove()
        }
        return cancelled
    }
}

internal data class PendingPermissionRequest(
    val sessionId: String,
    val deferred: CompletableDeferred<RequestPermissionOutcome>,
)
