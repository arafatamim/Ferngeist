package com.tamimarafat.ferngeist.acp.bridge.session

import kotlinx.coroutines.flow.StateFlow

/**
 * Internal state engine for a single ACP session.
 *
 * SessionStateEngine owns all transcript/session state mutations and exposes
 * a [snapshot] StateFlow for the UI to observe. It does not expose transport
 * operations (send, cancel, config) — those live on [SessionPort].
 *
 * This interface hides implementation details (Mutex, RuntimeData, buffered/live split,
 * internal reduce/publishLive methods) from the connection and bridge layers. It is
 * implemented by [SessionRuntime].
 */
interface SessionStateEngine {
    val snapshot: StateFlow<SessionSnapshot>

    suspend fun onEvent(event: AppSessionEvent)

    suspend fun onLocalPromptStarted(
        text: String,
        images: List<Pair<String, String>>,
    )

    suspend fun onPromptSendFailed()

    suspend fun onLocalCancel()

    /** Begins buffering events during session/load hydration. */
    suspend fun beginHydration()

    /** Commits buffered events to live state after session/load completes. */
    suspend fun completeHydration()

    /** Marks the session as failed with an error message. */
    suspend fun failHydration(error: String?)

    /**
     * Signals that the session is ready for additional operations.
     * Only effective when the session is not in HYDRATING state.
     */
    suspend fun markReady()
}
