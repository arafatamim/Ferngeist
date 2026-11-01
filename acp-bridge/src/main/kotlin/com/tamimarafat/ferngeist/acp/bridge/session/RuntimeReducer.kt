package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.TurnTiming

/**
 * Pure function that reduces an event onto the current state.
 *
 * Message list changes are delegated to [SessionMessageReducer.handleEvent].
 * Side-field updates (usage, commands, config options, legacy mode/model) are handled
 * directly here. The branching is straightforward: each event type updates exactly one
 * field or a small related group.
 *
 * Config option values go through [SessionConfigPolicy.applyValueChange], which owns origin handling.
 */
internal fun reduce(
    current: RuntimeData,
    event: AppSessionEvent,
    nowMs: Long?,
    eventSeq: Long,
): RuntimeData {
    val transcript = reduceTranscript(current, event, nowMs)
    val fields = applySideFieldUpdates(current, event)

    // TurnComplete / TurnEndUnheard end the streaming turn.
    val endsTurn = event is AppSessionEvent.TurnComplete || event is AppSessionEvent.TurnEndUnheard
    // Only the trailing bubble can be mid-stream: finishStreaming clears the flag on
    // every message it closes, and a new streaming bubble is always appended last.
    // A full scan is O(messages) and this runs per event.
    val derivedStreaming =
        (current.isStreaming && !endsTurn) || transcript.messages.lastOrNull()?.isStreaming == true
    return current.copy(
        messages = transcript.messages,
        toolCallIndex = transcript.toolCallIndex,
        isStreaming = derivedStreaming,
        usage = fields.usage,
        availableCommands = fields.availableCommands,
        commandsAdvertised = fields.commandsAdvertised,
        nativeConfigOptions = fields.nativeConfigOptions,
        legacyModes = fields.legacyModes,
        legacyModel = fields.legacyModel,
        title = fields.title,
        // A turn that has ended cannot still be waiting on the user: a request it left
        // behind is unanswerable, so it must not keep the session in attention across a
        // reconnect (the agent may never send its resolve event after a drop). Clearing on
        // a turn end only - not on a load replay's close - keeps a request the agent
        // raises while a load runs answerable.
        pendingElicitations =
            if (event is AppSessionEvent.TurnComplete) {
                emptyList()
            } else {
                applyElicitationUpdate(current.pendingElicitations, event)
            },
        pendingPermissions =
            if (event is AppSessionEvent.TurnComplete) {
                emptySet()
            } else {
                pendingPermissionsAfter(current.pendingPermissions, event)
            },
        turnOutcome = turnOutcomeAfter(current.turnOutcome, event, current.isStreaming),
        turnSettled = current.turnSettled || event is AppSessionEvent.TurnComplete,
        turnCompletionSequence =
            if (event is AppSessionEvent.TurnComplete) eventSeq else current.turnCompletionSequence,
    )
}

/** Folds one event into the transcript: a stop's tail, the reducer, and the turn-close fold. */
private fun reduceTranscript(
    current: RuntimeData,
    event: AppSessionEvent,
    nowMs: Long?,
): ReducerResult {
    // Text arriving after a stop would otherwise land in the stopped reply, and its
    // turn-close fold would glue it onto that reply's last sentence.
    val stoppedReply = current.messages.lastOrNull()?.takeIf { it.id == current.stoppedAt }
    val opensReply =
        stoppedReply?.segments?.isNotEmpty() == true &&
            (
                event is AppSessionEvent.AgentMessage ||
                    event is AppSessionEvent.AgentThought ||
                    event is AppSessionEvent.PlanUpdated
            )
    val opened = if (opensReply) SessionMessageReducer.startStreaming(current.messages) else current.messages

    // TurnEndUnheard only flips isStreaming; everything else goes through the reducer,
    // which owns both the message list and the tool-call index
    val fromReducer =
        when (event) {
            is AppSessionEvent.TurnEndUnheard -> null
            else -> SessionMessageReducer.handleEvent(opened, current.toolCallIndex, event, nowMs)
        }
    val reduced = fromReducer ?: ReducerResult(opened, current.toolCallIndex)
    if (fromReducer == null || current.stoppedAt != null) {
        // ponytail: re-folds the trailing reply per late event; fine for a stop's short tail.
        return reduced.copy(messages = SessionMessageReducer.finishStreaming(reduced.messages))
    }
    return reduced
}

