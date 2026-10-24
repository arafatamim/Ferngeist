package com.tamimarafat.ferngeist.feature.chat

import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FILE = "src/A.kt"

@OptIn(ExperimentalCoroutinesApi::class)
class ChatFileLoaderTest {
    @Test
    fun aPathInsideTheProjectIsReadRelativeToIt() {
        assertEquals("src/A.kt", "/repo/src/A.kt".relativeTo("/repo/"))
        assertEquals("src/A.kt", "./src/A.kt".relativeTo("/repo"))
        assertEquals("src/A.kt", "C:\\repo\\src\\A.kt".relativeTo("C:\\repo"))
    }

    @Test
    fun anAbsolutePathOutsideTheProjectIsRefused() {
        assertNull("/elsewhere/A.kt".relativeTo("/repo"))
        assertNull("/repository/A.kt".relativeTo("/repo"))
        assertNull("D:/x/A.kt".relativeTo("C:/repo"))
    }

    @Test
    fun changedAndInsertedLinesAreMarkedAndADeletionMarksTheLineBelowIt() {
        val file = markedFile(oldText = "a\nb\nc\nd\n", newText = "a\nB\nc\nnew\n")

        // b -> B changed (1); d -> new changed (3).
        assertEquals(setOf(1, 3), file.changedLines)
        assertEquals(emptySet<Int>(), file.deletedAbove)

        val deleted = markedFile(oldText = "a\nb\nc\n", newText = "a\nc\n")
        assertEquals(emptySet<Int>(), deleted.changedLines)
        assertEquals(setOf(1), deleted.deletedAbove)
    }

    @Test
    fun linesDeletedAtTheEndMarkPastTheLastLine() {
        assertEquals(setOf(1), markedFile(oldText = "a\nb\n", newText = "a\n").deletedAbove)
    }

    @Test
    fun anUntrackedFileIsAllNew() {
        assertEquals(setOf(0, 1), markedFile(oldText = null, newText = "a\nb\n").changedLines)
    }

    @Test
    fun aFileOpenedAtACommitIsThatCommitsCopyMarkedByThatCommit() =
        runTest {
            val facade = workspace()
            facade.fetchGitDiffResults[FILE] =
                listOf(ToolCallContent.Diff(path = FILE, oldText = "a\nb\n", newText = "a\nB\nb\n"))

            val file = load(facade, path = "/repo/$FILE", commit = "abc1234")

            assertNotNull(file)
            assertEquals("a\nB\nb\n", file!!.text)
            assertEquals(setOf(1), file.changedLines)
            assertEquals("abc1234", file.commit)
            assertNull(file.compare)
            assertEquals(listOf("abc1234" to null), facade.gitDiffCommitRequests)
        }

    @Test
    fun aFileOpenedAgainstACompareRefReadsTheWorkingTreeMarkedAgainstIt() =
        runTest {
            val facade = workspace()
            facade.fetchGitDiffResults[FILE] =
                listOf(ToolCallContent.Diff(path = FILE, oldText = "a\nb\n", newText = "a\nb\nc\n"))

            val file = load(facade, path = "/repo/$FILE", compare = "origin/main")

            assertNotNull(file)
            assertEquals("a\nb\nc\n", file!!.text)
            assertNull(file.commit)
            assertEquals("origin/main", file.compare)
            assertEquals(listOf<String?>("origin/main"), facade.gitDiffCompareRequests)
        }

    @Test
    fun aFileTheWorkingTreeDeletedOpensAsDeletedRatherThanAsThePlainFile() =
        runTest {
            val facade = workspace()
            facade.fetchGitDiffResults[FILE] =
                listOf(ToolCallContent.Diff(path = FILE, oldText = "a\nb\n", newText = ""))

            val file = load(facade, path = "/repo/$FILE", tree = listOf(ChatChangedFile(FILE, status = "D")))

            assertNotNull(file)
            assertTrue(file!!.deleted)
        }

    @Test
    fun aPathOutsideTheProjectFailsWithoutAskingTheGateway() =
        runTest {
            val facade = workspace()

            val file = load(facade, path = "/elsewhere/A.kt")

            assertNotNull(file!!.error)
            assertTrue(facade.gitDiffRequests.isEmpty())
        }

    /** A facade with a gateway workspace, so its reads answer instead of reporting none. */
    private fun workspace(): FakeChatSessionFacade =
        FakeChatSessionFacade().also {
            it.emitGatewayWorkspaceConnection(
                GatewayWorkspaceConnection(runtimeId = "rt", scheme = "http", host = "host", gatewayCredential = "c"),
            )
        }

    /** Opens [path] and returns what landed in the state's file slot. */
    private fun TestScope.load(
        facade: ChatSessionFacade,
        path: String,
        commit: String? = null,
        compare: String? = null,
        tree: List<ChatChangedFile> = emptyList(),
    ): FileView? {
        var state = ChatState()
        ChatFileLoader(this, facade, { state = it(state) }, "/repo", { tree })
            .open(path, commit, compare)
        advanceUntilIdle()
        return state.openFile
    }
}
