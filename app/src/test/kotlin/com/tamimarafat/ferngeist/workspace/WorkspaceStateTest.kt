package com.tamimarafat.ferngeist.workspace

import androidx.compose.runtime.saveable.SaverScope
import com.tamimarafat.ferngeist.core.model.NEW_SESSION_ARG
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
    fun aSecondNewChatOnTheSameAgent_doesNotInheritTheFirstsMintedId() {
        val state = WorkspaceState(null, null, false)
        val newChat = WorkspaceSelection("srv", NEW_SESSION_ARG, "/work", "")
        state.selectSession(newChat)
        state.recordMinted(newChat.copy(chatId = state.selectedChatId!!), "real-42")
        assertEquals("real-42", state.mintedSessionId)
        state.selectSession(newChat)
        assertNull(state.mintedSessionId)
    }

    @Test
    fun selectingAnotherChat_dropsThePreviousChatsMintedId() {
        val state = WorkspaceState(null, null, false)
        val created = WorkspaceSelection("srv", NEW_SESSION_ARG, "/work", "")
        state.selectSession(created)
        state.recordMinted(created.copy(chatId = state.selectedChatId!!), "real-42")
        state.selectSession(WorkspaceSelection("srv", "other", "/work", ""))
        assertNull(state.mintedSessionId)
    }

    @Test
    fun aMintFromAChatNoLongerSelected_isDropped() {
        val state = WorkspaceState(null, null, false)
        state.selectSession(WorkspaceSelection("srv", NEW_SESSION_ARG, "/work", ""))
        val outgoing = WorkspaceSelection("srv", NEW_SESSION_ARG, "/work", "", chatId = state.selectedChatId!!)
        // Another new chat on the same agent: the outgoing pane minting late must not claim it.
        state.selectSession(WorkspaceSelection("srv", NEW_SESSION_ARG, "/work", ""))
        state.recordMinted(outgoing, "real-42")
        assertNull(state.mintedSessionId)
    }

    @Test
    fun saver_roundTripsEveryField() {
        val scope = SaverScope { true }
        val state =
            WorkspaceState("srv", "sess", true, cwd = "/work", title = "Session S").apply {
                recordMinted(WorkspaceSelection("srv", "sess", "/work", "Session S"), "real-42")
            }
        val saved = with(WorkspaceState.saver) { scope.save(state) }
        val restored = WorkspaceState.saver.restore(saved!!)
        assertEquals("srv", restored!!.selectedServerId)
        assertEquals("sess", restored.selectedSessionId)
        assertTrue(restored.agentsInLeft)
        assertEquals("real-42", restored.mintedSessionId)
        assertEquals("/work", restored.cwd)
        assertEquals("Session S", restored.title)
    }

    @Test
    fun aMintedNewChat_keepsItsChatAndMintedIdAcrossRestore() {
        val scope = SaverScope { true }
        val state = WorkspaceState(null, null, false)
        val newChat = WorkspaceSelection("srv", NEW_SESSION_ARG, "/work", "")
        state.selectSession(newChat)
        state.recordMinted(newChat.copy(chatId = state.selectedChatId!!), "real-42")
        val saved = with(WorkspaceState.saver) { scope.save(state) }
        val restored = WorkspaceState.saver.restore(saved!!)!!
        assertEquals(state.selectedChatId, restored.selectedChatId)
        assertEquals("real-42", restored.mintedSessionId)
    }

    @Test
    fun chatPickedFromAProject_keepsTheProjectAcrossRestore() {
        val scope = SaverScope { true }
        val state = WorkspaceState(null, null, false)
        state.selectProject(WorkspaceProject("gw", "/h/Ferngeist"))
        state.selectSession(WorkspaceSelection("srv", "s", "/h/Ferngeist", "Session S", updatedAt = 123456L))
        val saved = with(WorkspaceState.saver) { scope.save(state) }
        val restored = WorkspaceState.saver.restore(saved!!)!!
        assertEquals(WorkspaceProject("gw", "/h/Ferngeist"), restored.selectedProject)
        assertEquals("s", restored.selectedSessionId)
        assertEquals(123456L, restored.updatedAt)
    }
}
