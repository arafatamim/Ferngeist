package com.tamimarafat.ferngeist.acp.bridge.session.persist

import com.tamimarafat.ferngeist.acp.bridge.session.CommandInfo
import com.tamimarafat.ferngeist.acp.bridge.session.LegacyModeState
import com.tamimarafat.ferngeist.acp.bridge.session.LegacyModelState
import com.tamimarafat.ferngeist.acp.bridge.session.RuntimeData
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOption
import com.tamimarafat.ferngeist.acp.bridge.session.SessionRuntime
import com.tamimarafat.ferngeist.acp.bridge.session.SessionUsage
import com.tamimarafat.ferngeist.acp.bridge.session.ToolCallLocation
import com.tamimarafat.ferngeist.acp.bridge.session.mergedTextRuns
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.TurnStatus
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Bump when [SavedSessionState]'s shape or a field's meaning changes incompatibly: the version is
 * part of the state file's name, so an older file is never read and costs only a full load.
 */
internal const val SAVED_STATE_FORMAT_VERSION = 2

/**
 * The saved chat state's file format, so a chat draws at once after process death. Every
 * `@Serializable` type it reaches (`ChatMessage` and its parts in core/model, the session config
 * and command types here) is part of it; `SavedSessionStateFormatTest` fails when any of them
 * changes what it writes.
 *
 * Pending permissions and elicitations are not saved: the gateway re-sends them, and a restored
 * entry has nothing behind it to answer. Monotonic turn marks mean nothing in another process,
 * so a restored turn starts untimed.
 */
@Serializable
internal data class SavedSessionState(
    val messages: List<ChatMessage>,
    val isStreaming: Boolean,
    val toolCallIndex: Map<String, ToolCallLocation>,
    val usage: SessionUsage?,
    val availableCommands: List<CommandInfo>,
    val commandsAdvertised: Boolean,
    val nativeConfigOptions: List<SessionConfigOption>,
    val legacyModes: LegacyModeState?,
    val legacyModel: LegacyModelState?,
    val title: String?,
    val stoppedAt: String?,
    val turnOutcome: TurnStatus,
    val turnSettled: Boolean,
    val turnCompletionSequence: Long,
    val openReplyId: String?,
    val turnDurationMs: Long?,
    val epoch: String?,
    val lastSeq: Long,
)

// Defaults are encoded: ids and timestamps default to fresh values on decode.
private val savedStateJson = Json { encodeDefaults = true }

/** This runtime's live state as a [SavedSessionState] file's text. */
internal fun SessionRuntime.savedState(): String = savedStateJson.encodeToString(state.toSaved())

/**
 * Installs a [savedState] from an earlier process. Throws
 * [kotlinx.serialization.SerializationException] when [saved] is not a saved state.
 */
internal suspend fun SessionRuntime.restore(saved: String) {
    install(savedStateJson.decodeFromString<SavedSessionState>(saved).toRuntimeData())
}

// A reply still streaming holds one segment per chunk (see SessionMessageReducer); folded here, a
// long turn saves and restores as a few segments instead of thousands.
private fun RuntimeData.toSaved() =
    SavedSessionState(
        messages = messages.map { it.copy(segments = mergedTextRuns(it.segments)) },
        isStreaming = isStreaming,
        toolCallIndex = toolCallIndex,
        usage = usage,
        availableCommands = availableCommands,
        commandsAdvertised = commandsAdvertised,
        nativeConfigOptions = nativeConfigOptions,
        legacyModes = legacyModes,
        legacyModel = legacyModel,
        title = title,
        stoppedAt = stoppedAt,
        turnOutcome = turnOutcome,
        turnSettled = turnSettled,
        turnCompletionSequence = turnCompletionSequence,
        openReplyId = openReplyId,
        turnDurationMs = turnDurationMs,
        epoch = epoch,
        lastSeq = lastSeq,
    )

// A permission prompt is dropped: its request died with the process, and the gateway re-sends
// the ones still open.
private fun SavedSessionState.toRuntimeData() =
    RuntimeData(
        messages = messages.map { it.withoutPermissionPrompts() },
        isStreaming = isStreaming,
        toolCallIndex = toolCallIndex,
        usage = usage,
        availableCommands = availableCommands,
        commandsAdvertised = commandsAdvertised,
        nativeConfigOptions = nativeConfigOptions,
        legacyModes = legacyModes,
        legacyModel = legacyModel,
        title = title,
        stoppedAt = stoppedAt,
        turnOutcome = turnOutcome,
        turnSettled = turnSettled,
        turnCompletionSequence = turnCompletionSequence,
        openReplyId = openReplyId,
        turnDurationMs = turnDurationMs,
        epoch = epoch,
        lastSeq = lastSeq,
    )

private fun ChatMessage.withoutPermissionPrompts(): ChatMessage {
    if (segments.none { it.toolCall?.permissionRequestId != null }) return this
    return copy(
        segments =
            segments
                .map { segment ->
                    val call = segment.toolCall?.takeIf { it.permissionRequestId != null } ?: return@map segment
                    segment.copy(toolCall = call.copy(permissionOptions = null, permissionRequestId = null))
                }.toPersistentList(),
    )
}
