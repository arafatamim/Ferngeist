package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.core.model.ChatAudioData
import com.tamimarafat.ferngeist.core.model.ChatElicitationValue
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatPromptResourceLink
import com.tamimarafat.ferngeist.core.model.SteerOutcome
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public interface for the chat layer to interact with an ACP session.
 *
 * SessionPort exposes the snapshot and the user-facing operations (send,
 * cancel, config, permissions) without revealing implementation details.
 * The transport layer (AcpConnection) creates and owns implementations
 * of this interface; feature modules consume it without importing concrete
 * bridge classes.
 *
 * Implemented by [SessionBridge].
 */
@Suppress("ComplexInterface")
interface SessionPort {
    val sessionId: String
    val snapshot: StateFlow<SessionSnapshot>

    /**
     * Stream of [ModelSelectionConfirmed] events emitted by the session bridge.
     *
     * This is a narrow, purpose-built flow — it does not expose the full
     * event stream.
     */
    val modelSelectionEvents: SharedFlow<AppSessionEvent.ModelSelectionConfirmed>

    /**
     * Sends a prompt message to the agent. Optionally includes inline image data. [clientId] names
     * the prompt for the gateway's exactly-once delivery and for matching its echo; null sends it
     * unnamed.
     */
    suspend fun sendPrompt(
        text: String,
        images: List<ChatImageData> = emptyList(),
        files: List<ChatFileData> = emptyList(),
        clientId: String? = null,
    )

    /** Sends audio and resource links alongside the legacy prompt payload. */
    @Suppress("LongParameterList")
    suspend fun sendPrompt(
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
        clientId: String?,
        audios: List<ChatAudioData>,
        resourceLinks: List<ChatPromptResourceLink>,
    ) = sendPrompt(text, images, files, clientId)

    /** Cancels the current agent turn (streaming). */
    suspend fun cancel()

    /** Steers a prompt into the running turn; the transcript only changes when it is taken. */
    suspend fun steer(
        text: String,
        images: List<ChatImageData> = emptyList(),
        files: List<ChatFileData> = emptyList(),
        clientId: String? = null,
    ): SteerOutcome

    /** Steers audio and resource links alongside the legacy prompt payload. */
    @Suppress("LongParameterList")
    suspend fun steer(
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
        clientId: String?,
        audios: List<ChatAudioData>,
        resourceLinks: List<ChatPromptResourceLink>,
    ): SteerOutcome = steer(text, images, files, clientId)

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

    suspend fun submitElicitation(
        key: String,
        values: Map<String, ChatElicitationValue>,
    )

    suspend fun declineElicitation(key: String)

    suspend fun cancelElicitation(key: String)
}
