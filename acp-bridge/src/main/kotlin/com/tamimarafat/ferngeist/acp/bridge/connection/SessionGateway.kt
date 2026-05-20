package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigChoice
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigValue
import com.tamimarafat.ferngeist.acp.bridge.session.SessionMode
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPermissionOption
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPort
import kotlinx.coroutines.CompletableDeferred

/**
 * Owns session-level ACP protocol operations for all active sessions.
 *
 * SessionGateway is the bridge between the thin [AcpConnectionManager] shell and
 * the ACP SDK's [ClientSession] lifecycle. It handles creation, loading, prompt
 * streaming, cancellation, mode/model/config changes, and permission resolution.
 *
 * Each method follows the same pattern: resolve the SDK [ClientSession] from the
 * [AcpSessionRegistry], emit RPC diagnostics, run the SDK call, and forward any
 * resulting events to the correct [SessionBridge] via [emitToBridge].
 *
 * @property orchestra provides the SDK client, diagnostics store, and auth helpers
 * @property permissionFlow tracks pending permission completable-futures
 * @property bridgeFactory creates a [SessionBridge] wired to the thin shell
 */
internal class SessionGateway(
    private val orchestra: ConnectionOrchestrator,
    private val permissionFlow: PermissionFlow,
    private val bridgeFactory: (String) -> SessionBridge,
) {
    private val sessionRegistry = AcpSessionRegistry()

    /**
     * Creates a new ACP session and returns a [SessionPort] for the chat layer.
     *
     * Internally this sends a `session/new` RPC, [registerSession] maps the SDK
     * [ClientSession] capabilities (modes, models, config options) into events.
     * Auth-required errors are re-thrown so the facade can prompt re-auth; other
     * errors are recorded in diagnostics and null is returned.
     */
    suspend fun createSession(cwd: String = "/"): SessionPort? {
        val client = orchestra.sdkClient ?: return null
        return runCatching {
            orchestra.diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/new")
            val session = client.newSession(
                sessionParameters = SessionCreationParameters(cwd = cwd, mcpServers = emptyList()),
                operationsFactory = operationsFactory,
            )
            registerSession(session)
        }.getOrElse {
            orchestra.toAuthRequiredException(it)?.let { error -> throw error }
            orchestra.diagnosticsStore.appendError(
                "session/new",
                formatAcpErrorMessage(it, "Failed to create session"),
            )
            null
        }
    }

    /**
     * Loads (or reattaches to) an existing session and returns a [SessionPort].
     *
     * Short-circuits if the session is already in the registry. Otherwise sends a
     * `session/load` RPC with history-buffering via [SessionBridge.beginHydration].
     * On completion the buffered history is committed via [SessionBridge.completeHydration].
     *
     * A JSON-RPC -32602 / "already loaded" error is handled separately: if the
     * bridge exists locally it is reused; otherwise the session is assumed active
     * on another client and null is returned.
     */
    suspend fun loadSession(
        sessionId: String,
        cwd: String,
    ): SessionPort? {
        getLoadedSession(sessionId)?.let { existing -> return existing }

        val client = orchestra.sdkClient ?: run {
            orchestra.logError("loadSession: sdkClient is NULL, returning null")
            return null
        }
        orchestra.diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/load")
        return runCatching {
            // Store bridge before client.loadSession() so BridgeSessionOperations
            // notify() callbacks during loading have a target for history buffering.
            val bridge = sessionRegistry.getBridge(sessionId)
                ?: bridgeFactory(sessionId).also {
                    sessionRegistry.storeBridge(sessionId, it)
                }
            bridge.beginHydration()

            val session = client.loadSession(
                sessionId = SessionId(sessionId),
                sessionParameters = SessionCreationParameters(cwd = cwd, mcpServers = emptyList()),
                operationsFactory = operationsFactory,
            )
            val registeredBridge = registerSession(session)
            registeredBridge.completeHydration()
            registeredBridge.emitEvent(AppSessionEvent.SessionLoadComplete)
            registeredBridge
        }.getOrElse { error ->
            orchestra.toAuthRequiredException(error)?.let { authError -> throw authError }
            if (isSessionAlreadyLoadedError(error)) {
                getLoadedSession(sessionId)?.let { existing ->
                    orchestra.diagnosticsStore.appendError(
                        "session/load",
                        "Session is already loaded locally. Reusing the active session.",
                    )
                    return existing
                }

                val message = "This session is already active elsewhere. " +
                    "Reconnect or open a new session instead."
                sessionRegistry.getBridge(sessionId)?.failHydration(message)
                clearSessionState(sessionId, closeBridge = true)
                orchestra.diagnosticsStore.appendError("session/load", message)
                return null
            }

            val message = formatAcpErrorMessage(error, "Failed to load session")
            sessionRegistry.getBridge(sessionId)?.failHydration(message)
            clearSessionState(sessionId, closeBridge = true)
            orchestra.diagnosticsStore.appendError("session/load", message)
            throw error
        }
    }

    /**
     * Sends a user prompt message (text + optional images) to a session.
     *
     * The flow:
     * 1. Resolves the [SessionBridge] and SDK [ClientSession] from the registry.
     * 2. Emits an optimistic [AppSessionEvent.UserMessage] so the UI updates
     *    before the server round-trip completes.
     * 3. Streams prompt events from the SDK, mapping each [SessionUpdate] and
     *    terminal [PromptResponseEvent] to [AppSessionEvent] values.
     * 4. Includes a defensive [AppSessionEvent.TurnComplete] if the prompt stream
     *    finishes without a PromptResponseEvent — some ACP server/transport combos
     *    can drop the terminal event on cancellation or teardown.
     */
    suspend fun sendSessionMessage(
        sessionId: String,
        content: String,
        images: List<Pair<String, String>> = emptyList(),
    ) {
        val bridge = sessionRegistry.getBridge(sessionId) ?: throw IllegalStateException(
            "Session bridge missing for sessionId=$sessionId",
        )
        val session = sessionRegistry.getSdkSession(sessionId) ?: throw IllegalStateException(
            "SDK session missing for sessionId=$sessionId",
        )

        orchestra.diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/prompt")

        bridge.emitEvent(
            AppSessionEvent.UserMessage(
                text = content,
                timestampMs = System.currentTimeMillis(),
            ),
        )

        val blocks = mutableListOf<ContentBlock>()
        if (content.isNotEmpty()) {
            blocks += ContentBlock.Text(content)
        }
        for ((mimeType, data) in images) {
            blocks += ContentBlock.Image(data = data, mimeType = mimeType)
        }

        // session.prompt returns a cold flow; .collect is terminal and suspends
        // until the entire prompt turn completes (all updates + final response).
        var receivedPromptResponse = false
        session.prompt(blocks).collect { event ->
            when (event) {
                is Event.SessionUpdateEvent -> {
                    val appEvent = AcpSessionUpdateMapper.mapSessionUpdateToEvent(event.update)
                    if (appEvent != null) {
                        bridge.emitEvent(appEvent)
                    }
                }

                is Event.PromptResponseEvent -> {
                    receivedPromptResponse = true
                    bridge.emitEvent(
                        AppSessionEvent.TurnComplete(
                            AcpSessionUpdateMapper.mapStopReason(
                                event.response.stopReason,
                            ),
                        ),
                    )
                }
            }
        }

        if (!receivedPromptResponse) {
            bridge.emitEvent(AppSessionEvent.TurnComplete("end_turn"))
        }
    }

    /**
     * Cancels the current streaming turn via `session/cancel` RPC.
     *
     * On success the diagnostics flag is set to indicate the server supports
     * cancellation. On failure [handleSessionCancelFailure] checks whether the
     * error is a "Method not found" (server does not support cancel) vs. a
     * genuine transport error, and sets the diagnostics flag accordingly.
     */
    suspend fun cancelSession(sessionId: String) {
        val session = sessionRegistry.getSdkSession(sessionId) ?: return
        runCatching {
            orchestra.diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/cancel")
            session.cancel()
            orchestra.diagnosticsStore.setSessionCancelSupport(isSupported = true)
        }.onFailure { handleSessionCancelFailure(it) }
    }

    /** Sets the session's active mode via `session/set_mode` RPC. */
    suspend fun setSessionMode(sessionId: String, modeId: String) {
        val session = sessionRegistry.getSdkSession(sessionId) ?: return
        runCatching {
            orchestra.diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/set_mode")
            session.setMode(SessionModeId(modeId))
        }.onFailure {
            orchestra.diagnosticsStore.appendError(
                "session/set_mode",
                formatAcpErrorMessage(it, "Set mode failed"),
            )
        }
    }

    /** Sets the session's legacy model via `session/set_model` RPC (unstable ACP API). */
    @OptIn(UnstableApi::class)
    suspend fun setSessionModel(sessionId: String, modelId: String) {
        val session = sessionRegistry.getSdkSession(sessionId) ?: return
        runCatching {
            orchestra.diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/set_model")
            session.setModel(com.agentclientprotocol.model.ModelId(modelId))
        }.onFailure {
            orchestra.diagnosticsStore.appendError(
                "session/set_model",
                formatAcpErrorMessage(it, "Set model failed"),
            )
        }
    }

    /**
     * Sets a native configuration option via `session/set_config_option` RPC.
     *
     * The SDK updates session.configOptions from the RPC response, but Ferngeist
     * does not collect that StateFlow directly. To keep dependent options (e.g.
     * model-specific reasoning effort lists) immediately in sync, we mirror the
     * authoritative response into the bridge even when the server does not emit
     * a separate config_option_update notify.
     */
    @OptIn(UnstableApi::class)
    suspend fun setSessionConfigOption(
        sessionId: String,
        optionId: String,
        value: SessionConfigValue,
    ) {
        val session = sessionRegistry.getSdkSession(sessionId) ?: return
        runCatching {
            orchestra.diagnosticsStore.appendRpcEntry(
                RpcDirection.OutboundRequest,
                "session/set_config_option",
            )
            val response = session.setConfigOption(SessionConfigId(optionId), value.toSdkValue())
            // NOTE: Mirror the authoritative response to the bridge even when the server
            // does not emit a separate config_option_update notification. The SDK updates
            // session.configOptions StateFlow from this same response internally, but we
            // don't observe it reactively — see registerSession() for the rationale.
            emitToBridge(
                sessionId,
                AppSessionEvent.ConfigOptionsUpdated(
                    options = response.configOptions.map(
                        AcpSessionUpdateMapper::mapSdkConfigOption,
                    ),
                ),
            )
        }.onFailure {
            orchestra.diagnosticsStore.appendError(
                "session/set_config_option",
                formatAcpErrorMessage(it, "Set config option failed"),
            )
        }
    }

    /**
     * Resolves a pending permission request by completing its deferred outcome
     * with the user's selected option. Emits a [ToolPermissionResolved] event so
     * the UI removes the permission card.
     */
    suspend fun respondPermissionSelected(
        sessionId: String,
        toolCallId: String,
        optionId: String,
    ) {
        val pending = permissionFlow.takePending(toolCallId) ?: return
        pending.deferred.complete(
            RequestPermissionOutcome.Selected(PermissionOptionId(optionId)),
        )
        emitToBridge(sessionId, AppSessionEvent.ToolPermissionResolved(toolCallId))
    }

    /**
     * Cancels a pending permission request by completing its deferred outcome
     * with [RequestPermissionOutcome.Cancelled].
     */
    suspend fun respondPermissionCancelled(
        sessionId: String,
        toolCallId: String,
    ) {
        val pending = permissionFlow.takePending(toolCallId) ?: return
        pending.deferred.complete(RequestPermissionOutcome.Cancelled)
        emitToBridge(sessionId, AppSessionEvent.ToolPermissionResolved(toolCallId))
    }

    /** Returns an existing session as a [SessionPort], or null if not registered. */
    fun getSession(sessionId: String): SessionPort? = sessionRegistry.getPort(sessionId)

    /** Removes a session from the registry and closes its bridge + cancels its pending permissions. */
    fun removeSession(sessionId: String) {
        clearSessionState(sessionId, closeBridge = true)
    }

    /** Clears all sessions (bridges, SDK sessions, pending permissions). */
    fun clearAllSessions() {
        clearAllSessionState(closeBridges = true)
    }

    // ---- RPC dispatch bridge ----
    // Bridges between the ACP SDK's ClientSessionOperations callbacks and
    // Ferngeist's AppSessionEvent stream. Each registered session gets an
    // instance of BridgeSessionOperations.

    private val operationsFactory = ClientOperationsFactory { sessionId, _ ->
        createBridgeSessionOperations(sessionId.value)
    }

    private fun createBridgeSessionOperations(sessionId: String): ClientSessionOperations =
        BridgeSessionOperations(sessionId)

    /**
     * Handles two SDK callbacks per session:
     * - [requestPermissions]: creates a deferred outcome, maps SDK permission
     *   options to Ferngeist types, emits a [ToolPermissionRequested] event,
     *   and suspends until the user responds via [respondPermissionSelected]
     *   or [respondPermissionCancelled].
     * - [notify]: maps each [SessionUpdate] to an [AppSessionEvent] and
     *   forwards it to the bridge.
     */
    private inner class BridgeSessionOperations(
        private val sessionId: String,
    ) : ClientSessionOperations {
        override suspend fun requestPermissions(
            toolCall: SessionUpdate.ToolCallUpdate,
            permissions: List<PermissionOption>,
            _meta: kotlinx.serialization.json.JsonElement?,
        ): RequestPermissionResponse {
            val toolId = toolCall.toolCallId.value
            val deferred = CompletableDeferred<RequestPermissionOutcome>()
            permissionFlow.addPending(
                toolCallId = toolId,
                sessionId = sessionId,
                deferred = deferred,
            )

            val options = permissions.map {
                SessionPermissionOption(
                    id = it.optionId.value,
                    label = it.name,
                    kind = it.kind.name.lowercase(),
                )
            }

            emitToBridge(
                sessionId,
                AppSessionEvent.ToolPermissionRequested(
                    toolCallId = toolId,
                    requestId = toolId,
                    title = toolCall.title,
                    options = options,
                ),
            )

            val outcome = deferred.await()
            return RequestPermissionResponse(outcome = outcome)
        }

        override suspend fun notify(
            notification: SessionUpdate,
            _meta: kotlinx.serialization.json.JsonElement?,
        ) {
            // mapSessionUpdateToEvent covers known SessionUpdate subtypes; null
            // is defensive for unrecognized SDK types — skip silently.
            val appEvent = AcpSessionUpdateMapper.mapSessionUpdateToEvent(notification) ?: return
            emitToBridge(sessionId, appEvent)
        }
    }

    /**
     * Registers an SDK session and creates/reattaches a [SessionBridge].
     *
     * Maps SDK capabilities (modes, models, config options) into their
     * corresponding [AppSessionEvent] values and forwards them to the bridge.
     * After all initial state is pushed, marks the bridge as ready.
     *
     * @return the concrete [SessionBridge] so callers can invoke bridge-internal
     *         methods ([emitEvent], [markReady], hydration lifecycle).
     */
    private suspend fun registerSession(session: ClientSession): SessionBridge {
        val bridge = sessionRegistry.getBridge(session.sessionId.value)
            ?: bridgeFactory(session.sessionId.value)
        sessionRegistry.storeSdkSession(session.sessionId.value, session)
        sessionRegistry.storeBridge(session.sessionId.value, bridge)

        // NOTE: We read mode/model/config once from the SDK's ClientSession StateFlows
        // rather than observing them reactively, so all initial state lands in the bridge
        // replay buffer before markReady(). If the SDK adds a CurrentModelUpdate notify
        // type in a future version, reactive collection could replace the one-shot read.
        if (session.modesSupported) {
            val modes = session.availableModes.map {
                SessionMode(
                    id = it.id.value,
                    name = it.name,
                    description = it.description,
                )
            }
            emitToBridge(
                session.sessionId.value,
                AppSessionEvent.ModesUpdated(
                    modes = modes,
                    currentModeId = session.currentMode.value.value,
                ),
            )
        }

        @OptIn(UnstableApi::class) if (session.modelsSupported) {
            val current = session.currentModel.value.value
            val modelChoices = session.availableModels.map { model ->
                SessionConfigChoice(
                    id = model.modelId.value,
                    label = model.name,
                    value = model.modelId.value,
                    description = model.description,
                )
            }
            emitToBridge(
                session.sessionId.value,
                AppSessionEvent.LegacyModelOptionsUpdated(
                    choices = modelChoices,
                    currentModelId = current,
                ),
            )
            // The SDK exposes currentModel on the session object but not a
            // corresponding SessionUpdate.CurrentModelUpdate event for
            // notify() consumers, so we mirror the initial selection into app state.
            emitToBridge(
                session.sessionId.value,
                AppSessionEvent.ModelSelectionConfirmed(current),
            )
        }

        @OptIn(UnstableApi::class) if (session.configOptionsSupported) {
            emitToBridge(
                session.sessionId.value,
                AppSessionEvent.ConfigOptionsUpdated(
                    options = session.configOptions.value.map(
                        AcpSessionUpdateMapper::mapSdkConfigOption,
                    ),
                ),
            )
        }

        bridge.markReady()
        return bridge
    }

    /**
     * Forwards an [AppSessionEvent] to the bridge for the given session ID.
     *
     * Logs an error if no bridge is registered — this should only happen during
     * torn-down sessions or registry corruption.
     */
    private suspend fun emitToBridge(
        sessionId: String,
        event: AppSessionEvent,
    ) {
        val bridge = sessionRegistry.getBridge(sessionId)
        if (bridge != null) {
            orchestra.trace("emitToBridge sid=$sessionId event=${event::class.simpleName}")
            bridge.emitEvent(event)
            return
        }
        orchestra.logError(
            "emitToBridge: NO BRIDGE for sessionId=$sessionId, " +
                "available keys=${sessionRegistry.bridgeIds()}",
        )
        orchestra.diagnosticsStore.appendError(
            "session",
            "Session bridge not found for id: $sessionId",
        )
    }

    /** Clears all bridges, SDK sessions, and pending permissions. */
    private fun clearAllSessionState(closeBridges: Boolean) {
        sessionRegistry.clearAll(closeBridges = closeBridges)
        permissionFlow.cancelAll()
    }

    /** Clears a single session: SDK session, bridge, and its pending permissions. */
    private fun clearSessionState(
        sessionId: String,
        closeBridge: Boolean,
    ) {
        sessionRegistry.clearSession(sessionId, closeBridge = closeBridge)
        permissionFlow.cancelForSession(sessionId)
    }

    /** Returns a loaded session port if the SDK session exists in the registry. */
    private fun getLoadedSession(sessionId: String): SessionPort? {
        if (!sessionRegistry.hasSdkSession(sessionId)) return null
        return sessionRegistry.getPort(sessionId)
    }

    /** Checks if a JSON-RPC error means the session is already loaded (Invalid params + "already loaded"). */
    private fun isSessionAlreadyLoadedError(error: Throwable): Boolean {
        val rpcError = error as? JsonRpcException ?: return false
        if (rpcError.code != JsonRpcErrorCode.INVALID_PARAMS.code) return false
        return rpcError.message.contains("already loaded", ignoreCase = true)
    }

    /**
     * Inspects a session/cancel failure to determine whether the server
     * genuinely doesn't support the method (as opposed to a network error),
     * and updates the diagnostics flag accordingly.
     */
    private fun handleSessionCancelFailure(error: Throwable) {
        if (isUnsupportedSessionCancel(error)) {
            orchestra.diagnosticsStore.setSessionCancelSupport(isSupported = false)
        }
        orchestra.diagnosticsStore.appendError(
            "session/cancel",
            formatAcpErrorMessage(error, "Cancel failed"),
        )
    }

    /** Checks whether an error is "Method not found: session/cancel" – the server lacks cancel support. */
    private fun isUnsupportedSessionCancel(error: Throwable): Boolean {
        val rpcError = error as? JsonRpcException ?: return false
        if (rpcError.code != JsonRpcErrorCode.METHOD_NOT_FOUND.code) return false
        return rpcError.message.contains("session/cancel", ignoreCase = true)
    }

    /** Converts a Ferngeist [SessionConfigValue] to the SDK's wire format. */
    @OptIn(UnstableApi::class)
    private fun SessionConfigValue.toSdkValue(): SessionConfigOptionValue = when (this) {
        is SessionConfigValue.StringValue -> SessionConfigOptionValue.of(value)
        is SessionConfigValue.BoolValue -> SessionConfigOptionValue.of(value)
        is SessionConfigValue.UnknownValue -> error("Unsupported config option value: $this")
    }
}
