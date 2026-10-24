package com.tamimarafat.ferngeist.feature.chat.ui.git

import com.tamimarafat.ferngeist.core.model.ChatGitRef
import com.tamimarafat.ferngeist.feature.chat.GitRefsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGitComparePickerTest {
    private fun ref(
        name: String,
        kind: ChatGitRef.Kind = ChatGitRef.Kind.BRANCH,
    ) = ChatGitRef(name, kind)

    @Test
    fun `sections run local, remote, tags, and skip the empty ones`() {
        val refs =
            GitRefsState(
                refs = listOf(ref("v1", ChatGitRef.Kind.TAG), ref("dev"), ref("origin/dev", ChatGitRef.Kind.REMOTE)),
            )

        assertEquals(
            listOf(ChatGitRef.Kind.BRANCH, ChatGitRef.Kind.REMOTE, ChatGitRef.Kind.TAG),
            comparePickerSections(refs).map { it.first },
        )
        assertEquals(
            listOf(
                ChatGitRef.Kind.BRANCH,
            ),
            comparePickerSections(GitRefsState(refs = listOf(ref("dev")))).map {
                it.first
            },
        )
    }

    @Test
    fun `the default branch leads its section, local and remote`() {
        val refs =
            GitRefsState(
                default = "main",
                refs =
                    listOf(
                        ref("dev"),
                        ref("main"),
                        ref("origin/dev", ChatGitRef.Kind.REMOTE),
                        ref("origin/main", ChatGitRef.Kind.REMOTE),
                    ),
            )

        val sections = comparePickerSections(refs).associate { it.first to it.second.map { ref -> ref.name } }

        assertEquals(listOf("main", "dev"), sections[ChatGitRef.Kind.BRANCH])
        assertEquals(listOf("origin/main", "origin/dev"), sections[ChatGitRef.Kind.REMOTE])
    }

    @Test
    fun `a tag and a branch sharing a name are different targets`() {
        assertTrue(ref("main").isSameTarget(ref("main")))
        assertFalse(ref("main").isSameTarget(ref("main", ChatGitRef.Kind.TAG)))
        assertFalse(null.isSameTarget(ref("main")))
    }
}
