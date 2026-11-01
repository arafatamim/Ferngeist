package com.tamimarafat.ferngeist.acp.bridge.session.persist

import com.tamimarafat.ferngeist.acp.bridge.session.CatchUpCursor
import com.tamimarafat.ferngeist.acp.bridge.session.SessionRuntime
import com.tamimarafat.ferngeist.core.model.TurnStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The saved state is persisted data: a file written by a released app must still read back in
 * the next one. A failure here means [SavedSessionState] or a type it reaches changed what it
 * writes; bump [SAVED_STATE_FORMAT_VERSION] and add a fixture for the new version rather than
 * editing this one.
 */
class SavedSessionStateFormatTest {
    @Test
    fun `a version 1 state file reads back and saves unchanged`() =
        runTest {
            val fixture = "/saved-state/v$SAVED_STATE_FORMAT_VERSION.json"
            val golden = checkNotNull(javaClass.getResource(fixture)).readText()
            val runtime = SessionRuntime("ses_golden")

            runtime.restore(golden)

            val snapshot = runtime.snapshot.value
            assertEquals(listOf("go", "done"), snapshot.messages.map { it.content })
            assertEquals("Golden chat", snapshot.title)
            assertEquals(TurnStatus.FINISHED, snapshot.turnStatus)
            assertEquals(CatchUpCursor("ep-1", afterSeq = 10), runtime.catchUpFrom("ep-1"))
            assertEquals(Json.parseToJsonElement(golden), Json.parseToJsonElement(runtime.savedState()))
        }
}
