package com.tamimarafat.ferngeist.acp.bridge.session

import android.util.Log

/**
 * Gate for the per-event trace logging on the session hot path.
 *
 * `SessionRuntime.onEvent` runs for every agent chunk. With logging on, each event pays
 * `event::class.simpleName` reflection plus interpolated strings and a `Log.d` call, even
 * though the messages are only useful while debugging a load or a stream. Disabled by
 * default; flip [enabled] when investigating.
 */
internal object SessionDebug {
    @Volatile
    var enabled: Boolean = false

    fun d(
        tag: String,
        sessionId: String,
        message: () -> String,
    ) {
        if (!enabled) return
        runCatching { Log.d(tag, "[$sessionId] ${message()}") }
    }
}
