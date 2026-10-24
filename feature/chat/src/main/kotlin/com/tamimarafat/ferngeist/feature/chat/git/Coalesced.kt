package com.tamimarafat.ferngeist.feature.chat.git

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs [block] one at a time. A [run] made while it is in flight does not start a second copy; it
 * marks the result stale and reruns once after, so a late trigger (the session becoming ready after
 * an early attempt raced its setup) is never dropped. Used on [scope]'s main thread only.
 */
internal class Coalesced(
    private val scope: CoroutineScope,
    private val block: suspend () -> Unit,
) {
    private var job: Job? = null
    private var stale = false

    fun run() {
        if (job?.isActive == true) {
            stale = true
            return
        }
        job =
            scope.launch {
                do {
                    stale = false
                    block()
                } while (stale)
            }
    }
}
