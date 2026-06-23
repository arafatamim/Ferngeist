package com.tamimarafat.ferngeist.push

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide foreground/background signal backed by [ProcessLifecycleOwner].
 *
 * Used to suppress redundant push notifications: the gateway pushes turn-complete events
 * regardless of WebSocket attachment, so a user actively watching a session shouldn't be
 * buzzed for each turn. [isForeground] lets the messaging service tell whether the app is
 * on-screen. Single-instance ([Singleton]) so the value the messaging service reads is the
 * same one the observer updates.
 */
@Singleton
class AppForegroundState
    @Inject
    constructor() : DefaultLifecycleObserver {
        private val _isForeground = MutableStateFlow(false)
        val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

        /** Registers the process-lifecycle observer. Must be called on the main thread. */
        fun start() {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }

        override fun onStart(owner: LifecycleOwner) {
            _isForeground.value = true
        }

        override fun onStop(owner: LifecycleOwner) {
            _isForeground.value = false
        }
    }
