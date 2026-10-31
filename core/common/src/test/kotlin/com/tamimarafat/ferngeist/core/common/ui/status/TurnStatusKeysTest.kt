package com.tamimarafat.ferngeist.core.common.ui.status

import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.aggregateTurnActivity
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnStatusKeysTest {
    @Test
    fun `statuses match the full target identity, not the session id alone`() {
        val statuses =
            mapOf("a/same" to TurnActivity(TurnStatus.WORKING), "b/same" to TurnActivity(TurnStatus.FINISHED))

        assertEquals(mapOf("same" to TurnActivity(TurnStatus.WORKING)), statuses.statusesFor("a"))
        assertEquals(TurnActivity(TurnStatus.UNKNOWN), statuses.statusesFor("missing").values.aggregateTurnActivity())
    }

    @Test
    fun `a target keeps the highest-priority status among its own chats`() {
        val statuses =
            mapOf(
                "a/1" to TurnActivity(TurnStatus.FINISHED),
                "a/2" to TurnActivity(TurnStatus.ERROR),
                "b/3" to TurnActivity(TurnStatus.NEEDS_ATTENTION),
            )

        assertEquals(TurnActivity(TurnStatus.ERROR), statuses.statusesFor("a").values.aggregateTurnActivity())
    }
}
