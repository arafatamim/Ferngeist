package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus
import com.tamimarafat.ferngeist.core.model.QueuedPromptRecord
import com.tamimarafat.ferngeist.core.model.SteerOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the durable prompt queue and the pending-message bubbles it mirrors into
 * [ChatState.pendingMessages], so [ChatViewModel] only routes send/retry intents here.
 */
internal class ChatOfflineQueueController(
    private val scope: CoroutineScope,
    private val coordinator: ChatSessionCoordinator,
    private val persistence: ChatPromptPersistence,
    private val state: StateFlow<ChatState>,
    private val update: (ChatState.() -> ChatState) -> Unit,
) {
    /** Queue of locally-created prompts that have not been delivered to the server yet. */
    private val offlineQueue = OfflineQueue()

    /** Restores [offlineQueue] from [store] at most once per view model. */
    private var pendingPromptsRestored = false

    /** Serializes [persistQueue] writes so a slower, older snapshot cannot land last. */
    private val persistMutex = Mutex()

    /** The clientId of the prompt currently being dispatched via [flushOfflineQueue].
     *  Used to wire the operation-error callback back to the specific SENDING bubble. */
    private var inFlightClientId: String? = null

    /** Guards [flushOfflineQueue] so overlapping sessionReady/retry calls cannot interleave. */
    private val flushMutex = Mutex()
    private var reconnectKickJob: Job? = null

    /**
     * Marks the prompt currently being dispatched FAILED after an operation error, so it
     * stays visible for manual retry.
     */
    fun failInFlightPrompt() {
        val clientId = inFlightClientId ?: return
        inFlightClientId = null
        update {
            val updated =
                pendingMessages.map { msg ->
                    if (msg.clientId == clientId &&
                        msg.status == MessageDeliveryStatus.SENDING
                    ) {
                        msg.copy(status = MessageDeliveryStatus.FAILED)
                    } else {
                        msg
                    }
                }
            copy(pendingMessages = updated)
        }
    }

    /**
     * Restores the prompts a previous screen lifetime persisted (see [persistQueue]) into
     * [offlineQueue] and [ChatState.pendingMessages], then gives them a path forward: an
     * immediate flush when the session is already live, otherwise the reconnect the offline
     * enqueue path uses, so a restored prompt stays QUEUED until the session is ready
     * instead of being marked FAILED by a flush that cannot dispatch it yet.
     *
     * Guarded so a second call cannot duplicate the queue.
     */
    suspend fun restorePendingPrompts() {
        if (pendingPromptsRestored) return
        pendingPromptsRestored = true
        val records = persistence.restore()
        if (records.isEmpty()) return
        records.forEach { record ->
            offlineQueue.enqueue(
                PendingPrompt(
                    clientId = record.clientId,
                    text = record.text,
                    images = record.images,
                    files = record.files,
                ),
            )
            val message =
                ChatMessage(
                    id = record.clientId,
                    role = ChatMessage.Role.USER,
                    content = record.text,
                    images = record.images,
                    files = record.files,
                    status = MessageDeliveryStatus.QUEUED,
                    clientId = record.clientId,
                )
            update { copy(pendingMessages = pendingMessages + message) }
        }
        val canSendNow =
            state.value.isSessionReady && state.value.connectionState == ChatConnectionState.Connected
        if (canSendNow) {
            flushOfflineQueue()
        } else {
            kickReconnect()
        }
    }

    /**
     * Persists [offlineQueue] for this chat so a screen teardown cannot silently drop a
     * queued prompt. The snapshot is taken at write time under [persistMutex], so an older
     * write can never land after a newer one.
     */
    suspend fun persistQueue() {
        persistMutex.withLock {
            val records =
                offlineQueue.snapshot().map {
                    QueuedPromptRecord(it.clientId, it.text, it.images, it.files)
                }
            persistence.save(records)
        }
    }

    /**
     * Optimistically adds a user bubble with [MessageDeliveryStatus.QUEUED] and
     * stores the prompt in [offlineQueue] for later delivery.
     *
     * Suspends only for the queue persist; the sole caller is the suspend intent
     * dispatch path, so no signature ripples.
     */
    suspend fun enqueuePrompt(
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
    ) {
        if (text.isBlank() && images.isEmpty() && files.isEmpty()) return
        val clientId =
            java.util.UUID
                .randomUUID()
                .toString()
        val message =
            ChatMessage(
                id = clientId,
                role = ChatMessage.Role.USER,
                content = text,
                images = images,
                files = files,
                status = MessageDeliveryStatus.QUEUED,
                clientId = clientId,
            )
        offlineQueue.enqueue(
            PendingPrompt(
                clientId = clientId,
                text = text,
                images = images,
                files = files,
            ),
        )
        update {
            copy(pendingMessages = pendingMessages + message)
        }
        if (state.value.connectionState != ChatConnectionState.Connected) {
            kickReconnect()
        }
        persistQueue()
    }

    /**
     * Ensures a single reconnect attempt is in flight so a prompt queued while
     * offline has a path forward. The next enqueue re-triggers once this settles.
     */
    private fun kickReconnect() {
        if (reconnectKickJob?.isActive == true) return
        reconnectKickJob = scope.launch { coordinator.reconnect() }
    }

    /**
     * Enqueues the prompt then immediately tries to send it while the session is ready.
     * Used by the view model's intent handling for the online-send path so the offline
     * queue is always the source of truth and the flush path is uniform.
     */
    suspend fun enqueueThenSend(
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
    ) {
        if (text.isBlank() && images.isEmpty() && files.isEmpty()) return
        val clientId =
            java.util.UUID
                .randomUUID()
                .toString()
        val message =
            ChatMessage(
                id = clientId,
                role = ChatMessage.Role.USER,
                content = text,
                images = images,
                files = files,
                status = MessageDeliveryStatus.QUEUED,
                clientId = clientId,
            )
        val prompt =
            PendingPrompt(
                clientId = clientId,
                text = text,
                images = images,
                files = files,
            )
        offlineQueue.enqueue(prompt)
        persistQueue()
        update {
            copy(pendingMessages = pendingMessages + message)
        }
        flushOfflineQueue()
    }

    /**
     * Drains [offlineQueue] in FIFO order under [flushMutex], one prompt per turn.
     *
     * Each send first waits for the agent to be idle. The mutex alone only covered
     * turns this view model started: a screen re-entered mid-turn (or a restored queue)
     * sent its prompt straight into the running turn. An agent that takes prompts
     * mid-turn is the exception: it gets each one at once (see [steerIntoTurn]).
     *
     * Each prompt transitions QUEUED -> SENDING *without* being removed from
     * [ChatState.pendingMessages].  The pending bubble stays visible until:
     * - The reducer echo arrives via the snapshot path (SENT: the echo replaces it), or
     * - The send returns false (FAILED: no bridge), or
     * - An operation error fires while the prompt is in-flight (FAILED).
     */
    suspend fun flushOfflineQueue() {
        flushMutex.withLock {
            while (!offlineQueue.isEmpty) {
                val midTurn = state.first { !it.isStreaming || it.supportsSteering }.isStreaming
                val prompt = offlineQueue.dequeue() ?: break
                if (midTurn) steerIntoTurn(prompt) else startTurn(prompt)
            }
        }
    }

    /**
     * Sends [prompt]. The send lasts the whole turn, so for a steering agent the flush is held
     * only until the turn is under way: its next prompt must go into that turn, not behind it.
     */
    private suspend fun startTurn(prompt: PendingPrompt) {
        if (!state.value.supportsSteering) return sendWhenIdle(prompt)
        state.awaitTurnStartedOr(scope.launch { sendWhenIdle(prompt) })
    }

    private suspend fun sendWhenIdle(prompt: PendingPrompt) {
        // Transition QUEUED -> SENDING, keep the bubble visible.
        inFlightClientId = prompt.clientId
        setPendingStatus(prompt.clientId, MessageDeliveryStatus.QUEUED, MessageDeliveryStatus.SENDING)
        val dispatched = coordinator.sendMessage(prompt.text, prompt.images, prompt.files)
        inFlightClientId = null
        if (!dispatched) {
            // No bridge / not ready / unsupported -> mark FAILED immediately. The facade
            // has already reported the cause; a second generic error would replace it.
            setPendingStatus(prompt.clientId, MessageDeliveryStatus.SENDING, MessageDeliveryStatus.FAILED)
        }
        // Only now is the durable copy dropped, and deliberately not from a
        // finally: until the send resolves the stored snapshot still contains
        // this prompt, so a screen teardown mid-send (which cancels
        // sendMessage and skips this line) restores it as QUEUED rather than
        // losing it. The cost is that a process death between a successful
        // send and this write can re-send one prompt after restart
        // (at-least-once); the record carries its clientId, so an echo-based
        // dedupe could tighten that later if it ever matters.
        persistQueue()
    }

    /**
     * Hands a prompt to the running turn; the agent feeds it to the model at its next
     * step. SENDING lets the runtime's local copy reconcile the bubble away as a normal
     * send's echo does. A prompt the agent does not take goes back to the front and
     * waits for the turn to end, so a refusal cannot spin the flush loop.
     */
    private suspend fun steerIntoTurn(prompt: PendingPrompt) {
        setPendingStatus(prompt.clientId, MessageDeliveryStatus.QUEUED, MessageDeliveryStatus.SENDING)
        val outcome = coordinator.steerMessage(prompt.text, prompt.images, prompt.files)
        if (outcome == SteerOutcome.NOT_CONSUMED) {
            offlineQueue.enqueueFirst(prompt)
            setPendingStatus(prompt.clientId, MessageDeliveryStatus.SENDING, MessageDeliveryStatus.QUEUED)
            persistQueue()
            state.first { !it.isStreaming }
        } else {
            persistQueue()
        }
    }

    /**
     * Stops the running turn and makes [clientId]'s prompt the next one, folded together with
     * every prompt queued ahead of it so they all go at once. Prompts behind it keep waiting.
     * It keeps [clientId]'s bubble, whose content becomes the folded prompt the echo will carry.
     */
    suspend fun forceSend(clientId: String) {
        val (merged, folded) = offlineQueue.foldThrough(clientId) ?: return
        update { copy(pendingMessages = pendingMessages.foldedInto(merged, folded)) }
        persistQueue()
        if (state.value.isStreaming) coordinator.cancelStreaming()
        // Normally a flush is already waiting on the turn; this covers one that is not.
        flushOfflineQueue()
    }

    /** Drops a prompt the user no longer wants sent. One already in flight cannot be recalled. */
    suspend fun removeQueuedMessage(clientId: String) {
        val pending = state.value.pendingMessages.firstOrNull { it.clientId == clientId } ?: return
        if (pending.status == MessageDeliveryStatus.SENDING) return
        offlineQueue.removeByClientId(clientId)
        update { copy(pendingMessages = pendingMessages.filterNot { it.clientId == clientId }) }
        persistQueue()
    }

    /** Moves [clientId]'s pending bubble from [from] to [to]; any other status stays. */
    private fun setPendingStatus(
        clientId: String,
        from: MessageDeliveryStatus,
        to: MessageDeliveryStatus,
    ) = update { copy(pendingMessages = pendingMessages.withStatus(clientId, from, to)) }

    /** Retries a single FAILED message by re-enqueueing it and flushing.
     *  Other QUEUED prompts are preserved in FIFO order. */
    suspend fun retryMessage(clientId: String) {
        val pendingMessage = state.value.pendingMessages.firstOrNull { it.clientId == clientId }
        if (pendingMessage == null || pendingMessage.status != MessageDeliveryStatus.FAILED) return

        val prompt =
            PendingPrompt(
                clientId = clientId,
                text = pendingMessage.content,
                images = pendingMessage.images,
                files = pendingMessage.files,
            )
        // Remove existing queue entry for this clientId, then enqueue at the back.
        offlineQueue.removeByClientId(clientId)
        offlineQueue.enqueue(prompt)
        persistQueue()
        // Reset the bubble's status from FAILED to QUEUED so flushOfflineQueue
        // can transition it QUEUED -> SENDING and the echo-reconcile in
        // applySnapshot can remove it on delivery confirmation.
        setPendingStatus(clientId, MessageDeliveryStatus.FAILED, MessageDeliveryStatus.QUEUED)
        flushOfflineQueue()
    }
}

