package com.tamimarafat.ferngeist.acp.bridge.session

/*
 * Hydration and readiness transitions for [SessionBridge]. Called only by the connection that
 * owns the bridge, never through [SessionPort], so they live beside the bridge rather than on it.
 */

/** Marks the session as entering hydration (history replay) mode. */
suspend fun SessionBridge.beginHydration() {
    runtime.beginHydration()
}

/** Marks hydration as completed and transitions the runtime toward ready. */
suspend fun SessionBridge.completeHydration() {
    runtime.completeHydration()
}

/** Marks hydration as failed and stores the failure message. */
suspend fun SessionBridge.failHydration(error: String?) {
    runtime.failHydration(error)
}

/** Marks the session as ready once initial state has been fully built. */
suspend fun SessionBridge.markReady() {
    runtime.markReady()
}
