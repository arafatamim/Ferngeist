package com.tamimarafat.ferngeist.acp.bridge.session

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public interface for the chat layer to interact with an ACP session.
 *
 * SessionPort exposes the snapshot and the user-facing operations (send,
 * cancel, config, permissions) without revealing implementation details.
 * The transport layer (AcpConnectionManager) creates and owns implementations
 * of this interface; feature modules consume it without importing concrete
 * bridge classes.
 *
 * Implemented by [SessionBridge].
 */
interface SessionPort {
    val sessionId: String
    val snapshot: StateFlow<SessionSnapshot>

    /**
     * Stream of [ModelSelectionConfirmed] events emitted by the session bridge.
     *
     * This is a narrow, purpose-built flow — it does not expose the full
     * event stream. Non-ACP implementations may return null if they don't
     * separately track model selection confirmations (the snapshot already
     * carries the current model value).
     */
    val modelSelectionEvents: SharedFlow<AppSessionEvent.ModelSelectionConfirmed>?

    /** Sends a prompt message to the agent. Optionally includes inline image data. */
    suspend fun sendPrompt(
        text: String,
        images: List<Pair<String, String>> = emptyList(),
    )

    /** Cancels the current agent turn (streaming). */
    suspend fun cancel()

    /** Updates a configuration option value on the session. */
    suspend fun setConfigOption(
        optionId: String,
        value: SessionConfigValue,
    )

    suspend fun grantPermission(
        toolCallId: String,
        optionId: String,
    )

    suspend fun denyPermission(toolCallId: String)
}
