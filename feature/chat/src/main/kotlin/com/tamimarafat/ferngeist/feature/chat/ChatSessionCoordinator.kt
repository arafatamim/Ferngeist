package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAgentCapabilities
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticationRequiredException
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpInitializeResult
import com.tamimarafat.ferngeist.acp.bridge.connection.formatAcpErrorMessage
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigCategory
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigValue
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPort
import com.tamimarafat.ferngeist.acp.bridge.session.SessionSnapshot
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.refreshGatewaySourceIfNeeded
import com.tamimarafat.ferngeist.gateway.resolveGatewayWebSocketUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Coordinates a single chat session's lifecycle over an ACP connection.
 *
 * ChatSessionCoordinator owns the bridge between the chat UI layer
 * (ChatViewModel) and the transport/session layer ([AcpConnectionManager]).
 * It handles:
 *
 * - **Session attachment**: loading an existing session or creating a fresh
 *   one, with timeout and graceful fallback to a live session when
 *   session/load is unavailable or the bridge is destroyed.
 * - **Bridge observation**: collecting [SessionSnapshot] values from the
 *   [SessionPort] and routing them to the UI via [Callbacks.onSnapshot],
 *   plus observing [AppSessionEvent.ModelSelectionConfirmed] for post-set
 *   model update confirmation.
 * - **Bridge recovery**: when the transport reconnects, automatically
 *   re-attaching to the same session or creating a replacement.
 * - **User actions**: sending messages, cancelling streaming, configuring
 *   session options, and resolving tool permissions.
 *
 * ## Thread safety
 * Session-load and message-send operations are guarded by
 * [bridgeOperationMutex] to prevent races between reconnection recovery and
 * user-initiated actions.
 *
 * ## Error surface
 * Connection failures, auth requirements, and ACP RPC errors are surfaced as
 * user-facing strings via [Callbacks.onOperationError] and
 * [Callbacks.onLoadFailed]. Bridge-level stream errors during load trigger a
 * fallback to [AcpConnectionManager.createSession].
 */
