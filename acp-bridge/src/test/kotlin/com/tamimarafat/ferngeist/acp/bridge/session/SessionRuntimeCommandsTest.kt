package com.tamimarafat.ferngeist.acp.bridge.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionRuntimeCommandsTest {
    @Test
    fun command_input_hints_follow_changed_and_removed_advertisements() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")

            runtime.onEvent(
                AppSessionEvent.CommandsUpdated(
                    listOf(CommandInfo("review", "Review a diff", inputHint = "base branch")),
                ),
            )
            val firstSnapshot = runtime.snapshot.value
            assertEquals("base branch", firstSnapshot.availableCommands.single().inputHint)

            runtime.onEvent(
                AppSessionEvent.CommandsUpdated(
                    listOf(CommandInfo("review", "Review a diff", inputHint = "commit")),
                ),
            )
            val updatedSnapshot = runtime.snapshot.value
            assertEquals("commit", updatedSnapshot.availableCommands.single().inputHint)

            runtime.onEvent(
                AppSessionEvent.CommandsUpdated(listOf(CommandInfo("review", "Review a diff"))),
            )
            val removedSnapshot = runtime.snapshot.value
            assertNull(removedSnapshot.availableCommands.single().inputHint)
        }
}
