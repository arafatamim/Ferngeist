package com.tamimarafat.ferngeist.workspace

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceStateTest {
    @Test
    fun selectSession_clearsAgentsInLeft() {
        val state = WorkspaceState(null, null, false)
        state.selectAgent("a")
        state.backToAgents()
        state.selectSession(WorkspaceSelection("srv", "s", "/work", "Session S"))
        assertFalse(state.agentsInLeft)
        assertEquals("srv", state.selectedServerId)
        assertEquals("s", state.selectedSessionId)
        // The pane-built ChatViewModel reads both out of the handle, so the selection is
        // the only thing that can carry them.
        assertEquals("/work", state.cwd)
        assertEquals("Session S", state.title)
    }

    @Test
    fun resumeSession_keepsChatPinnedBesideTheAgentList() {
        val state = WorkspaceState(null, null, false)
        state.resumeSession(WorkspaceSelection("srv", "s", "/work", "Session S"))
        // The extraPane gate reads the server id, so a restore that leaves it null renders
        // the empty placeholder instead of the chat.
        assertEquals("srv", state.selectedServerId)
        assertEquals("s", state.selectedSessionId)
        assertTrue(state.agentsInLeft)
        assertEquals("/work", state.cwd)
        assertEquals("Session S", state.title)
        // The D9 cold-start and D1 back target are the same state.
        assertEquals(
            listOf(WorkspacePane.AGENTS, WorkspacePane.CHAT),
            desirablePanes(2, state.selectedServerId, state.selectedSessionId, state.agentsInLeft),
        )
    }

    @Test
    fun backToAgents_keepsTheChatPinnedBesideTheAgentList() {
        val state = WorkspaceState(null, null, false)
        state.selectAgent("a")
        state.selectSession(WorkspaceSelection("srv", "s", "/work", "Session S"))
        state.backToAgents()
        assertTrue(state.agentsInLeft)
        // D1: the chat stays pinned; only the left pane steps up to the agents.
        assertEquals("s", state.selectedSessionId)
        assertEquals(
            listOf(WorkspacePane.AGENTS, WorkspacePane.CHAT),
            desirablePanes(2, state.selectedServerId, state.selectedSessionId, state.agentsInLeft),
        )
        // and at one pane the same state yields the chat, per the D1 table.
        assertEquals(
            listOf(WorkspacePane.CHAT),
            desirablePanes(1, state.selectedServerId, state.selectedSessionId, state.agentsInLeft),
        )
    }

    @Test
    fun selectAgent_clearsAnySessionFromThePreviousAgent() {
        val state = WorkspaceState(null, null, false)
        state.selectAgent("a")
        state.selectSession(WorkspaceSelection("srv", "s", "/work", "Session S"))
        state.selectAgent("b")
        assertEquals("b", state.selectedServerId)
        assertNull(state.selectedSessionId)
        assertFalse(state.agentsInLeft)
    }

    @Test
    fun mintedIdIsCarriedSoItSurvivesProcessDeath() {
        val state = WorkspaceState(null, null, false)
        assertNull(state.mintedSessionId)
        state.mintedSessionId = "real-42"
        assertEquals("real-42", state.mintedSessionId)
    }

    @Test
    fun saver_roundTripsEveryField() {
        val scope = SaverScope { true }
        val state = WorkspaceState("srv", "sess", true, "real-42", "/work", "Session S")
        val saved = with(WorkspaceState.Saver) { scope.save(state) }
        val restored = WorkspaceState.Saver.restore(saved!!)
        assertEquals("srv", restored!!.selectedServerId)
        assertEquals("sess", restored.selectedSessionId)
        assertTrue(restored.agentsInLeft)
        assertEquals("real-42", restored.mintedSessionId)
        assertEquals("/work", restored.cwd)
        assertEquals("Session S", restored.title)
    }
}
