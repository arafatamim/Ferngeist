package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.core.model.ChatElicitationRequest

internal fun mapUsage(event: AppSessionEvent.UsageUpdated): SessionUsage =
    SessionUsage(
        promptTokens = event.promptTokens,
        completionTokens = event.completionTokens,
        totalTokens = event.totalTokens,
        cachedReadTokens = event.cachedReadTokens,
        contextWindowTokens = event.contextWindowTokens,
        costAmount = event.costAmount,
        costCurrency = event.costCurrency,
    )

internal fun confirmModeChanged(
    legacyModes: LegacyModeState?,
    modeId: String,
): LegacyModeState {
    val base = legacyModes ?: LegacyModeState()
    return base.copy(currentModeId = modeId)
}

internal fun mapModes(
    event: AppSessionEvent.ModesUpdated,
    legacyModes: LegacyModeState?,
): LegacyModeState =
    LegacyModeState(
        modes = event.modes,
        currentModeId = event.currentModeId ?: legacyModes?.currentModeId,
    )

internal fun mapLegacyModel(event: AppSessionEvent.LegacyModelOptionsUpdated): LegacyModelState =
    LegacyModelState(
        choices = event.choices,
        currentModelId = event.currentModelId,
    )

/** Mutates config options via the central policy for type-safe value changes. */
internal fun applyConfigValueChange(
    options: List<SessionConfigOption>,
    event: AppSessionEvent.ConfigOptionValueChanged,
): List<SessionConfigOption> =
    options.map { option ->
        SessionConfigPolicy.applyValueChange(option, event.optionId, event.value)
    }

/** Confirms the selected model id on the legacy model state, if a model set exists. */
internal fun confirmModelSelection(
    legacyModel: LegacyModelState?,
    modelId: String?,
): LegacyModelState? {
    if (modelId.isNullOrBlank() || legacyModel == null) return legacyModel
    return legacyModel.copy(currentModelId = modelId)
}

/**
 * Debug-only one-line summary of an event. Exhaustive `when` over the sealed
 * [AppSessionEvent] hierarchy — one branch per event type, no branching logic,
 * so cyclomatic complexity equals the type count and cannot be meaningfully
 * reduced without losing exhaustiveness.
 */
@Suppress("CyclomaticComplexMethod")
internal fun summarizeEvent(event: AppSessionEvent): String =
    when (event) {
        is AppSessionEvent.UserMessage -> "userLen=${event.text.length} append=${event.append}"
        is AppSessionEvent.AgentMessage -> "agentLen=${event.text.length}"
        is AppSessionEvent.AgentThought -> "thoughtLen=${event.text.length}"
        is AppSessionEvent.ToolCallStarted -> "toolCallId=${event.toolCallId} status=${event.status}"
        is AppSessionEvent.ToolCallUpdated -> "toolCallId=${event.toolCallId} status=${event.status}"
        is AppSessionEvent.ToolPermissionRequested -> "toolCallId=${event.toolCallId} options=${event.options.size}"
        is AppSessionEvent.ToolPermissionResolved -> "toolCallId=${event.toolCallId}"
        is AppSessionEvent.ElicitationRequested -> "elicitationKey=${event.request.key}"
        is AppSessionEvent.ElicitationResolved -> "elicitationKey=${event.key}"
        is AppSessionEvent.ElicitationCompleted -> "elicitationId=${event.elicitationId}"
        is AppSessionEvent.ModeChanged -> "modeId=${event.modeId}"
        is AppSessionEvent.ModesUpdated -> "modes=${event.modes.size} current=${event.currentModeId}"
        is AppSessionEvent.ConfigOptionsUpdated -> "configOptions=${event.options.size}"
        is AppSessionEvent.ConfigOptionValueChanged -> "optionId=${event.optionId} value=${event.value}"
        is AppSessionEvent.LegacyModelOptionsUpdated ->
            "legacyModels=${event.choices.size} current=${event.currentModelId}"
        is AppSessionEvent.ModelSelectionConfirmed -> "modelId=${event.modelId}"
        is AppSessionEvent.PlanUpdated -> "entries=${event.entries.size}"
        is AppSessionEvent.UsageUpdated -> "usageTotal=${event.totalTokens} context=${event.contextWindowTokens}"
        is AppSessionEvent.CommandsUpdated -> "commands=${event.commands.size}"
        is AppSessionEvent.SessionInfoUpdated -> "title=${event.title} updatedAt=${event.updatedAt}"
        is AppSessionEvent.SessionLoadComplete -> "sessionLoadComplete"
        is AppSessionEvent.TurnComplete -> "stopReason=${event.stopReason}"
        is AppSessionEvent.Unknown -> "unknownRawLen=${event.raw.length}"
    }

/**
 * First-class pending list, unlike permissions which ride on tool-call segments:
 * elicitations may arrive without any tool call, so there is no segment to attach to.
 */
internal fun applyElicitationUpdate(
    current: List<ChatElicitationRequest>,
    event: AppSessionEvent,
): List<ChatElicitationRequest> =
    when (event) {
        is AppSessionEvent.ElicitationRequested ->
            (current.filterNot { it.key == event.request.key } + event.request)
        is AppSessionEvent.ElicitationResolved ->
            current.filterNot { it.key == event.key }
        else -> current
    }
