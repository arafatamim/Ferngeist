package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.ChatAgentCapabilities
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Thin adapter that wraps [ChatSessionFacade] and exposes a callback-based
 * API for [ChatViewModel].
 *
 * All ACP-specific logic lives in the facade implementation
 * ([com.tamimarafat.ferngeist.acp.bridge.facade.AcpChatSessionFacade]).
 * This coordinator has zero ACP imports.
 */
internal class ChatSessionCoordinator(
    scope: CoroutineScope,
    private val facade: ChatSessionFacade,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        /** Emitted before any snapshot is delivered. */
        suspend fun onLoadStarted()

        /** Called for every session snapshot update. */
        suspend fun onSnapshot(snapshot: ChatSessionSnapshot)

        /** Emitted when the session has finished loading and is ready for use. */
        suspend fun onSessionReady()

        /** Persists newly created session metadata. */
        suspend fun onSessionStored(
            sessionId: String,
            cwd: String,
            updatedAt: Long,
        )

        /** Surfaces a fatal load error to the UI. */
        suspend fun onLoadFailed(message: String)

        /** Surfaces a non-fatal operational error to the UI. */
        suspend fun onOperationError(
            message: String,
            stopStreaming: Boolean,
        )

        /** Emitted when a streaming cancel completes. */
        suspend fun onStreamingCancelled()

        /** Emitted when the server does not support cancellation. */
        suspend fun onCancelUnsupported()

        /** Emitted when a model change is confirmed by the server. */
        suspend fun onModelUpdated()

        /** Updates UI capability flags (images, embedded context, etc.). */
        suspend fun onCapabilitiesChanged(capabilities: ChatAgentCapabilities)
    }

    init {
        // Connection state -> facade recovery decisions (StateFlow)
        scope.launch {
            facade.connectionState.collect { state ->
                facade.onConnectionStateChanged(state)
            }
        }

        // Session snapshot + capabilities (StateFlow) - combined in single coroutine
        scope.launch {
            combine(facade.sessionSnapshot, facade.agentCapabilities) { snapshot, caps ->
                Pair(snapshot, caps)
            }.collect { (snapshot, caps) ->
                snapshot?.let { callbacks.onSnapshot(it) }
                callbacks.onCapabilitiesChanged(caps)
            }
        }

        // One-shot event flows (SharedFlow) - separate launches but grouped logically
        scope.launch { facade.sessionReady.collect { callbacks.onSessionReady() } }
        scope.launch { facade.loadFailed.collect { callbacks.onLoadFailed(it) } }
        scope.launch { facade.operationError.collect { callbacks.onOperationError(it.message, it.stopStreaming) } }
        scope.launch { facade.streamingCancelled.collect { callbacks.onStreamingCancelled() } }
        scope.launch { facade.cancelUnsupported.collect { callbacks.onCancelUnsupported() } }
        scope.launch { facade.modelUpdated.collect { callbacks.onModelUpdated() } }
    }

    /** Starts session load, then delegates to the facade. */
    suspend fun loadSession() {
        callbacks.onLoadStarted()
        facade.loadSession()
    }

    /**
     * Cache-first attach: paints the last rendered snapshot instantly (no
     * reconnect, no transcript wipe), then revalidates in the background.
     * Warm transport hit still wins — it reattaches live observers.
     */
    suspend fun attachCachedThenLoad() {
        val cached = facade.cachedSnapshot.value
        if (cached != null) {
            callbacks.onSnapshot(cached)
            callbacks.onSessionReady()
        } else {
            callbacks.onLoadStarted()
        }
        if (!facade.tryRestoreWarmSession()) {
            if (cached == null) facade.loadSession() else facade.loadSessionQuietly()
        }
    }

    /** Sends a chat message via the facade. Returns true when dispatched; false when no bridge. */
    suspend fun sendMessage(
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
    ): Boolean = facade.sendMessage(text, images, files)

    /** Ensures the transport is (re)connecting so queued prompts can drain. */
    suspend fun reconnect() {
        facade.reconnect()
    }

    /** Requests a streaming cancellation. */
    suspend fun cancelStreaming() {
        facade.cancelStreaming()
    }

    /** Updates a session configuration option. */
    suspend fun setConfigOption(
        optionId: String,
        value: ChatConfigValue,
    ) {
        facade.setConfigOption(optionId, value)
    }

    /** Grants a permission prompt for a tool call. */
    suspend fun grantPermission(
        toolCallId: String,
        optionId: String,
    ) {
        facade.grantPermission(toolCallId, optionId)
    }

    /** Denies a permission prompt for a tool call. */
    suspend fun denyPermission(toolCallId: String) {
        facade.denyPermission(toolCallId)
    }

    /** Clears any facade resources when the view model is torn down. */
    fun clear() {
        facade.clear()
    }
}
