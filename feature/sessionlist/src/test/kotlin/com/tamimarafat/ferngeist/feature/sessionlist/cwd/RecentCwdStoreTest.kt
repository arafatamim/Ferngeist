package com.tamimarafat.ferngeist.feature.sessionlist.cwd

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [RecentCwdStore] fake used as the test subject for contract tests. */
class InMemoryRecentCwdStore : RecentCwdStore {
    private val data = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    override fun getRecentCwds(targetId: String): Flow<List<String>> =
        data.map { it[targetId] ?: emptyList() }

    override suspend fun addCwd(targetId: String, cwd: String) {
        val normalized = cwd.trim()
        if (normalized.isBlank()) return
        data.emit(
            data.value.toMutableMap().apply {
                val current = this[targetId] ?: emptyList()
                this[targetId] = (listOf(normalized) + current.filter { it != normalized }).take(10)
            },
        )
    }

    override suspend fun removeCwd(targetId: String, cwd: String) {
        data.emit(
            data.value.toMutableMap().apply {
                val current = this[targetId] ?: return@apply
                this[targetId] = current.filter { it != cwd }
            },
        )
    }

    override suspend fun clear(targetId: String) {
        data.emit(
            data.value.toMutableMap().apply {
                remove(targetId)
            },
        )
    }
}

/** Contract tests for [RecentCwdStore] behaviour exercised through [InMemoryRecentCwdStore]. */
class RecentCwdStoreTest {

    private val store = InMemoryRecentCwdStore()

    @Test
    fun getRecentCwds_returnsEmptyListInitially() = runTest {
        val result = store.getRecentCwds("target1").first()
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun addCwd_addsToEmptyList() = runTest {
        store.addCwd("target1", "/home/user/projects")
        val result = store.getRecentCwds("target1").first()
        assertEquals(listOf("/home/user/projects"), result)
    }

    @Test
    fun addCwd_bumpsExistingToTop() = runTest {
        store.addCwd("target1", "/home/user/projects")
        store.addCwd("target1", "/var/www")
        store.addCwd("target1", "/home/user/projects")
        val result = store.getRecentCwds("target1").first()
        assertEquals(listOf("/home/user/projects", "/var/www"), result)
    }

    @Test
    fun addCwd_deduplicates() = runTest {
        store.addCwd("target1", "/home/user/projects")
        store.addCwd("target1", "/home/user/projects")
        val result = store.getRecentCwds("target1").first()
        assertEquals(listOf("/home/user/projects"), result)
    }

    @Test
    fun addCwd_trimsToMax10() = runTest {
        for (i in 1..12) {
            store.addCwd("target1", "/path/$i")
        }
        val result = store.getRecentCwds("target1").first()
        assertEquals(10, result.size)
        assertEquals("/path/12", result.first())
        assertEquals("/path/3", result.last())
    }

    @Test
    fun addCwd_rejectsBlankStrings() = runTest {
        store.addCwd("target1", "   ")
        store.addCwd("target1", "")
        val result = store.getRecentCwds("target1").first()
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun addCwd_trimsWhitespace() = runTest {
        store.addCwd("target1", "  /home/user/projects  ")
        val result = store.getRecentCwds("target1").first()
        assertEquals(listOf("/home/user/projects"), result)
    }

    @Test
    fun removeCwd_removesEntry() = runTest {
        store.addCwd("target1", "/home/user/projects")
        store.addCwd("target1", "/var/www")
        store.removeCwd("target1", "/var/www")
        val result = store.getRecentCwds("target1").first()
        assertEquals(listOf("/home/user/projects"), result)
    }

    @Test
    fun differentTargetIds_areSeparate() = runTest {
        store.addCwd("target1", "/home/user/projects")
        store.addCwd("target2", "/var/www")
        val result1 = store.getRecentCwds("target1").first()
        val result2 = store.getRecentCwds("target2").first()
        assertEquals(listOf("/home/user/projects"), result1)
        assertEquals(listOf("/var/www"), result2)
    }

    @Test
    fun clear_removesAllEntriesForTarget() = runTest {
        store.addCwd("target1", "/home")
        store.addCwd("target1", "/usr")
        store.addCwd("target2", "/tmp")
        store.clear("target1")
        val recents1 = store.getRecentCwds("target1").first()
        val recents2 = store.getRecentCwds("target2").first()
        assertTrue(recents1.isEmpty())
        assertEquals(listOf("/tmp"), recents2)
    }
}
