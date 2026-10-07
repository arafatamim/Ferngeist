@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.model.CreateElicitationResponse
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

internal class ElicitationFlow {
    private val pending = ConcurrentHashMap<String, PendingElicitation>()

    fun addPending(
        key: String,
        sessionId: String,
        deferred: CompletableDeferred<CreateElicitationResponse>,
    ) {
        pending[key] =
            PendingElicitation(
                sessionId = sessionId,
                deferred = deferred,
            )
    }

    fun takePending(key: String): PendingElicitation? = pending.remove(key)

    fun peekSessionId(key: String): String? = pending[key]?.sessionId

    // Explicit iterator with remove() to avoid ConcurrentModificationException
    // from mutating the ConcurrentHashMap during iteration.
    fun cancelAll(): List<String> {
        val cancelled = mutableListOf<String>()
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.deferred.cancel()
            cancelled += entry.key
            iterator.remove()
        }
        return cancelled
    }

    // Single-pass iteration: avoids snapshot + second pass by filtering and
    // removing matching entries in one loop using explicit iterator.remove().
    fun cancelForSession(sessionId: String) {
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.sessionId != sessionId) continue
            entry.value.deferred.cancel()
            iterator.remove()
        }
    }

    /**
     * Completes every pending elicitation for [sessionId] with a cancel action and
     * removes it, returning the affected keys. Used when a turn is cancelled:
     * `createElicitation` suspends on its deferred, so leaving it pending would
     * block the agent's request forever.
     */
    fun cancelPendingForSession(sessionId: String): List<String> {
        val cancelled = mutableListOf<String>()
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.sessionId != sessionId) continue
            entry.value.deferred.complete(ElicitationMappers.cancelResponse())
            cancelled += entry.key
            iterator.remove()
        }
        return cancelled
    }
}

internal data class PendingElicitation(
    val sessionId: String,
    val deferred: CompletableDeferred<CreateElicitationResponse>,
)