/**
 * The durable copy of one chat's queue, keyed by the chat's real session id. Nothing is read
 * or written while the chat still has no id (a create-on-arrival chat before the facade mints one).
 */
internal class ChatPromptPersistence(
    private val store: PendingPromptStore,
    private val serverId: String,
    private val durableSessionId: () -> String?,
) {
    suspend fun restore(): List<QueuedPromptRecord> {
        val queueId = durableSessionId() ?: return emptyList()
        return store.restore(serverId, queueId)
    }

    suspend fun save(records: List<QueuedPromptRecord>) {
        val queueId = durableSessionId() ?: return
        store.save(serverId, queueId, records)
    }
}

/** Suspends until a turn is streaming or [send] has finished, whichever comes first. */
private suspend fun StateFlow<ChatState>.awaitTurnStartedOr(send: Job) {
    val sendDone =
        flow {
            emit(false)
            send.join()
            emit(true)
        }
    combine(this, sendDone) { current, done -> current.isStreaming || done }.first { it }
}

private fun List<ChatMessage>.withStatus(
    clientId: String,
    from: MessageDeliveryStatus,
    to: MessageDeliveryStatus,
): List<ChatMessage> = map { if (it.clientId == clientId && it.status == from) it.copy(status = to) else it }

/** Gives [merged]'s bubble the folded content and drops the bubbles of the prompts it [folded] in. */
private fun List<ChatMessage>.foldedInto(
    merged: PendingPrompt,
    folded: Set<String>,
): List<ChatMessage> =
    mapNotNull {
        when (it.clientId) {
            merged.clientId -> it.copy(content = merged.text, images = merged.images, files = merged.files)
            in folded -> null
            else -> it
        }
    }
