package com.tamimarafat.ferngeist.core.common.ui.status

import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.aggregateTurnStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnStatusKeysTest {
    @Test
    fun `statuses match the full target identity, not the session id alone`() {
        val statuses = mapOf("a/same" to TurnStatus.WORKING, "b/same" to TurnStatus.FINISHED)

        assertEquals(mapOf("same" to TurnStatus.WORKING), statuses.statusesFor("a"))
        assertEquals(TurnStatus.UNKNOWN, statuses.statusesFor("missing").values.aggregateTurnStatus())
    }

    @Test
    fun `a target keeps the highest-priority status among its own chats`() {
        val statuses =
            mapOf(
                "a/1" to TurnStatus.FINISHED,
                "a/2" to TurnStatus.ERROR,
                "b/3" to TurnStatus.NEEDS_ATTENTION,
            )

        assertEquals(TurnStatus.ERROR, statuses.statusesFor("a").values.aggregateTurnStatus())
    }
}
