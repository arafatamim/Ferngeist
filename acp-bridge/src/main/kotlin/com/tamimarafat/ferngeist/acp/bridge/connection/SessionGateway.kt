package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.CompleteElicitationNotification
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.CreateElicitationRequest
import com.agentclientprotocol.model.CreateElicitationResponse
import com.agentclientprotocol.model.EmbeddedResourceResource
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
import com.agentclientprotocol.rpc.MethodName
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigChoice
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigValue
import com.tamimarafat.ferngeist.acp.bridge.session.SessionMode
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPermissionOption
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPort
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.SteerOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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
 * @property elicitationFlow tracks pending elicitation completable-futures
 * @property bridgeFactory creates a [SessionBridge] wired to the thin shell
 */
@Suppress("TooManyFunctions")
internal class SessionGateway(
    private val orchestra: ConnectionOrchestrator,
    private val permissionFlow: PermissionFlow,
    private val elicitationFlow: ElicitationFlow,
    private val bridgeFactory: (String) -> SessionBridge,
    private val scope: CoroutineScope,
) {
    private val sessionRegistry = AcpSessionRegistry(scope, ::shouldCloseSdkSession)
    private val observerJobs = ConcurrentHashMap<String, List<Job>>()

    /**
     * Session ids whose current turn was explicitly cancelled through [cancelSession].
     * A prompt stream that ends without a terminal `PromptResponseEvent` then reports
     * `"cancelled"` instead of the misleading `"end_turn"`. Each entry is consumed —
     * and cleared — at the end of its turn so a later turn is not mislabelled.
     */
    private val cancelsRequested = ConcurrentHashMap.newKeySet<String>()

    /** Timing of each in-flight attach, keyed by session id; see [AttachTiming]. */
    private val attachTimings = ConcurrentHashMap<String, AttachTiming>()

    /** Working directory of each registered session, kept so a reconnect can re-attach it. */
    private val sessionCwds = ConcurrentHashMap<String, String>()

    /** The registered sessions and their working directories. */
    fun registeredSessionCwds(): Map<String, String> = sessionCwds.toMap()

    /**
     * Creates a new ACP session and returns a [SessionPort] for the chat layer.
     *
     * Internally this sends a `session/new` RPC, [registerSession] maps the SDK
     * [ClientSession] capabilities (modes, models, config options) into events.
     * Auth-required errors are re-thrown so the facade can prompt re-auth; other
     * errors are recorded in diagnostics and null is returned.
     */
    suspend fun createSession(cwd: String = ""): SessionPort? {
        if (cwd.isBlank()) {
            orchestra.diagnosticsStore.appendError("session/new", "Refusing to create session: blank cwd")
            return null
        }
        val client =
            orchestra.sdkClient
                ?: throw AcpDisconnectedException()
        return runCatching {
            orchestra.diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/new")
            val session =
                client.newSession(
                    sessionParameters = SessionCreationParameters(cwd = cwd, mcpServers = emptyList()),
                    operationsFactory = operationsFactory,
                )
            registerSession(session).also { sessionCwds[session.sessionId.value] = cwd }
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
     * Attaches to an existing session through the RPC the agent advertises:
     * `session/load` (history replay) or `session/resume` (reattach only) — see
     * [SessionAttachRpc].
     *
     * Short-circuits if the session is already in the registry. Otherwise the
     * bridge is registered *before* the RPC so `notify()` callbacks during the
     * attach have a target for history buffering ([SessionBridge.beginHydration]),
     * and the buffered history is committed afterwards
     * ([SessionBridge.completeHydration]). A resume replays nothing, so its bridge
     * simply publishes its empty snapshot.
     *
     * A JSON-RPC -32602 / "already loaded" error is handled separately: if the
     * bridge exists locally it is reused; otherwise the session is assumed active
     * on another client and null is returned.
     */
    @OptIn(UnstableApi::class)
    suspend fun attachSession(
        rpc: SessionAttachRpc,
        sessionId: String,
        cwd: String,
    ): SessionPort? {
        getLoadedSession(sessionId)?.let { existing -> return existing }

        val client =
            orchestra.sdkClient
                ?: throw AcpDisconnectedException()
        orchestra.diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, rpc.rpc)
        val timing = AttachTiming().also { attachTimings[sessionId] = it }
        var succeeded = false
        try {
            return attachAndHydrate(client, rpc, sessionId, cwd).also { succeeded = it != null }
        } finally {
            attachTimings.remove(sessionId, timing)
            reportAttachTiming(rpc, sessionId, timing, succeeded)
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun attachAndHydrate(
        client: Client,
        rpc: SessionAttachRpc,
        sessionId: String,
        cwd: String,
    ): SessionPort? {
        val result =
            runCatching {
                // Store bridge before the attach RPC so BridgeSessionOperations
                // notify() callbacks during loading have a target for history buffering.
                val bridge =
                    sessionRegistry.getBridge(sessionId)
                        ?: bridgeFactory(sessionId).also {
                            sessionRegistry.storeBridge(sessionId, it)
                        }
                // Only a load replays history into the bridge. A resume replays nothing, so
                // hydrating would wipe the transcript a reattached bridge still holds.
                if (rpc == SessionAttachRpc.Load) bridge.beginHydration()

                val parameters = SessionCreationParameters(cwd = cwd, mcpServers = emptyList())
                val session =
                    when (rpc) {
                        SessionAttachRpc.Load ->
                            client.loadSession(
                                sessionId = SessionId(sessionId),
                                sessionParameters = parameters,
                                operationsFactory = operationsFactory,
                            )

                        SessionAttachRpc.Resume ->
                            client.resumeSession(
                                sessionId = SessionId(sessionId),
                                sessionParameters = parameters,
                                operationsFactory = operationsFactory,
                            )
                    }
                val registeredBridge = registerSession(session)
                sessionCwds[sessionId] = cwd
                if (rpc == SessionAttachRpc.Load) registeredBridge.completeHydration()
                registeredBridge.emitEvent(AppSessionEvent.SessionLoadComplete)
                registeredBridge
            }
        return result.getOrElse { error ->
            // The attach deadline lives in the caller (`withTimeout`), so it arrives here
            // as a cancellation — but it is an attach failure, not the caller going away.
            // Without the teardown below the bridge stays registered and HYDRATING for
            // the life of the process, and the screen keeps rendering its spinner.
            if (error is TimeoutCancellationException) {
                return@getOrElse handleSessionAttachFailure(error, sessionId, rpc)
            }
            // A cancelled caller (the chat screen closed mid-attach) is not a
            // failure: rethrow so no local session state is destroyed for an exit.
            if (error is CancellationException) throw error
            handleSessionAttachFailure(error, sessionId, rpc)
        }
    }

    /**
     * Routes a failed `session/load` or `session/resume` to the appropriate
     * recovery: an auth-required error rethrows, an "already loaded" error reuses
     * the local session or raises [SessionAlreadyActiveException] for the
     * remote-active case, and any other failure is logged and rethrown.
     *
     * The `failHydration` + [clearSessionState] teardown runs in [NonCancellable]:
     * `failHydration` takes the runtime mutex, so on an already-cancelled context the
     * lock acquisition would throw before [clearSessionState] runs, stranding the
     * bridge in HYDRATING.
     *
     * @param rpc the attach RPC that failed, for diagnostics and the failure label.
     *
     * Suppresses `ThrowsCount`: this is the single funnel for every attach failure —
     * auth rethrow, remote-active, and the original error.
     */
    @Suppress("ThrowsCount")
    private suspend fun handleSessionAttachFailure(
        error: Throwable,
        sessionId: String,
        rpc: SessionAttachRpc,
    ): SessionPort? {
        orchestra.toAuthRequiredException(error)?.let { authError -> throw authError }
        if (isSessionAlreadyLoadedError(error)) {
            getLoadedSession(sessionId)?.let { existing ->
                orchestra.diagnosticsStore.appendError(
                    rpc.rpc,
                    "Session is already loaded locally. Reusing the active session.",
                )
                return existing
            }

            val message =
                "This session is already active elsewhere. " +
                    "Disconnect it from the session list, then reopen."
            // failHydration takes the runtime mutex; on an already-cancelled context the
            // lock acquisition throws and clearSessionState never runs, leaving the
            // bridge stuck in HYDRATING (which buffers every event and publishes nothing).
            withContext(NonCancellable) {
                sessionRegistry.getBridge(sessionId)?.failHydration(message)
                clearSessionState(sessionId, closeBridge = true)
            }
            orchestra.diagnosticsStore.appendError(rpc.rpc, message)
            // The user-facing text is an instruction, not a diagnosis. Keep the agent's own
            // words beside it, so a refusal classified from wording stays diagnosable when
            // that wording turns out to have meant something else.
            orchestra.diagnosticsStore.appendError(
                rpc.rpc,
                formatAcpErrorMessage(error, "Attach refused"),
            )
            // Thrown rather than returned: the callers' null path collapses every
            // cause into "Could not load this session. Check connection and retry.",
            // which hides the one instruction that helps — release it elsewhere.
            throw SessionAlreadyActiveException(message)
        }

        val message =
            formatAcpErrorMessage(
                error,
                rpc.failureLabel,
            )
        withContext(NonCancellable) {
            sessionRegistry.getBridge(sessionId)?.failHydration(message)
            clearSessionState(sessionId, closeBridge = true)
        }
        orchestra.diagnosticsStore.appendError(rpc.rpc, message)
        throw error
    }

    /**
     * Sends a user prompt message (text + optional images) to a session.
     *
     * The flow:
     * 1. Resolves the [SessionBridge] and SDK [ClientSession] from the registry.
     * 2. Emits an optimistic [AppSessionEvent.UserMessage] so the UI updates
     *    before the server round-trip completes.
     * 3. Streams prompt events from the SDK, mapping each [SessionUpdate] and
     *    terminal [Event.PromptResponseEvent] to [AppSessionEvent] values. The
     *    stream is collected on the bridge's own scope ([SessionBridge.startTurn])
     *    so a cancelled caller cannot abort the turn, then merely awaited here.
     * 4. Includes a defensive [AppSessionEvent.TurnComplete] if the prompt stream
     *    finishes without a PromptResponseEvent — some ACP server/transport combos
     *    can drop the terminal event on cancellation or teardown. That fallback
     *    reports `"cancelled"` when [cancelSession] was requested for the session
     *    and `"end_turn"` otherwise.
     */
    suspend fun sendSessionMessage(
        sessionId: String,
        content: String,
        images: List<ChatImageData> = emptyList(),
        files: List<ChatFileData> = emptyList(),
    ) {
        val bridge =
            sessionRegistry.getBridge(sessionId) ?: throw IllegalStateException(
                "Session bridge missing for sessionId=$sessionId",
            )
        val session =
            sessionRegistry.getSdkSession(sessionId) ?: throw IllegalStateException(
                "SDK session missing for sessionId=$sessionId",
            )

        orchestra.diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/prompt")

        // User message already added optimistically by onLocalPromptStarted; omitted here to
        // avoid duplication.
        val blocks = promptBlocks(content, images, files)

        // session.prompt returns a cold flow; .collect is terminal and suspends
        // until the entire prompt turn completes (all updates + final response).
        // The turn must outlive this caller: a chat screen's viewModelScope is
        // cancelled when the user navigates away, and cancelling this collector
        // would make the SDK send a `$/cancelRequest`, aborting the agent mid-turn
        // with no terminal event — stranding the transcript in a streaming state.
        // startTurn runs it on the bridge's own scope, which lives until the
        // session is torn down.
        // Awaiting merely joins the turn: if this caller is cancelled (the chat screen
        // closed) the cancellation propagates from here while the turn keeps running on
        // the bridge scope, emitting its own terminal event when the agent finishes.
        awaitTurn(bridge.startTurn { collectPromptTurn(sessionId, session, bridge, blocks) })
    }

    /**
     * Steers a prompt into [sessionId]'s running turn (see SessionSteering.kt). Any failure,
     * including an agent without the method, reads as [SteerOutcome.NotConsumed] so the
     * caller falls back to an ordinary prompt.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun steerSession(
        sessionId: String,
        content: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
    ): SteerOutcome {
        val client = orchestra.sdkClient ?: return SteerOutcome.NotConsumed
        orchestra.diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, STEERING_METHOD)
        return try {
            val params = steeringParams(sessionId, promptBlocks(content, images, files))
            parseSteerOutcome(client.protocol.sendRequestRaw(MethodName(STEERING_METHOD), params))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            orchestra.diagnosticsStore.appendError(STEERING_METHOD, e.message ?: e.toString())
            SteerOutcome.NotConsumed
        }
    }

    private fun promptBlocks(
        content: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
    ): List<ContentBlock> =
        buildList {
            if (content.isNotEmpty()) add(ContentBlock.Text(content))
            images.forEach { add(ContentBlock.Image(data = it.base64, mimeType = it.mimeType)) }
            files.forEach { file ->
                add(
                    ContentBlock.Resource(
                        resource =
                            EmbeddedResourceResource.BlobResourceContents(
                                blob = file.base64,
                                uri = "file:///${file.name}",
                                mimeType = file.mimeType,
                            ),
                    ),
                )
            }
        }

    /**
     * Joins [turn]. A cancelled caller rethrows as-is. A live caller whose turn was
     * cancelled under it (the transport dropped) gets a failure instead: a bare
     * CancellationException would silently end the caller's own coroutine.
     */
    private suspend fun awaitTurn(turn: Deferred<Unit>) {
        try {
            turn.await()
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw AcpDisconnectedException().apply { initCause(error) }
        }
    }

    /**
     * Collects one prompt turn and maps it onto the bridge's event stream.
     *
     * Runs on the bridge's own scope (see [sendSessionMessage]) so it survives a
     * cancelled caller. A turn therefore always reaches a terminal event:
     * - the stream ends without a [Event.PromptResponseEvent] — some ACP
     *   server/transport combos drop the terminal event on cancellation or
     *   teardown — a defensive [AppSessionEvent.TurnComplete] is emitted;
     * - the stream throws, in which case the turn ends before the error is
     *   rethrown, because no awaiter may remain to run the rollback.
     *
     * Either way the stop reason is `"cancelled"` when a cancel was requested for
     * this session and `"end_turn"` otherwise. The cancel mark is consumed here so
     * the next turn starts clean.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun collectPromptTurn(
        sessionId: String,
        session: ClientSession,
        bridge: SessionBridge,
        blocks: List<ContentBlock>,
    ) {
        // Start clean: a cancel that arrived after the previous turn had already
        // finished must not mislabel this one.
        cancelsRequested.remove(sessionId)
        var receivedPromptResponse = false
        try {
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
        } catch (error: CancellationException) {
            // Interrupted (transport reset) or torn down with the session. Either way the
            // turn is over: without a terminal event the bridge would read as streaming
            // forever, and the hub would never evict it or let it be closed.
            withContext(NonCancellable) {
                bridge.emitEvent(AppSessionEvent.TurnComplete(consumeTurnEndReason(sessionId)))
            }
            throw error
        } catch (error: Exception) {
            // This turn runs on the bridge scope, so whoever started it may already
            // be gone (the chat screen closed). Nobody is left to run the rollback
            // in that case, so end the turn here — otherwise a transport or SDK
            // failure after navigation leaves the optimistic bubble streaming
            // forever. Still rethrown so a live awaiter can surface the error.
            bridge.emitEvent(AppSessionEvent.TurnComplete(consumeTurnEndReason(sessionId)))
            throw error
        }

        if (!receivedPromptResponse) {
            bridge.emitEvent(AppSessionEvent.TurnComplete(consumeTurnEndReason(sessionId)))
        }
    }

    /**
     * Consumes this session's cancel mark (set by [cancelSession]) and maps it to a stop
     * reason: `"cancelled"` when the turn was explicitly cancelled, `"end_turn"` when the
     * stream simply ended without reporting one.
     */
    private fun consumeTurnEndReason(sessionId: String): String =
        if (cancelsRequested.remove(sessionId)) "cancelled" else "end_turn"

    /**
     * Cancels the current streaming turn via `session/cancel` RPC.
     *
     * On success the diagnostics flag is set to indicate the server supports
     * cancellation, the session is marked as having a cancel in flight (so a prompt
     * stream that dies without a terminal event reports `"cancelled"`), and any
     * pending permission requests are completed as [RequestPermissionOutcome.Cancelled]:
     * [BridgeSessionOperations.requestPermissions] suspends on its deferred, so leaving
     * one pending would block the agent's tool call forever. On failure
     * [handleSessionCancelFailure] records the error and, for a `-32601`, marks cancel
     * unsupported — then the failure **propagates**. Swallowing it here left the UI
     * reporting a cancelled turn the agent never received, and made both of
     * [AcpChatSessionFacade.cancelStreaming]'s failure branches unreachable.
     */
    suspend fun cancelSession(sessionId: String) {
        val session = sessionRegistry.getSdkSession(sessionId) ?: return
        // Mark before the RPC: the prompt stream can finish while cancel() is still
        // in flight, and it consumes this mark to report "cancelled". Marking after
        // the call would let that turn end as "end_turn" and leave the mark behind.
        cancelsRequested += sessionId
        val outcome =
            runCatching {
                orchestra.diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/cancel")
                session.cancel()
            }
        outcome.onFailure { error ->
            handleSessionCancelFailure(error)
            // Unmark before rethrowing: no cancel reached the agent, so a prompt stream
            // that ends now must not report itself as cancelled.
            cancelsRequested -= sessionId
        }
        outcome.getOrThrow()
        orchestra.diagnosticsStore.setSessionCancelSupport(isSupported = true)

        permissionFlow.cancelPendingForSession(sessionId).forEach { toolCallId ->
            emitToBridge(sessionId, AppSessionEvent.ToolPermissionResolved(toolCallId))
        }
        elicitationFlow.cancelPendingForSession(sessionId).forEach { key ->
            emitToBridge(sessionId, AppSessionEvent.ElicitationResolved(key))
        }
    }

    /**
     * Sets the session's active mode via `session/set_mode` RPC.
     *
     * The failure propagates. Diagnostics-only was not enough: [SessionBridge] emits
     * its optimistic config event regardless of this call's outcome, so a rejected
     * mode rendered as the current one with no error anywhere.
     */
    suspend fun setSessionMode(
        sessionId: String,
        modeId: String,
    ) {
        val session = sessionRegistry.getSdkSession(sessionId) ?: return
        runCatching {
            orchestra.diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/set_mode")
            session.setMode(SessionModeId(modeId))
        }.onFailure {
            orchestra.diagnosticsStore.appendError(
                "session/set_mode",
                formatAcpErrorMessage(it, "Set mode failed"),
            )
        }.getOrThrow()
    }

    /** Sets the session's legacy model via `session/set_model` RPC (unstable ACP API). */
    @OptIn(UnstableApi::class)
    suspend fun setSessionModel(
        sessionId: String,
        modelId: String,
    ) {
        val session = sessionRegistry.getSdkSession(sessionId) ?: return
        runCatching {
            orchestra.diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/set_model")
            session.setModel(com.agentclientprotocol.model.ModelId(modelId))
        }.onFailure {
            orchestra.diagnosticsStore.appendError(
                "session/set_model",
                formatAcpErrorMessage(it, "Set model failed"),
            )
        }.getOrThrow()
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
                    options =
                        response.configOptions.map(
                            AcpSessionUpdateMapper::mapSdkConfigOption,
                        ),
                ),
            )
        }.onFailure {
            orchestra.diagnosticsStore.appendError(
                "session/set_config_option",
                formatAcpErrorMessage(it, "Set config option failed"),
            )
        }.getOrThrow()
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

    /**
     * Accepts a pending elicitation with the user's answers, resolving the
     * agent's `elicitation/create` call. Emits an [ElicitationResolved] event so
     * the UI dismisses the sheet.
     */
    @OptIn(UnstableApi::class)
    suspend fun submitElicitation(
        sessionId: String,
        key: String,
        values: Map<String, com.tamimarafat.ferngeist.core.model.ChatElicitationValue>,
    ) {
        val pending = elicitationFlow.takePending(key) ?: return
        pending.deferred.complete(ElicitationMappers.toAcceptResponse(values))
        emitToBridge(sessionId, AppSessionEvent.ElicitationResolved(key))
    }

    /** Declines a pending elicitation: the user explicitly refused the request. */
    @OptIn(UnstableApi::class)
    suspend fun declineElicitation(
        sessionId: String,
        key: String,
    ) {
        val pending = elicitationFlow.takePending(key) ?: return
        pending.deferred.complete(ElicitationMappers.declineResponse())
        emitToBridge(sessionId, AppSessionEvent.ElicitationResolved(key))
    }

    /** Cancels a pending elicitation: dismissed without choosing. */
    @OptIn(UnstableApi::class)
    suspend fun cancelElicitation(
        sessionId: String,
        key: String,
    ) {
        val pending = elicitationFlow.takePending(key) ?: return
        pending.deferred.complete(ElicitationMappers.cancelResponse())
        emitToBridge(sessionId, AppSessionEvent.ElicitationResolved(key))
    }

    /**
     * Returns a session attached on the current connection, or null. A bridge kept
     * across a transport reset is not one until it is attached again.
     */
    fun getSession(sessionId: String): SessionPort? = getLoadedSession(sessionId)

    /** Removes a session from the registry and closes its bridge + cancels its pending permissions. */
    fun removeSession(sessionId: String) {
        clearSessionState(sessionId, closeBridge = true)
    }

    /**
     * Ends a turn the gateway reports finished on an earlier connection. Its chunks
     * reach the reattached bridge, but its terminal reply answered a request this
     * connection never made, so nothing else would close it.
     */
    suspend fun endRemoteTurn(
        sessionId: String,
        stopReason: String,
    ) {
        sessionRegistry.getBridge(sessionId)?.emitEvent(AppSessionEvent.TurnComplete(stopReason))
    }

    /** Clears all sessions (bridges, SDK sessions, pending permissions). */
    fun clearAllSessions() {
        clearAllSessionState(closeBridges = true)
    }

    /**
     * Drops every session from a connection that is going away but keeps their
     * bridges, ending any turn in flight. The next [attachSession] of a session
     * reuses its bridge, so the transcript and the bridge identity survive.
     */
    fun detachAllSessions() {
        observerJobs.values.forEach { jobs -> jobs.forEach { it.cancel() } }
        observerJobs.clear()
        sessionCwds.clear()
        sessionRegistry.detachAll()
        permissionFlow.cancelAll()
        elicitationFlow.cancelAll()
    }

    // ---- RPC dispatch bridge ----
    // Bridges between the ACP SDK's ClientSessionOperations callbacks and
    // Ferngeist's AppSessionEvent stream. Each registered session gets an
    // instance of BridgeSessionOperations.

    private val operationsFactory =
        ClientOperationsFactory { sessionId, _ ->
            createBridgeSessionOperations(sessionId.value)
        }

    private fun createBridgeSessionOperations(sessionId: String): ClientSessionOperations =
        BridgeSessionOperations(sessionId)

    /**
     * Handles three SDK callbacks per session:
     * - [requestPermissions]: creates a deferred outcome, maps SDK permission
     *   options to Ferngeist types, emits a [ToolPermissionRequested] event,
     *   and suspends until the user responds via [respondPermissionSelected]
     *   or [respondPermissionCancelled].
     * - [createElicitation]: maps the SDK elicitation to a [ChatElicitationRequest],
     *   emits an [ElicitationRequested] event, and suspends until the user
     *   responds via [submitElicitation], [declineElicitation] or [cancelElicitation].
     *   Request-scoped elicitations answer `cancel` at once — they have no
     *   session UI to attach to.
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

            val options =
                permissions.map {
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

        @OptIn(UnstableApi::class)
        override suspend fun createElicitation(request: CreateElicitationRequest) = runElicitation(request)

        /**
         * Runs one `elicitation/create` round-trip: unsupported scopes answer `cancel`
         * at once, renderable ones suspend on a deferred the UI resolves. Split from
         * the override so its signature fits the line budget.
         */
        @OptIn(UnstableApi::class)
        private suspend fun runElicitation(request: CreateElicitationRequest): CreateElicitationResponse {
            val domain = ElicitationMappers.toDomain(sessionId, request)
            if (domain == null) {
                orchestra.diagnosticsStore.appendError(
                    "elicitation/create",
                    "Unsupported elicitation (request-scoped); answered cancel.",
                )
                return ElicitationMappers.cancelResponse()
            }
            val deferred = CompletableDeferred<CreateElicitationResponse>()
            elicitationFlow.addPending(key = domain.key, sessionId = sessionId, deferred = deferred)
            emitToBridge(sessionId, AppSessionEvent.ElicitationRequested(domain))
            try {
                return deferred.await()
            } catch (error: CancellationException) {
                // Transport reset or teardown cancelled the deferred without resolving it
                // (detach/clear paths cancel rather than answer). Clear the sheet so it
                // cannot linger past the connection that owned it, then rethrow so the
                // agent sees the elicitation die with its turn.
                elicitationFlow.takePending(domain.key)
                emitToBridge(sessionId, AppSessionEvent.ElicitationResolved(domain.key))
                throw error
            }
        }

        @OptIn(UnstableApi::class)
        override suspend fun completeElicitation(notification: CompleteElicitationNotification) {
            emitToBridge(
                sessionId,
                AppSessionEvent.ElicitationCompleted(notification.elicitationId.value),
            )
        }

        override suspend fun notify(
            notification: SessionUpdate,
            _meta: kotlinx.serialization.json.JsonElement?,
        ) {
            val receivedNanos = System.nanoTime()
            try {
                // mapSessionUpdateToEvent covers known SessionUpdate subtypes; null
                // is defensive for unrecognized SDK types — skip silently.
                val appEvent = AcpSessionUpdateMapper.mapSessionUpdateToEvent(notification) ?: return
                emitToBridge(sessionId, appEvent)
            } finally {
                attachTimings[sessionId]?.record(receivedNanos, System.nanoTime())
            }
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
        val bridge =
            sessionRegistry.getBridge(session.sessionId.value)
                ?: bridgeFactory(session.sessionId.value)
        sessionRegistry.storeSdkSession(session.sessionId.value, session)
        sessionRegistry.storeBridge(session.sessionId.value, bridge)

        pushInitialSessionState(session)

        bridge.markReady()
        startReactiveObservers(session)
        return bridge
    }

    /**
     * Mirrors the SDK session's initial capability state (modes, models, config
     * options) into app events, pushed to the bridge replay buffer before
     * markReady(). After registration, [startReactiveObservers] forwards changes.
     */
    private suspend fun pushInitialSessionState(session: ClientSession) {
        // One-shot initial reads of availableModes/availableModels (plain List, not
        // StateFlow, so the SDK doesn't expose change notifications for them) and
        // of currentMode/currentModel/configOptions. The initial values land in the
        // bridge replay buffer before markReady(). After that, we reactively collect
        // currentMode/currentModel/configOptions with drop(1) to skip the initial
        // emission (already mirrored above) and forward subsequent changes to the
        // bridge so the UI updates when the agent mutates them mid-session.
        if (session.modesSupported) {
            val modes =
                session.availableModes.map {
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

        @OptIn(UnstableApi::class)
        if (session.modelsSupported) {
            val current = session.currentModel.value.value
            val modelChoices =
                session.availableModels.map { model ->
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

        @OptIn(UnstableApi::class)
        if (session.configOptionsSupported) {
            emitToBridge(
                session.sessionId.value,
                AppSessionEvent.ConfigOptionsUpdated(
                    options =
                        session.configOptions.value.map(
                            AcpSessionUpdateMapper::mapSdkConfigOption,
                        ),
                ),
            )
        }
    }

    /**
     * Subscribes to the [ClientSession] StateFlows that can change after the
     * session is registered: [currentMode], [currentModel], [configOptions].
     * Each subscriber runs in [scope] and is cancelled in [clearSessionState].
     *
     * `drop(1)` skips the current value, which was already emitted into the
     * bridge replay buffer by [registerSession]'s one-shot reads.
     */
    @OptIn(UnstableApi::class)
    private fun startReactiveObservers(session: ClientSession) {
        val sessionId = session.sessionId.value
        val jobs = mutableListOf<Job>()

        if (session.modesSupported) {
            val availableModesSnapshot = session.availableModes
            jobs +=
                session.currentMode
                    .drop(1)
                    .onEach { newModeId ->
                        emitToBridge(
                            sessionId,
                            AppSessionEvent.ModesUpdated(
                                modes =
                                    availableModesSnapshot.map { mode ->
                                        SessionMode(
                                            id = mode.id.value,
                                            name = mode.name,
                                            description = mode.description,
                                        )
                                    },
                                currentModeId = newModeId.value,
                            ),
                        )
                    }.launchIn(scope)
        }

        if (session.modelsSupported) {
            jobs +=
                session.currentModel
                    .drop(1)
                    .onEach { newModelId ->
                        emitToBridge(
                            sessionId,
                            AppSessionEvent.ModelSelectionConfirmed(newModelId.value),
                        )
                    }.launchIn(scope)
        }

        if (session.configOptionsSupported) {
            jobs +=
                session.configOptions
                    .drop(1)
                    .onEach { newOptions ->
                        emitToBridge(
                            sessionId,
                            AppSessionEvent.ConfigOptionsUpdated(
                                options = newOptions.map(AcpSessionUpdateMapper::mapSdkConfigOption),
                            ),
                        )
                    }.launchIn(scope)
        }

        if (jobs.isNotEmpty()) {
            observerJobs[sessionId] = jobs
        }
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

    /**
     * Logs where an attach spent its time, to logcat (tag `FerngeistLoad`) and the
     * diagnostics RPC log. `wait` (time to the first update) and the share of `span` not
     * spent in `handling` are the agent and gateway; `handling` is this client reducing updates;
     * `tail` is the response after the last update plus committing the hydrated transcript.
     */
    private fun reportAttachTiming(
        rpc: SessionAttachRpc,
        sessionId: String,
        timing: AttachTiming,
        succeeded: Boolean,
    ) {
        val summary = timing.summary(endNanos = System.nanoTime(), succeeded = succeeded)
        runCatching { android.util.Log.i("FerngeistLoad", "${rpc.rpc} sid=$sessionId $summary") }
        orchestra.diagnosticsStore.appendRpcEntry(
            if (succeeded) RpcDirection.InboundResult else RpcDirection.InboundError,
            rpc.rpc,
            summary = summary,
        )
    }

    /** Clears all bridges, SDK sessions, pending permissions, and reactive observers. */
    private fun clearAllSessionState(closeBridges: Boolean) {
        observerJobs.values.forEach { jobs -> jobs.forEach { it.cancel() } }
        observerJobs.clear()
        sessionCwds.clear()
        sessionRegistry.clearAll(closeBridges = closeBridges)
        permissionFlow.cancelAll()
        elicitationFlow.cancelAll()
    }

    /**
     * Clears a single session: SDK session, bridge, its pending permissions, and
     * the reactive observers launched by [startReactiveObservers].
     */
    private fun clearSessionState(
        sessionId: String,
        closeBridge: Boolean,
    ) {
        observerJobs.remove(sessionId)?.forEach { it.cancel() }
        sessionCwds.remove(sessionId)
        sessionRegistry.clearSession(sessionId, closeBridge = closeBridge)
        permissionFlow.cancelForSession(sessionId)
        elicitationFlow.cancelForSession(sessionId)
    }

    /**
     * Whether the SDK session's `close()` should be invoked when it is cleared
     * from the registry. Only close sessions when the agent advertises the
     * `session/close` capability; otherwise closing would error out (the method
     * is not implemented by the agent) and leave the agent-side session running.
     */
    @OptIn(UnstableApi::class)
    private fun shouldCloseSdkSession(): Boolean =
        orchestra.agentCapabilities.value
            ?.sessionCapabilities
            ?.close != null

    /** Returns a loaded session port if the SDK session exists in the registry. */
    private fun getLoadedSession(sessionId: String): SessionPort? {
        if (!sessionRegistry.hasSdkSession(sessionId)) return null
        return sessionRegistry.getPort(sessionId)
    }

    /**
     * Checks whether an error means the session is already loaded on the agent.
     *
     * Agents surface this differently — a [JsonRpcException] with a provider-specific
     * code, a standard INVALID_PARAMS code, or a message-only exception — so the
     * wordings the bridged agents use when they still hold the session are matched
     * anywhere in the cause chain: "already loaded", "already active" (the harness has
     * it in memory) and "active write handle" (another handle owns its store).
     *
     * The JSON-RPC code alone is deliberately *not* evidence. INVALID_PARAMS is the
     * protocol's generic "invalid params", returned just as readily for a bad cwd, an
     * unknown session id or a malformed request; reading it as "held" replaced those
     * users' real cause with the disconnect instruction. An unrecognised refusal is
     * relayed as the agent worded it, which keeps the cause visible. The cost is a
     * missed classification for an agent that signals a held session with a bare code
     * and unrecognised wording: that degrades to the generic load failure instead of
     * inventing a cause the evidence does not support. For gateway sessions the gateway
     * recovers from this transparently; this remains the fallback for direct (Manual)
     * connections to an agent that keeps the session loaded.
     */
    internal fun isSessionAlreadyLoadedError(error: Throwable): Boolean =
        generateSequence(error as Throwable?) { it.cause }.any { cause ->
            SESSION_ACTIVE_MARKERS.any { marker ->
                // Details ride in the JSON-RPC `data` field, so match the
                // formatted text ("Internal error: {"details":"…"}") rather than
                // `message` alone.
                formatAcpErrorMessage(cause, "").contains(marker, ignoreCase = true)
            }
        }

    /**
     * Inspects a session/cancel failure to determine whether the server
     * genuinely doesn't support the method (as opposed to a network error),
     * and updates the diagnostics flag accordingly.
     */
    private fun handleSessionCancelFailure(error: Throwable) {
        val rpcError = error as? JsonRpcException
        // Only the code is evidence. The method identity is not in dispute: this is the
        // session/cancel call site. Requiring "session/cancel" to appear in the message
        // added nothing but a false negative — a legal bare `-32601 Method not found`
        // left the flag unset and the user with a Cancel button that cannot work.
        val unsupported = rpcError?.code == JsonRpcErrorCode.METHOD_NOT_FOUND.code
        if (unsupported) {
            orchestra.diagnosticsStore.setSessionCancelSupport(isSupported = false)
        }
        orchestra.diagnosticsStore.appendError(
            "session/cancel",
            formatAcpErrorMessage(error, "Cancel failed"),
        )
    }

    /** Converts a Ferngeist [SessionConfigValue] to the SDK's wire format. */
    @OptIn(UnstableApi::class)
    private fun SessionConfigValue.toSdkValue(): SessionConfigOptionValue =
        when (this) {
            is SessionConfigValue.StringValue -> SessionConfigOptionValue.of(value)
            is SessionConfigValue.BoolValue -> SessionConfigOptionValue.of(value)
            is SessionConfigValue.UnknownValue -> error("Unsupported config option value: $this")
        }
}

/**
 * Wordings agents use when they refuse an attach because they still hold the
 * session — the ACP harness says "session is already active", its session store
 * says "already owned by an active write handle".
 */
private val SESSION_ACTIVE_MARKERS = listOf("already loaded", "already active", "active write handle")

/**
 * Splits one `session/load` (or `session/resume`) into time spent waiting on the agent and
 * gateway versus time spent handling replayed updates here. Updates for one session arrive
 * sequentially, so [record] needs no locking.
 */
internal class AttachTiming(
    private val startNanos: Long = System.nanoTime(),
) {
    private var updates = 0
    private var firstNanos = 0L
    private var lastNanos = 0L
    private var handlingNanos = 0L

    fun record(
        receivedNanos: Long,
        handledNanos: Long,
    ) {
        if (updates == 0) firstNanos = receivedNanos
        updates++
        lastNanos = handledNanos
        handlingNanos += handledNanos - receivedNanos
    }

    fun summary(
        endNanos: Long,
        succeeded: Boolean,
    ): String {
        fun ms(nanos: Long) = nanos / NANOS_PER_MS
        val outcome = if (succeeded) "ok" else "failed"
        if (updates == 0) return "$outcome total=${ms(endNanos - startNanos)}ms updates=0"
        return "$outcome total=${ms(endNanos - startNanos)}ms " +
            "wait=${ms(firstNanos - startNanos)}ms span=${ms(lastNanos - firstNanos)}ms " +
            "handling=${ms(handlingNanos)}ms tail=${ms(endNanos - lastNanos)}ms updates=$updates"
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
