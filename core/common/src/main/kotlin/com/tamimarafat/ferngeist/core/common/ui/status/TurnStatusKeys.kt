package com.tamimarafat.ferngeist.core.common.ui.status

import com.tamimarafat.ferngeist.core.model.TurnStatus

/** Catalog status entries belonging to [targetId], keyed by session id ("targetId/sessionId"). */
fun Map<String, TurnStatus>.statusesFor(targetId: String): Map<String, TurnStatus> {
    val prefix = "$targetId/"
    return entries
        .filter { it.key.startsWith(prefix) }
        .associate { it.key.removePrefix(prefix) to it.value }
}

/** A turn is in flight: working, or still holding while a request waits for the user. */
val TurnStatus.isActive: Boolean
    get() = this == TurnStatus.WORKING || this == TurnStatus.NEEDS_ATTENTION
