package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.core.model.TurnStatus

/**
 * Tracks which permission requests the user has not answered yet, so a live turn can be
 * shown as needing attention. The user answering one emits its resolve event.
 */
internal fun pendingPermissionsAfter(
    current: Set<String>,
    event: AppSessionEvent,
): Set<String> =
    when (event) {
        is AppSessionEvent.ToolPermissionRequested -> current + event.toolCallId
        is AppSessionEvent.ToolPermissionResolved -> current - event.toolCallId
        else -> current
    }

/** Keeps the last outcome between turns; a load restores no reliable outcome. */
internal fun turnOutcomeAfter(
    current: TurnStatus,
    event: AppSessionEvent,
): TurnStatus =
    when (event) {
        is AppSessionEvent.TurnComplete -> turnOutcomeFor(event.stopReason)
        is AppSessionEvent.SessionLoadComplete -> TurnStatus.UNKNOWN
        else -> current
    }

/**
 * The outcome a prompt stop reason establishes. `end_turn` is the only success; a refusal
 * or a turn cut off at a limit failed; a cancellation, a missing reason, or one this
 * client does not recognize proves nothing, so it stays unknown rather than reading as
 * finished.
 */
internal fun turnOutcomeFor(stopReason: String): TurnStatus =
    when (stopReason) {
        "end_turn" -> TurnStatus.FINISHED
        "refusal", "max_tokens", "max_turn_requests", AppSessionEvent.STOP_REASON_FAILED ->
            TurnStatus.ERROR
        else -> TurnStatus.UNKNOWN
    }