internal class ChatSessionCoordinator(
    private val scope: CoroutineScope,
    private val connectionManager: AcpConnectionManager,
    private val launchableTargetRepository: LaunchableTargetRepository,
    private val gatewaySourceRepository: GatewaySourceRepository,
    private val gatewayRepository: GatewayRepository,
    private val serverId: String,
    private val initialSessionId: String,
    private val cwd: String,
    private val sessionLoadTimeoutMs: Long = 20_000L,
    private val bridgeRecoveryRetryDelayMs: Long = 3_000L,
    private val trace: (String) -> Unit,
    private val logError: (String, Throwable?) -> Unit,
    private val callbacks: Callbacks,
) {
    /**
     * Callbacks invoked by the coordinator to communicate session lifecycle
     * events back to [ChatViewModel].
     */
    interface Callbacks {
        /** The coordinator has started an asynchronous session load. */
        suspend fun onLoadStarted()

        /** A new [SessionSnapshot] is available from the observed bridge. */
        suspend fun onSnapshot(snapshot: SessionSnapshot)

        /** The session bridge is ready for accept user input. */
        suspend fun onSessionReady()

        /**
         * The session was persisted locally (only when the server does not
         * advertise session/list support — the app tracks it instead).
         */
        suspend fun onSessionStored(
            sessionId: String,
            cwd: String,
            updatedAt: Long,
        )

        /** The session load attempt failed with a user-facing message. */
        suspend fun onLoadFailed(message: String)

        /**
         * A user-facing operation error message.
         * @param stopStreaming whether the error should also cancel streaming
         */
        suspend fun onOperationError(
            message: String,
            stopStreaming: Boolean,
        )

        /** The active streaming turn was successfully cancelled. */
        suspend fun onStreamingCancelled()

        /** The server does not support session/cancel. */
        suspend fun onCancelUnsupported()

        /**
         * The session's model selection was confirmed (after a
         * [setConfigOption] call on a model-category option).
         */
        suspend fun onModelUpdated()

        /** Agent capabilities changed (on connect/initialize). */
        suspend fun onCapabilitiesChanged(capabilities: AcpAgentCapabilities)
    }

    /** The currently resolved session ID. Updated on bridge attach. */
    private var activeSessionId: String = initialSessionId

    /**
     * The [SessionPort] attached to the current chat. Null during loading,
     * after disconnection, or before first attach.
     */
    private var sessionBridge: SessionPort? = null

    /** Active coroutine jobs collecting from the current bridge's flows. */
    private var bridgeObserverJobs: List<Job> = emptyList()

    /**
     * A retry coroutine that periodically attempts to reattach the bridge
     * after reconnection. Non-null while recovery is in progress.
     */
    private var bridgeRecoveryJob: Job? = null

    /**
     * Tracks a model selection that was initiated locally via [setConfigOption]
     * but not yet confirmed by the server. Cleared when
     * [AppSessionEvent.ModelSelectionConfirmed] fires with a matching ID.
     *
     * Legacy model changes are confirmed locally because the current SDK
     * surface does not expose a dedicated CurrentModelUpdate event on the
     * client side.
     */
    private var pendingModelSelectionId: String? = null

    /** Cached [AcpAgentCapabilities] from the last successful initialization. */
    private var currentCapabilities: AcpAgentCapabilities? = null

    /**
     * Whether the coordinator should attempt bridge recovery after
     * reconnection. Set to true on load/attach; set to false when an
     * unrecoverable condition is detected (e.g. auth required or agent
     * doesn't advertise session/load).
     */
    private var shouldRecoverBridge: Boolean = false

    /** Guards session-load and message-send operations from reentrancy races. */
    private val bridgeOperationMutex = Mutex()

    /**
     * Loads the session identified by [initialSessionId].
     *
     * **Loading strategy:**
     * 1. If the session is already loaded locally, reattaches immediately.
     * 2. If the agent does not advertise session/load, shows a clear error
     *    instead of timing out.
     * 3. Tries [AcpConnectionManager.loadSession] with a timeout.
     * 4. If load times out, shows a timeout-specific error.
     * 5. If the load fails with a "stream destroyed" error, falls back to
     *    [AcpConnectionManager.createSession] for a fresh live session.
     *
     * This is guarded by [bridgeOperationMutex] to prevent races with
     * reconnection recovery.
     */
    suspend fun loadSession() {
        bridgeOperationMutex.withLock {
            shouldRecoverBridge = true
            cancelBridgeRecovery()
            trace("loadSession:start sessionId=$initialSessionId cwd=$cwd")
            callbacks.onLoadStarted()

            if (!ensureConnectedAndInitialized()) {
                callbacks.onLoadFailed("Disconnected. Reconnect to refresh this session.")
                return
            }

            publishCapabilities()

            connectionManager.getSession(initialSessionId)?.let { existing ->
                trace("loadSession:reusingExisting sessionId=$initialSessionId")
                attachSessionBridge(existing)
                callbacks.onSessionReady()
                return
            }

            val capabilities =
                currentCapabilities ?: connectionManager.agentCapabilities.value
            if (capabilities != null && !capabilities.loadSession) {
                shouldRecoverBridge = false
                callbacks.onLoadFailed(
                    "This agent does not advertise session/load support.",
                )
                return
            }

            var loadTimedOut = false
            val bridge =
                try {
                    withTimeout(sessionLoadTimeoutMs) {
                        connectionManager.loadSession(
                            sessionId = initialSessionId,
                            cwd = cwd,
                        )
                    }
                } catch (error: AcpAuthenticationRequiredException) {
                    callbacks.onLoadFailed(
                        "ACP authentication is required for this server. " +
                            "Return to the session list and authenticate first.",
                    )
                    return
                } catch (_: TimeoutCancellationException) {
                    loadTimedOut = true
                    null
                } catch (error: Exception) {
                    // If the bridge's event stream was destroyed during load
                    // (e.g. the server closed the connection), fall back to a
                    // fresh session instead of showing an opaque error.
                    if (isDestroyedBridgeStreamError(error)) {
                        val fallbackBridge =
                            runCatching {
                                connectionManager.createSession(cwd)
                            }.getOrNull()
                        if (fallbackBridge != null) {
                            trace(
                                "loadSession:bridgeRestartFallback " +
                                    "active=${fallbackBridge.sessionId}",
                            )
                            attachSessionBridge(fallbackBridge)
                            callbacks.onSessionReady()
                            callbacks.onOperationError(
                                message = "The ACP bridge process restarted while " +
                                    "loading this session. Opened a new live session.",
                                stopStreaming = false,
                            )
                            return
                        }
                    }
                    // Preserve the original session/load failure so
                    // provider-specific JSON-RPC errors are shown to the user
                    // instead of a generic "Could not load this session" message.
                    callbacks.onLoadFailed(
                        formatAcpErrorMessage(error, "Failed to load session"),
                    )
                    return
                }

            if (bridge != null) {
                trace(
                    "loadSession:bridgeReady " +
                        "requested=$initialSessionId active=${bridge.sessionId}",
                )
                attachSessionBridge(bridge)
                return
            }

            val errorMessage =
                if (loadTimedOut) {
                    "Session load timed out. Check server connection and retry."
                } else {
                    "Could not load this session. Check connection and retry."
                }
            trace(
                "loadSession:failed timedOut=$loadTimedOut sessionId=$initialSessionId",
            )
            logError(
                "loadSession failed: bridge is null for sessionId=$initialSessionId",
                null,
            )
            callbacks.onLoadFailed(errorMessage)
        }
    }

    /**
     * Called by the chat layer when the ACP connection state changes.
     *
     * - **Connected**: triggers [scheduleBridgeRecovery] to reattach the bridge.
     * - **Disconnected / Failed**: invalidates the current bridge so that
     *   send operations fail-fast until recovery succeeds.
     */
    fun onConnectionStateChanged(connectionState: AcpConnectionState) {
        when (connectionState) {
            is AcpConnectionState.Connected -> scheduleBridgeRecovery()
            is AcpConnectionState.Connecting,
            is AcpConnectionState.Disconnected,
            is AcpConnectionState.Failed,
            -> invalidateActiveBridge()
        }
    }

    /**
     * Sends a user message (text + images) to the active session.
     *
     * If the bridge is disconnected, tries to reattach via
     * [ensureSessionReadyForSend]. Images are rejected if the agent's
     * capabilities do not advertise image prompt support.
     */
    suspend fun sendMessage(
        text: String,
        images: List<ChatImageData>,
    ) {
        if (text.isBlank() && images.isEmpty()) return

        val bridge = ensureSessionReadyForSend()
        if (bridge == null) {
            trace("sendMessage: bridge missing activeSessionId=$activeSessionId")
            callbacks.onOperationError(
                message = SESSION_NOT_READY_MESSAGE,
                stopStreaming = false,
            )
            return
        }

        val capabilities = connectionManager.agentCapabilities.value
        if (images.isNotEmpty() && capabilities != null && !capabilities.prompt.image) {
            callbacks.onOperationError(
                message = "This agent does not advertise image prompt support.",
                stopStreaming = false,
            )
            return
        }

        val imagePairs = images.map { Pair(it.base64, it.mimeType) }
        try {
            trace(
                "sendMessage: session=${bridge.sessionId} " +
                    "textLen=${text.length} images=${imagePairs.size}",
            )
            bridge.sendPrompt(text, imagePairs)
        } catch (error: Exception) {
            val errorMessage = userFacingSendError(error)
            logError("sendMessage failed for sessionId=$activeSessionId", error)
            trace(
                "sendMessage:error session=$activeSessionId message=${error.message}",
            )
            callbacks.onOperationError(
                message = errorMessage,
                stopStreaming = true,
            )
        }
    }

    /**
     * Cancels the current streaming turn via the bridge.
     *
     * If the server returns "Method not found: session/cancel", the UI is
     * notified via [Callbacks.onCancelUnsupported] so it can hide the cancel
     * button. Other errors surface as operation errors without stopping
     * streaming.
     */
    suspend fun cancelStreaming() {
        trace("cancelStreaming requested session=$activeSessionId")
        val bridge = requireActiveBridge("cancelStreaming") ?: return
        val result = runCatching { bridge.cancel() }
        val error = result.exceptionOrNull()
        if (error != null) {
            if (isSessionCancelUnsupported(error)) {
                callbacks.onCancelUnsupported()
                return
            }
            callbacks.onOperationError(
                message = "Failed to cancel the current turn",
                stopStreaming = false,
            )
            return
        }
        callbacks.onStreamingCancelled()
    }

    /**
     * Sets a session configuration option.
     *
     * If the option is in the [SessionConfigCategory.Model] category (i.e. a
     * model selection), the coordinator records the pending model ID so it can
     * confirm the change when [AppSessionEvent.ModelSelectionConfirmed] fires
     * back.
     */
    suspend fun setConfigOption(
        optionId: String,
        value: SessionConfigValue,
    ) {
        val bridge = requireActiveBridge("setConfigOption:$optionId") ?: return
        val option =
            bridge.snapshot.value.configOptions
                .firstOrNull { it.id == optionId }
        val selectedModelId =
            if (option?.category is SessionConfigCategory.Model) {
                (value as? SessionConfigValue.StringValue)?.value
            } else {
                null
            }
        if (!selectedModelId.isNullOrBlank()) {
            pendingModelSelectionId = selectedModelId
        }
        bridge.setConfigOption(optionId, value)
    }

    /** Resolves a pending tool permission request by selecting an option. */
    suspend fun grantPermission(
        toolCallId: String,
        optionId: String,
    ) {
        requireActiveBridge("grantPermission:$toolCallId")
            ?.grantPermission(toolCallId, optionId)
    }

    /** Cancels a pending tool permission request. */
    suspend fun denyPermission(toolCallId: String) {
        requireActiveBridge("denyPermission:$toolCallId")
            ?.denyPermission(toolCallId)
    }

    /**
     * Tears down the coordinator: cancels bridge recovery, invalidates the
     * bridge, and cancels collection jobs. Called on ViewModel onCleared.
     */
    fun clear() {
        cancelBridgeRecovery()
        invalidateActiveBridge()
        clearBridgeObservers()
    }

    // ---- Internal: connection and session management ----

    /**
     * Connects to the ACP server if not already connected, then initializes.
     *
     * For gateway agents, builds the connection config through the gateway
     * lifecycle (startAgent → connectRuntime → resolve WebSocket URL).
     * For manual servers, uses the stored connection parameters directly.
     *
     * If initialization returns [AcpInitializeResult.AuthenticationRequired],
     * sets [shouldRecoverBridge] to false to prevent reconnection loops.
     */
    private suspend fun ensureConnectedAndInitialized(): Boolean {
        if (connectionManager.isConnected) {
            publishCapabilities()
            return true
        }
        val server =
            launchableTargetRepository.getTarget(serverId) ?: run {
                logError(
                    "ensureConnectedAndInitialized: target not found for serverId=$serverId",
                    null,
                )
                return false
            }
        val connected =
            when (server) {
                is LaunchableTarget.GatewayAgent -> {
                    val config = buildGatewayConnectionConfig(server) ?: return false
                    connectionManager.connect(config)
                }

                is LaunchableTarget.Manual -> {
                    connectionManager.connect(
                        AcpConnectionConfig(
                            scheme = server.server.scheme,
                            host = server.server.host,
                            preferredAuthMethodId = server.server.preferredAuthMethodId,
                            serverDisplayName = server.name,
                        ),
                    )
                }
            }
        if (!connected) return false
        return when (val initializeResult = connectionManager.initialize()) {
            is AcpInitializeResult.Ready -> {
                currentCapabilities = initializeResult.agentCapabilities
                callbacks.onCapabilitiesChanged(initializeResult.agentCapabilities)
                true
            }

            is AcpInitializeResult.AuthenticationRequired -> {
                shouldRecoverBridge = false
                callbacks.onLoadFailed(
                    "ACP authentication is required for this server. " +
                        "Reconnect from the server list and choose an auth method.",
                )
                false
            }

            null -> false
        }
    }

    /**
     * Builds an [AcpConnectionConfig] for a gateway agent.
     *
     * Refreshes the gateway source if needed, then goes through the gateway
     * lifecycle: startAgent → connectRuntime → resolve WebSocket URL.
     * Returns null on failure (unpaired gateway, connection errors).
     */
    private suspend fun buildGatewayConnectionConfig(
        target: LaunchableTarget.GatewayAgent,
    ): AcpConnectionConfig? {
        val gatewaySource = target.gatewaySource
        if (gatewaySource.gatewayCredential.isBlank()) {
            callbacks.onLoadFailed("Gateway is not paired for ${target.name}.")
            return null
        }
        return try {
            val refreshedSource =
                refreshGatewaySourceIfNeeded(
                    gatewaySource,
                    gatewayRepository,
                    gatewaySourceRepository,
                )
            val runtime =
                gatewayRepository.startAgent(
                    scheme = refreshedSource.scheme,
                    host = refreshedSource.host,
                    gatewayCredential = refreshedSource.gatewayCredential,
                    agentId = target.binding.agentId,
                )
            val handoff =
                gatewayRepository.connectRuntime(
                    scheme = refreshedSource.scheme,
                    host = refreshedSource.host,
                    gatewayCredential = refreshedSource.gatewayCredential,
                    runtimeId = runtime.id,
                )
            AcpConnectionConfig(
                scheme = refreshedSource.scheme,
                host = refreshedSource.host,
                webSocketUrl = resolveGatewayWebSocketUrl(refreshedSource, handoff),
                webSocketBearerToken = handoff.bearerToken,
                preferredAuthMethodId = target.binding.preferredAuthMethodId,
                gatewayRuntimeId = runtime.id,
                gatewaySourceId = refreshedSource.id,
                serverDisplayName = target.name,
            )
        } catch (error: Throwable) {
            callbacks.onLoadFailed(
                "Failed to reconnect to ${target.name}: ${error.message ?: "unknown error"}",
            )
            null
        }
    }

    // ---- Internal: bridge attachment and observation ----

    /**
     * Binds a [SessionPort] as the active bridge.
     *
     * Updates [activeSessionId], clears the pending model selection, and
     * enables bridge recovery. Does NOT start observers or persist —
     * see [attachSessionBridge] for the full attach sequence.
     */
    private fun bindSessionBridge(bridge: SessionPort) {
        activeSessionId = bridge.sessionId
        sessionBridge = bridge
        pendingModelSelectionId = null
        shouldRecoverBridge = true
        cancelBridgeRecovery()
    }

    /**
     * Full attach sequence: bind the bridge, persist the session if needed,
     * and start observing its flows.
     */
    private suspend fun attachSessionBridge(bridge: SessionPort) {
        bindSessionBridge(bridge)
        persistSessionIfNeeded(bridge)
        observeSessionBridge(bridge)
    }

    /**
     * Starts two parallel collection coroutines on the bridge's flows:
     *
     * 1. **snapshot** — collects [SessionSnapshot] emissions and forwards
     *    them to [Callbacks.onSnapshot]. The debug-only trace logs a compact
     *    signature so repeated identical snapshots are filtered from logcat.
     * 2. **modelSelectionEvents** — collects
     *    [AppSessionEvent.ModelSelectionConfirmed] events to confirm
     *    locally-initiated model selections. See [handleModelSelectionConfirmed].
     */
    private fun observeSessionBridge(bridge: SessionPort) {
        clearBridgeObservers()
        bridgeObserverJobs =
            listOf(
                scope.launch {
                    var lastSignature: String? = null
                    bridge.snapshot.collect { snapshot ->
                        if (BuildConfig.DEBUG) {
                            val signature =
                                buildString {
                                    append("state=")
                                    append(snapshot.loadState)
                                    append(" messages=")
                                    append(snapshot.messages.size)
                                    append(" streaming=")
                                    append(snapshot.isStreaming)
                                    append(" lastId=")
                                    append(snapshot.messages.lastOrNull()?.id)
                                    append(" lastRole=")
                                    append(snapshot.messages.lastOrNull()?.role)
                                    append(" lastLen=")
                                    append(
                                        snapshot.messages
                                            .lastOrNull()
                                            ?.content
                                            ?.length ?: 0,
                                    )
                                    append(" commands=")
                                    append(snapshot.availableCommands.size)
                                    append(" configOptions=")
                                    append(snapshot.configOptions.size)
                                }
                            if (signature != lastSignature) {
                                trace("snapshot session=${bridge.sessionId} $signature")
                                lastSignature = signature
                            }
                        }
                        callbacks.onSnapshot(snapshot)
                    }
                },
                scope.launch {
                    bridge.modelSelectionEvents?.collect { event ->
                        handleModelSelectionConfirmed(event)
                    }
                },
            )
    }

    /** Cancels all active bridge observer coroutines. */
    private fun clearBridgeObservers() {
        bridgeObserverJobs.forEach { it.cancel() }
        bridgeObserverJobs = emptyList()
    }

    /**
     * Handles a [AppSessionEvent.ModelSelectionConfirmed] event.
     *
     * If there is a [pendingModelSelectionId] and the event's modelId matches
     * it (or is blank, meaning the server echoed the change without specifying
     * the model), clears the pending ID and notifies the UI via
     * [Callbacks.onModelUpdated].
     */
    private suspend fun handleModelSelectionConfirmed(
        event: AppSessionEvent.ModelSelectionConfirmed,
    ) {
        val pendingModel = pendingModelSelectionId
        if (pendingModel != null &&
            (event.modelId.isNullOrBlank() || event.modelId == pendingModel)
        ) {
            pendingModelSelectionId = null
            callbacks.onModelUpdated()
        }
    }

    /**
     * Ensures a session bridge is available for sending a message.
     *
     * If the bridge is already attached, returns it immediately. Otherwise,
     * connects to the server, tries to reattach to the existing session, and
     * falls back to creating a fresh session if reattachment fails.
     */
    private suspend fun ensureSessionReadyForSend(): SessionPort? {
        sessionBridge?.let { return it }
        if (!ensureConnectedAndInitialized()) return null

        connectionManager.getSession(activeSessionId)?.let { existing ->
            attachSessionBridge(existing)
            callbacks.onSessionReady()
            return existing
        }

        val created =
            try {
                connectionManager.createSession(cwd)
            } catch (_: AcpAuthenticationRequiredException) {
                callbacks.onLoadFailed(
                    "ACP authentication is required for this server. " +
                        "Return to the session list and authenticate first.",
                )
                null
            } ?: return null
        attachSessionBridge(created)
        callbacks.onSessionReady()
        return created
    }

    // ---- Internal: bridge recovery ----

    /**
     * Starts a periodic recovery loop that tries to reattach the bridge
     * after the transport reconnects.
     *
     * The loop exits when:
     * - [shouldRecoverBridge] is set to false (unrecoverable condition)
     * - A bridge is successfully recovered
     * - The transport disconnects again
     *
     * Only one recovery loop runs at a time — [bridgeRecoveryJob] tracks the
     * active job.
     */
    private fun scheduleBridgeRecovery() {
        if (!shouldRecoverBridge || sessionBridge != null || bridgeRecoveryJob != null) return

        bridgeRecoveryJob =
            scope.launch {
                try {
                    while (shouldRecoverBridge && sessionBridge == null) {
                        if (!connectionManager.isConnected) break

                        val recoveredBridge =
                            bridgeOperationMutex.withLock {
                                if (sessionBridge != null) {
                                    sessionBridge
                                } else {
                                    recoverSessionBridge()
                                }
                            }
                        if (recoveredBridge != null) {
                            trace(
                                "recoverSessionBridge:recovered " +
                                    "sessionId=${recoveredBridge.sessionId}",
                            )
                            callbacks.onSessionReady()
                            break
                        }

                        delay(bridgeRecoveryRetryDelayMs)
                    }
                } finally {
                    bridgeRecoveryJob = null
                }
            }
    }

    /** Cancels the active bridge recovery loop, if any. */
    private fun cancelBridgeRecovery() {
        bridgeRecoveryJob?.cancel()
        bridgeRecoveryJob = null
    }

    /**
     * Invalidates the active bridge: clears the reference, pending model
     * selection, and observer jobs. Called on disconnect/failure so that
     * send operations fail-fast until recovery succeeds.
     */
    private fun invalidateActiveBridge() {
        sessionBridge = null
        pendingModelSelectionId = null
        clearBridgeObservers()
    }

    /**
     * Attempts to reattach to the existing session after reconnection.
     *
     * Strategy:
     * 1. If the session is still in the [AcpConnectionManager] registry,
     *    reattaches immediately (no RPC needed).
     * 2. If the agent does not advertise session/load, creates a fresh
     *    session instead.
     * 3. Otherwise, tries [AcpConnectionManager.loadSession] with a timeout.
     */
    private suspend fun recoverSessionBridge(): SessionPort? {
        connectionManager.getSession(activeSessionId)?.let { existing ->
            attachSessionBridge(existing)
            return existing
        }

        publishCapabilities()
        val capabilities =
            currentCapabilities ?: connectionManager.agentCapabilities.value
        if (capabilities != null && !capabilities.loadSession) {
            val created =
                runCatching { connectionManager.createSession(cwd) }
                    .getOrNull() ?: return null
            attachSessionBridge(created)
            return created
        }

        val recovered =
            try {
                withTimeout(sessionLoadTimeoutMs) {
                    connectionManager.loadSession(
                        sessionId = activeSessionId,
                        cwd = cwd,
                    )
                }
            } catch (_: AcpAuthenticationRequiredException) {
                callbacks.onLoadFailed(
                    "ACP authentication is required for this server. " +
                        "Return to the session list and authenticate first.",
                )
                null
            } catch (_: TimeoutCancellationException) {
                null
            } catch (error: Exception) {
                callbacks.onLoadFailed(
                    formatAcpErrorMessage(error, "Failed to load session"),
                )
                null
            }
        if (recovered != null) {
            attachSessionBridge(recovered)
        }
        return recovered
    }

    // ---- Internal: helpers ----

    /**
     * Returns the active bridge or shows an error if missing.
     * Used by permission and configuration actions that depend on a ready
     * bridge but should not attempt recovery.
     */
    private suspend fun requireActiveBridge(action: String): SessionPort? =
        sessionBridge ?: run {
            trace("$action: bridge missing activeSessionId=$activeSessionId")
            callbacks.onOperationError(
                message = SESSION_NOT_READY_MESSAGE,
                stopStreaming = false,
            )
            null
        }

    /**
     * Produces a user-facing error message for a send failure.
     *
     * Maps known error patterns (request timeout, invalid params) to friendly
     * strings. Falls back to [formatAcpErrorMessage] for provider-specific
     * messages, and to a generic unknown-error string as the last resort.
     */
    private fun userFacingSendError(error: Throwable): String {
        val detailedMessage = formatAcpErrorMessage(error, "Send failed")
        val raw = error.message.orEmpty()
        return when {
            raw.contains("Request timeout", ignoreCase = true) ->
                "Request timed out. Please try again."
            raw.contains("Invalid params", ignoreCase = true) ->
                "Send failed due to an invalid request format."
            detailedMessage != "Send failed" ->
                detailedMessage
            else ->
                "Send failed due to an unknown error."
        }
    }

    /** Checks whether an error is an RPC "Method not found: session/cancel". */
    private fun isSessionCancelUnsupported(error: Throwable): Boolean {
        val raw = error.message.orEmpty()
        return raw.contains("Method not found", ignoreCase = true) &&
            raw.contains("session/cancel", ignoreCase = true)
    }

    /**
     * Checks whether an error indicates the gRPC/stream bridge was destroyed.
     *
     * gRPC streams can be destroyed by server restarts or connection drops.
     * When this happens during load, the coordinator falls back to creating a
     * fresh live session instead of failing permanently.
     */
    private fun isDestroyedBridgeStreamError(error: Throwable): Boolean {
        val message = formatAcpErrorMessage(error, "").lowercase()
        return message.contains("write after a stream was destroyed") ||
            message.contains("stream was destroyed")
    }

    /**
     * Publishes the current agent capabilities to the UI if available.
     * Called on connect and initialisation to keep the UI in sync.
     */
    private suspend fun publishCapabilities() {
        connectionManager.agentCapabilities.value?.let { capabilities ->
            currentCapabilities = capabilities
            callbacks.onCapabilitiesChanged(capabilities)
        }
    }

    /**
     * Persists the session locally if the server does not advertise
     * session/list support.
     *
     * When the server does support session/list, sessions are fetched from the
     * server on the session list screen. When it doesn't, the app tracks
     * sessions locally so they appear in the history.
     */
    private suspend fun persistSessionIfNeeded(bridge: SessionPort) {
        if (currentCapabilities?.session?.list != false) return
        callbacks.onSessionStored(
            sessionId = bridge.sessionId,
            cwd = cwd,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private companion object {
        /** Shown when the user tries to send a message or configure a session
         * before the bridge is ready. */
        const val SESSION_NOT_READY_MESSAGE =
            "Session is not ready. Please retry in a moment."
    }
}
