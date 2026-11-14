package com.tamimarafat.ferngeist.feature.chat.ui

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallLocation
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.git.files.entryFor
import com.tamimarafat.ferngeist.core.git.files.statsFor
import com.tamimarafat.ferngeist.core.git.state.GitState
import com.tamimarafat.ferngeist.core.git.ui.diff.DiffStats
import com.tamimarafat.ferngeist.core.git.ui.diff.computeDiffStats
import com.tamimarafat.ferngeist.core.model.AcpPermissionOption
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatGitStatus
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.core.model.content
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.ui.files.diffs
import com.tamimarafat.ferngeist.feature.chat.ui.files.displayLabel
import com.tamimarafat.ferngeist.feature.chat.ui.files.fileLocation
import com.tamimarafat.ferngeist.feature.chat.ui.files.filePath
import com.tamimarafat.ferngeist.feature.chat.ui.files.titleWithFile
import com.tamimarafat.ferngeist.feature.chat.ui.files.titleWithoutFile
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class FileChangesTest {
    @Test
    fun editsToOneFileCollectUnderItAndPointAtTheLastCall() {
        val changes =
            listOf(
                edit("c0", "a.kt"),
                edit("c1", "b.kt"),
                edit("c2", "a.kt"),
            ).fileChanges()

        assertEquals(listOf("a.kt", "b.kt"), changes.map { it.path })
        assertEquals(2, changes[0].diffs.size)
        assertEquals("c2", changes[0].segmentId)
    }

    @Test
    fun aFailedEditNeverLandedSoItIsLeftOut() {
        val changes = listOf(edit("c0", "a.kt", ToolCallStatus.FAILED), edit("c1", "b.kt")).fileChanges()

        assertEquals(listOf("b.kt"), changes.map { it.path })
    }

    @Test
    fun aWriteWithNoDiffCountsAsANewFileWithEveryLineAdded() {
        val write =
            ToolCallDisplay(
                kind = ToolKind.EDIT,
                status = ToolCallStatus.COMPLETED,
                rawInput =
                    buildJsonObject {
                        put("file_path", "src/New.kt")
                        put("content", "one\ntwo\nthree")
                    },
            )

        val diff = write.diffs().single()
        assertEquals("src/New.kt", diff.path)
        assertEquals(DiffStats(additions = 3, deletions = 0), listOf(diff).computeDiffStats())
    }

    @Test
    fun aReportedDiffIsNeverReplacedByAnInferredOne() {
        val reported = ToolCallContent.Diff(path = "a.kt", oldText = "a", newText = "b")
        val call =
            ToolCallDisplay(
                kind = ToolKind.EDIT,
                content = listOf(reported),
                rawInput =
                    buildJsonObject {
                        put("file_path", "a.kt")
                        put("content", "b")
                    },
            )

        assertEquals(listOf(reported), call.diffs())
    }

    @Test
    fun anEditWithNoDiffAndNothingToRebuildStillListsItsFile() {
        val segment =
            AssistantSegment(
                id = "c0",
                kind = AssistantSegment.Kind.TOOL_CALL,
                toolCall =
                    ToolCallDisplay(
                        kind = ToolKind.EDIT,
                        status = ToolCallStatus.COMPLETED,
                        rawInput = buildJsonObject { put("file_path", "a.kt") },
                    ),
            )

        val change = listOf(segment).fileChanges().single()
        assertEquals("a.kt", change.path)
        assertEquals(0, change.diffs.size)
    }

    @Test
    fun aSearchAndReplaceWithNoDiffBecomesAnExactOne() {
        val call =
            ToolCallDisplay(
                kind = ToolKind.EDIT,
                rawInput =
                    buildJsonObject {
                        put("file_path", "a.kt")
                        put("old_string", "one\ntwo")
                        put("new_string", "one\nTWO\nthree")
                    },
            )

        assertEquals(DiffStats(additions = 2, deletions = 1), call.diffs().computeDiffStats())
    }

    @Test
    fun eachEntryOfAnEditsListBecomesItsOwnDiff() {
        val call =
            ToolCallDisplay(
                kind = ToolKind.EDIT,
                rawInput =
                    buildJsonObject {
                        put("file_path", "a.kt")
                        put(
                            "edits",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("old_string", "a")
                                        put("new_string", "b")
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("old_string", "c")
                                        put("new_string", "d")
                                    },
                                )
                            },
                        )
                    },
            )

        assertEquals(listOf("a", "c"), call.diffs().map { it.oldText })
    }

    @Test
    fun anEditThatOnlyNamesItsFileInLocationsIsStillListed() {
        val segment =
            AssistantSegment(
                id = "c0",
                kind = AssistantSegment.Kind.TOOL_CALL,
                toolCall =
                    ToolCallDisplay(
                        kind = ToolKind.EDIT,
                        status = ToolCallStatus.COMPLETED,
                        locations = listOf(ToolCallLocation(path = "/repo/src/New.kt", line = 8u)),
                    ),
            )

        assertEquals(listOf("/repo/src/New.kt"), listOf(segment).fileChanges().map { it.path })
    }

    @Test
    fun gitCountsMatchAnAbsolutePathByItsRepoRelativeSuffix() {
        val tree =
            listOf(
                ChatChangedFile(path = "src/Old.kt", status = "M", added = 1, removed = 1),
                ChatChangedFile(path = "src/New.kt", status = "??", added = 12, removed = 0),
            )

        assertEquals(DiffStats(additions = 12, deletions = 0), tree.statsFor("/repo/src/New.kt"))
        // A suffix must sit on a path boundary: "xNew.kt" is a different file.
        assertEquals(null, tree.statsFor("/repo/src/xNew.kt"))
    }

    @Test
    fun theLongestMatchingGitEntryWins() {
        val tree =
            listOf(
                ChatChangedFile(path = "X.kt", status = "M"),
                ChatChangedFile(path = "src/X.kt", status = "M"),
            )

        assertEquals("src/X.kt", tree.entryFor("/repo/src/X.kt")?.path)
    }

    @Test
    fun binaryAndDirectoryEntriesNeverBackATurnFile() {
        val tree =
            listOf(
                ChatChangedFile(path = "img.png", status = "M", binary = true),
                ChatChangedFile(path = "vendor/lib", status = "M", isDir = true),
            )

        assertEquals(null, tree.entryFor("img.png"))
        assertEquals(null, tree.entryFor("vendor/lib"))
    }

    @Test
    fun gitCountsWinOverSummedDiffsOfARepeatedlyEditedFile() {
        val edits =
            listOf(
                ToolCallContent.Diff(path = "/repo/a.kt", oldText = "x", newText = "y"),
                ToolCallContent.Diff(path = "/repo/a.kt", oldText = "y", newText = "z"),
            )
        val file = FileChange("/repo/a.kt", edits, "s1")
        val tree = listOf(ChatChangedFile(path = "a.kt", status = "M", added = 1, removed = 1))

        assertEquals(DiffStats(additions = 2, deletions = 2), file.stats(emptyList()))
        assertEquals(DiffStats(additions = 1, deletions = 1), file.stats(tree))
    }

    @Test
    fun aFinalNewlineDoesNotAddALine() {
        val diff = ToolCallContent.Diff(path = "a.kt", oldText = null, newText = "one\ntwo\r\n")

        assertEquals(DiffStats(additions = 2, deletions = 0), listOf(diff).computeDiffStats())
    }

    @Test
    fun onlyEditCallsAreReadAsWrites() {
        val read =
            ToolCallDisplay(
                kind = ToolKind.READ,
                rawInput =
                    buildJsonObject {
                        put("path", "a.kt")
                        put("content", "x")
                    },
            )

        assertEquals(emptyList<ToolCallContent.Diff>(), read.diffs())
    }

    @Test
    fun aFileCallsTitleGainsItsFileNameOnlyWhenItLeavesItOut() {
        val bare =
            ToolCallDisplay(
                title = "Read",
                kind = ToolKind.READ,
                locations = listOf(ToolCallLocation(path = "/repo/src/A.kt", line = 4u)),
            )
        val command =
            ToolCallDisplay(title = "ls", kind = ToolKind.EXECUTE, rawInput = buildJsonObject { put("path", "/repo") })

        assertEquals("Read A.kt", bare.titleWithFile())
        assertEquals("Read src/A.kt", bare.copy(title = "Read src/A.kt").titleWithFile())
        assertEquals("ls", command.titleWithFile())
    }

    @Test
    fun aFileCallsTitleLosesItsFileForTheChipToShow() {
        val call =
            ToolCallDisplay(
                title = "Read src/A.kt",
                kind = ToolKind.READ,
                locations = listOf(ToolCallLocation(path = "/repo/src/A.kt", line = 4u)),
            )

        assertEquals("Read", call.titleWithoutFile())
        assertEquals("Read", call.copy(title = "Read").titleWithoutFile())
        assertEquals("ls", ToolCallDisplay(title = "ls", kind = ToolKind.EXECUTE).titleWithoutFile())
    }

    @Test
    fun aFileAlreadyChangedWhenTheTurnStartedIsLeftOutOfItsTree() {
        val tree =
            listOf(
                ChatChangedFile(path = "old.kt", status = "M"),
                ChatChangedFile(path = "new.kt", status = "??"),
            )
        val state = ChatState(git = GitState(status = ChatGitStatus(changed = tree)))

        assertEquals(tree, state.git.turnWorkingTree())
        assertEquals(
            listOf("new.kt"),
            state
                .copy(git = state.git.copy(dirtyAtStart = setOf("old.kt")))
                .git
                .turnWorkingTree()
                .map { it.path },
        )
    }

    @Test
    fun anEditAwaitingPermissionHasNotChangedItsFileYet() {
        val pending =
            edit("c0", "a.kt", ToolCallStatus.PENDING).let { segment ->
                segment.copy(
                    toolCall =
                        segment.toolCall?.copy(
                            permissionOptions = listOf(AcpPermissionOption("y", "Allow", "allow_once")),
                        ),
                )
            }

        assertEquals(emptyList<FileChange>(), listOf(pending).fileChanges())
    }

    @Test
    fun aTurnFileRowOpensEveryDiffItCounted() {
        val turn = turnOf(edit("c0", "a.kt"), edit("c1", "b.kt"), edit("c2", "a.kt"))
        val file = turnFiles(turn).first()

        val opened = turn.toolCallForSelection(file.selectionId(), newestTree = emptyList())

        assertEquals(file.diffs, opened?.content)
        assertEquals(listOf(ToolCallLocation(path = "a.kt")), opened?.locations)
    }

    @Test
    fun aTurnFileGitCountsOpensWithNoReportedDiffSoGitsFillsIt() {
        val turn = turnOf(edit("c0", "a.kt"), edit("c1", "a.kt"))
        val file = turnFiles(turn).single()
        val tree = listOf(ChatChangedFile(path = "a.kt", status = "M", added = 1, removed = 1))

        val opened = turn.toolCallForSelection(file.selectionId(), tree)

        assertEquals(emptyList<ToolCallContent>(), opened?.content)
        assertEquals(ToolKind.EDIT, opened?.kind)
    }

    @Test
    fun aToolCallRowStillOpensItsOwnCall() {
        val turn = turnOf(edit("c0", "a.kt"), edit("c1", "a.kt"))

        assertEquals(turn.single().segments[0].toolCall, turn.toolCallForSelection("c0", emptyList()))
        assertEquals("c0", FileChange("a.kt", emptyList(), "c0").selectionId().selectedSegmentId())
    }

    @Test
    fun aTurnSplitAcrossMessagesShowsOneFooterWithEveryBubblesEdits() {
        val turn = listOf(reply(edit("c0", "a.kt")), reply(edit("c1", "b.kt")))
        val messages =
            listOf(ChatMessage(role = ChatMessage.Role.USER, contentBlocks = listOf(ContentBlock.Text("go")))) + turn

        val footers = messages.turnSegmentsByCloser()

        assertEquals(setOf(turn[1].id), footers.keys)
        assertEquals(listOf("a.kt", "b.kt"), footers.getValue(turn[1].id).fileChanges().map { it.path })
    }

    @Test
    fun aTurnFileSplitAcrossMessagesOpensEveryDiffAndGitsCounts() {
        val editedTwice = listOf(reply(edit("c0", "a.kt")), reply(edit("c1", "a.kt")))
        val file = turnSegmentsOf(editedTwice).fileChanges().single()
        assertEquals(file.diffs, editedTwice.toolCallForSelection(file.selectionId(), emptyList())?.content)

        // a.kt's last call sits in the first bubble, but the turn is still the newest one.
        val newest = listOf(reply(edit("c0", "a.kt")), reply(edit("c1", "b.kt")))
        val earlier = turnSegmentsOf(newest).fileChanges().first()
        val tree = listOf(ChatChangedFile(path = "a.kt", status = "M", added = 1, removed = 1))
        assertEquals(emptyList<ToolCallContent>(), newest.toolCallForSelection(earlier.selectionId(), tree)?.content)
    }

    @Test
    fun aLocationShowsItsLineOnlyWhenTheAgentGivesOne() {
        assertEquals("src/A.kt:12", ToolCallLocation(path = "src/A.kt", line = 12u).displayLabel())
        assertEquals("src/A.kt", ToolCallLocation(path = "src/A.kt").displayLabel())
    }

    @Test
    fun aFilesLocationKeepsTheMatchingAcpLine() {
        val call =
            ToolCallDisplay(
                kind = ToolKind.READ,
                rawInput = buildJsonObject { put("path", "src/A.kt") },
                locations =
                    listOf(
                        ToolCallLocation(path = "src/B.kt", line = 3u),
                        ToolCallLocation(path = "src/A.kt", line = 42u),
                    ),
            )

        assertEquals(ToolCallLocation(path = "src/A.kt", line = 42u), call.fileLocation())
        assertEquals("src/A.kt", call.filePath())
    }

    private fun turnSegmentsOf(turn: List<ChatMessage>) = turn.turnSegmentsByCloser().values.single()

    private fun reply(vararg segments: AssistantSegment) =
        ChatMessage(role = ChatMessage.Role.ASSISTANT, segments = segments.toList().toPersistentList())

    private fun turnFiles(turn: List<ChatMessage>) = turn.single().segments.fileChanges()

    private fun turnOf(vararg segments: AssistantSegment) =
        listOf(ChatMessage(role = ChatMessage.Role.ASSISTANT, segments = segments.toList().toPersistentList()))

    private fun edit(
        id: String,
        path: String,
        status: ToolCallStatus = ToolCallStatus.COMPLETED,
    ) = AssistantSegment(
        id = id,
        kind = AssistantSegment.Kind.TOOL_CALL,
        toolCall =
            ToolCallDisplay(
                status = status,
                content = listOf(ToolCallContent.Diff(path = path, oldText = "a", newText = "b")),
            ),
    )
}
