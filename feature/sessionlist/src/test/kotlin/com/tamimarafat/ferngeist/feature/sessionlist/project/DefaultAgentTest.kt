package com.tamimarafat.ferngeist.feature.sessionlist.project

import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectChat
import com.tamimarafat.ferngeist.core.model.GatewayAgentBinding
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.WorktreeScope
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultAgentTest {
    private val gateway = GatewaySource(id = "gw", name = "PC", host = "pc:5788", gatewayCredential = "token")
    private val claude = agent("claude", "Claude")
    private val codex = agent("codex", "Codex")
    private val gemini = agent("gemini", "Gemini")
    private val worktree = "/h/Ferngeist/.worktrees/email"

    @Test
    fun `newest chat in the picked checkout wins over a newer one elsewhere in the project`() {
        val chats =
            listOf(chat(codex, updatedAt = 1, worktree = worktree), chat(claude, updatedAt = 2, worktree = null))

        assertEquals(codex, defaultAgent(chats, WorktreeScope.Worktree(worktree), listOf(claude, codex)))
    }

    @Test
    fun `an empty checkout falls back to the project's newest chat`() {
        val chats = listOf(chat(codex, updatedAt = 1, worktree = null), chat(claude, updatedAt = 2, worktree = null))

        assertEquals(claude, defaultAgent(chats, WorktreeScope.Worktree(worktree), listOf(claude, codex)))
    }

    @Test
    fun `a project without chats of bound agents takes the alphabetically first agent`() {
        val unbound = chat(agent("old", "Old"), updatedAt = 3, worktree = null)

        assertEquals(codex, defaultAgent(listOf(unbound), WorktreeScope.All, listOf(gemini, codex)))
    }

    @Test
    fun `the sheet lists the default first, then the rest alphabetically`() {
        assertEquals(listOf(gemini, claude, codex), agentsInSheetOrder(gemini, listOf(codex, gemini, claude)))
    }

    @Test
    fun `agent and worktree filters intersect without collapsing duplicate session ids`() {
        val chats = listOf(chat(claude, 3, worktree), chat(codex, 2, worktree), chat(claude, 1, null))
        val filtered = chatsInScope(chats, WorktreeScope.Worktree(worktree), claude.id)
        assertEquals(listOf(chats.first()), filtered)
        assertEquals(2, chatsInScope(chats, WorktreeScope.Worktree(worktree)).size)
    }

    private fun agent(
        id: String,
        name: String,
    ) = LaunchableTarget.GatewayAgent(
        GatewayAgentBinding(id = id, name = name, gatewaySourceId = gateway.id, agentId = id),
        gateway,
    )

    private fun chat(
        target: LaunchableTarget.GatewayAgent,
        updatedAt: Long,
        worktree: String?,
    ) = ProjectChat(
        summary = SessionSummary(id = "s-${target.id}", updatedAt = updatedAt, serverId = target.id),
        target = target,
        worktree = worktree,
    )
}
