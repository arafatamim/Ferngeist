package com.tamimarafat.ferngeist.acp.bridge.catalog.project

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Hydrates the catalog once and publishes mutations only after their disk write succeeds. */
internal class ProjectSnapshotCache(
    private val store: ProjectSnapshotStore,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) {
    val writes = Mutex()
    private val snapshots = MutableStateFlow<Map<String, ProjectSnapshot>?>(null)
    val state: StateFlow<Map<String, ProjectSnapshot>?> = snapshots

    init {
        scope.launch(ioDispatcher) { snapshots.value = store.readAll() }
    }

    // Callers hold writes across their read-modify-publish operation.
    fun publish(
        key: String,
        snapshot: ProjectSnapshot,
    ) {
        store.write(key, snapshot)
        snapshots.update { it.orEmpty() + (key to snapshot) }
    }

    fun forget(key: String) {
        store.delete(key)
        snapshots.update { it?.minus(key) }
    }
}