/**
 * Applies side-field updates (usage, commands, config options, legacy mode/model)
 * for a single event. Returns the new field values; unchanged fields carry through.
 */
private fun applySideFieldUpdates(
    current: RuntimeData,
    event: AppSessionEvent,
): SideFields {
    var usage = current.usage
    var availableCommands = current.availableCommands
    var commandsAdvertised = current.commandsAdvertised
    var nativeConfigOptions = current.nativeConfigOptions
    var legacyModes = current.legacyModes
    var legacyModel = current.legacyModel
    var title = current.title

    when (event) {
        is AppSessionEvent.TurnComplete -> Unit
        is AppSessionEvent.TurnEndUnheard -> Unit
        is AppSessionEvent.UsageUpdated -> usage = mapUsage(event)
        is AppSessionEvent.CommandsUpdated -> {
            availableCommands = event.commands
            commandsAdvertised = true
        }
        is AppSessionEvent.ModeChanged -> legacyModes = confirmModeChanged(legacyModes, event.modeId)
        is AppSessionEvent.ModesUpdated -> legacyModes = mapModes(event, legacyModes)
        is AppSessionEvent.ConfigOptionsUpdated -> nativeConfigOptions = event.options
        is AppSessionEvent.ConfigOptionValueChanged -> {
            nativeConfigOptions = applyConfigValueChange(nativeConfigOptions, event)
        }
        is AppSessionEvent.LegacyModelOptionsUpdated -> legacyModel = mapLegacyModel(event)
        is AppSessionEvent.ModelSelectionConfirmed -> {
            legacyModel = confirmModelSelection(legacyModel, event.modelId)
        }
        is AppSessionEvent.SessionInfoUpdated -> title = event.title ?: title
        else -> Unit
    }
    return SideFields(
        usage = usage,
        availableCommands = availableCommands,
        commandsAdvertised = commandsAdvertised,
        nativeConfigOptions = nativeConfigOptions,
        legacyModes = legacyModes,
        legacyModel = legacyModel,
        title = title,
    )
}

/**
 * Every path that closes a reply (turn end, cancel, a failed send, a steer, a new prompt)
 * publishes, so stamping there catches them all without each one knowing about time. The turn
 * runs from its prompt's send; replayed history never streams here, so it is never stamped.
 * The gateway's duration wins over the wall clock: a dozing phone hears the end late.
 */
internal fun stampClosedReply(
    data: RuntimeData,
    wallClock: () -> Long,
): RuntimeData {
    val messages = data.messages
    val index = data.openReplyId?.let { id -> messages.indexOfLast { it.id == id } } ?: -1
    val reply = messages.getOrNull(index)?.takeIf { !it.isStreaming && it.turn == null }
    val prompt = messages.subList(0, index.coerceAtLeast(0)).lastOrNull { it.role == ChatMessage.Role.USER }
    val stamped =
        if (reply != null && prompt != null) {
            val finishedAt = data.turnDurationMs?.let { prompt.createdAt + it } ?: wallClock()
            data.copy(
                messages =
                    messages.toMutableList().apply {
                        this[index] = reply.copy(turn = TurnTiming(prompt.createdAt, finishedAt))
                    },
            )
        } else {
            data
        }
    val last = stamped.messages.lastOrNull()
    return stamped.copy(turnDurationMs = null, openReplyId = if (last?.isStreaming == true) last.id else null)
}

private data class SideFields(
    val usage: SessionUsage?,
    val availableCommands: List<CommandInfo>,
    val commandsAdvertised: Boolean,
    val nativeConfigOptions: List<SessionConfigOption>,
    val legacyModes: LegacyModeState?,
    val legacyModel: LegacyModelState?,
    val title: String?,
)
