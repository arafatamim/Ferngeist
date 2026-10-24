package com.tamimarafat.ferngeist.feature.sessionlist

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** What the session list's collaborators report to the screen: the busy flag and one-shot events. */
internal class SessionListOutputs {
    val loading = MutableStateFlow(true)
    val events = MutableSharedFlow<SessionListEvent>()
}
