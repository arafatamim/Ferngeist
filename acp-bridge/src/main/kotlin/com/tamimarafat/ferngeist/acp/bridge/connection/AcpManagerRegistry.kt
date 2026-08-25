package com.tamimarafat.ferngeist.acp.bridge.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks every [AcpConnectionManager] created in-process and exposes aggregate
 * views of their state.
 *
 * Registrations happen at manager-creation time (create-time only), which is
 * rare, so the aggregate is recomputed by bumping a revision counter on each
 * [register] and re-combining over the current manager list via [flatMapLatest].
 * This stays correct after N registrations: the inner [combine] always observes
 * exactly the managers registered so far, and [flatMapLatest] discards the
 * previous combination when a new manager appears.
 *
 * The scope is supplied by the caller (application-scoped in the app module);
 * the aggregate is collected eagerly so consumers observe state even before
 * their own subscription.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcpManagerRegistry(
    scope: CoroutineScope,
) {
    private val managers = CopyOnWriteArrayList<AcpConnectionManager>()
    private val revision = MutableStateFlow(0)

    fun register(manager: AcpConnectionManager) {
        if (managers.addIfAbsent(manager)) {
            revision.value += 1
        }
    }

    /** True when any tracked manager is currently connected. */
    val anyConnected: StateFlow<Boolean> =
        revision
            .flatMapLatest { rev ->
                if (rev == 0) {
                    // No managers registered yet — emit the safe default.
                    flowOf(false)
                } else {
                    combine(managers.map { it.connectionState }) { states ->
                        states.any { it is AcpConnectionState.Connected }
                    }
                }
            }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Display name of the most recently registered connected manager, used for
     * the foreground notification when no aggregate name is available.
     */
    val connectedDisplayName: StateFlow<String?> =
        revision
            .flatMapLatest { rev ->
                if (rev == 0) {
                    flowOf(null)
                } else {
                    combine(managers.map { it.connectionState }) { states ->
                        val idx = states.indexOfLast { it is AcpConnectionState.Connected }
                        if (idx < 0) {
                            null
                        } else {
                            managers[idx].agentInfo.value?.name
                        }
                    }
                }
            }.stateIn(scope, SharingStarted.Eagerly, null)
}
