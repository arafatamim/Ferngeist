package com.tamimarafat.ferngeist.data.database.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tamimarafat.ferngeist.core.model.TurnKey
import com.tamimarafat.ferngeist.core.model.TurnTiming
import com.tamimarafat.ferngeist.data.database.FerngeistDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TurnTimingRepositoryTest {
    private lateinit var database: FerngeistDatabase

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FerngeistDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun sameSessionAndTurnOnDifferentAgents_keepIndependentTimes() =
        runTest {
            val repository = TurnTimingRepositoryImpl(database.turnTimingDao()) { 1000L }
            val key = TurnKey(0, 42)
            repository.record("agent-a", "shared", key, TurnTiming(100, 200))
            repository.record("agent-b", "shared", key, TurnTiming(300, 400))

            val reloaded = TurnTimingRepositoryImpl(database.turnTimingDao()) { 1000L }
            assertEquals(mapOf(key to TurnTiming(100, 200)), reloaded.getTimings("agent-a", "shared"))
            assertEquals(mapOf(key to TurnTiming(300, 400)), reloaded.getTimings("agent-b", "shared"))
        }

    @Test
    fun read_prunesExpiredTurnsAndKeepsRetentionBoundary() =
        runTest {
            val retention = TimeUnit.DAYS.toMillis(7)
            val repository = TurnTimingRepositoryImpl(database.turnTimingDao()) { retention + 1000 }
            val expired = TurnKey(0, 1)
            val boundary = TurnKey(1, 2)
            repository.record("agent", "chat", expired, TurnTiming(100, 999))
            repository.record("agent", "chat", boundary, TurnTiming(200, 1000))

            assertEquals(mapOf(boundary to TurnTiming(200, 1000)), repository.getTimings("agent", "chat"))
        }
}
