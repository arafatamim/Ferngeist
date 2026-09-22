package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Configurable [LaunchableTargetRepository] fake. */
class FakeLaunchableTargetRepository(
    initial: List<LaunchableTarget> = emptyList(),
) : LaunchableTargetRepository {
    private val targetsFlow = MutableStateFlow(initial)

    /** Test hook: replaces the emitted target list. */
    fun setTargets(targets: List<LaunchableTarget>) {
        targetsFlow.value = targets
    }

    override fun getTargets(): Flow<List<LaunchableTarget>> = targetsFlow

    override suspend fun getTarget(id: String): LaunchableTarget? = targetsFlow.value.firstOrNull { it.id == id }

    override suspend fun updatePreferredAuthMethod(
        targetId: String,
        methodId: String,
    ) = Unit

    override suspend fun deleteTarget(id: String) {
        targetsFlow.value = targetsFlow.value.filterNot { it.id == id }
    }
}
