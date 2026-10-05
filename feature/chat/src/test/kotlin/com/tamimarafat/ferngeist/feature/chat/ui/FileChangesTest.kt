package com.tamimarafat.ferngeist.feature.chat.ui

import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.gateway.GatewayChangedFile
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
                        locations = listOf("/repo/src/New.kt"),
                    ),
            )

        assertEquals(listOf("/repo/src/New.kt"), listOf(segment).fileChanges().map { it.path })
    }

    @Test
    fun gitCountsMatchAnAbsolutePathByItsRepoRelativeSuffix() {
        val tree =
            listOf(
                GatewayChangedFile(path = "src/Old.kt", status = "M", added = 1, removed = 1),
                GatewayChangedFile(path = "src/New.kt", status = "??", added = 12, removed = 0),
            )

        assertEquals(DiffStats(additions = 12, deletions = 0), tree.statsFor("/repo/src/New.kt"))
        // A suffix must sit on a path boundary: "xNew.kt" is a different file.
        assertEquals(null, tree.statsFor("/repo/src/xNew.kt"))
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
