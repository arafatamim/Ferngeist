package com.tamimarafat.ferngeist.acp.bridge.connection.callbacks

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.CompleteElicitationNotification
import com.agentclientprotocol.model.CreateElicitationRequest
import com.agentclientprotocol.model.CreateElicitationResponse
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpDiagnosticsStore
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpSessionUpdateMapper
import com.tamimarafat.ferngeist.acp.bridge.connection.AttachTiming
import com.tamimarafat.ferngeist.acp.bridge.connection.catchup.updateSeq
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPermissionOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonElement

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
 *   forwards it to the bridge with the gateway's number for it.
 */
internal class BridgeSessionOperations(
    private val sessionId: String,
    private val permissionFlow: PermissionFlow,
    private val elicitationFlow: ElicitationFlow,
    private val diagnosticsStore: AcpDiagnosticsStore,
    private val attachTiming: () -> AttachTiming?,
    private val emit: suspend (event: AppSessionEvent, updateSeq: Long?) -> Unit,
) : ClientSessionOperations {
    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
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

        // The request often carries the only copy of the call's diff and input, so it is
        // applied as an update before the permission prompt.
        AcpSessionUpdateMapper.mapSessionUpdateToEvent(toolCall)?.let { emit(it, null) }
        emit(
            AppSessionEvent.ToolPermissionRequested(
                toolCallId = toolId,
                requestId = toolId,
                title = toolCall.title,
                options = options,
            ),
            null,
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
            diagnosticsStore.appendError(
                "elicitation/create",
                "Unsupported elicitation (request-scoped); answered cancel.",
            )
            return ElicitationMappers.cancelResponse()
        }
        val deferred = CompletableDeferred<CreateElicitationResponse>()
        elicitationFlow.addPending(key = domain.key, sessionId = sessionId, deferred = deferred)
        emit(AppSessionEvent.ElicitationRequested(domain), null)
        try {
            return deferred.await()
        } catch (error: CancellationException) {
            // Transport reset or teardown cancelled the deferred without resolving it
            // (detach/clear paths cancel rather than answer). Clear the sheet so it
            // cannot linger past the connection that owned it, then rethrow so the
            // agent sees the elicitation die with its turn.
            elicitationFlow.takePending(domain.key)
            emit(AppSessionEvent.ElicitationResolved(domain.key), null)
            throw error
        }
    }

    @OptIn(UnstableApi::class)
    override suspend fun completeElicitation(notification: CompleteElicitationNotification) {
        emit(AppSessionEvent.ElicitationCompleted(notification.elicitationId.value), null)
    }

    override suspend fun notify(
        notification: SessionUpdate,
        _meta: JsonElement?,
    ) {
        val receivedNanos = System.nanoTime()
        try {
            // mapSessionUpdateToEvent covers known SessionUpdate subtypes; null
            // is defensive for unrecognized SDK types — skip silently.
            val appEvent = AcpSessionUpdateMapper.mapSessionUpdateToEvent(notification) ?: return
            emit(appEvent, updateSeq(notification))
        } finally {
            attachTiming()?.record(receivedNanos, System.nanoTime())
        }
    }
}
