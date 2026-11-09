package com.tamimarafat.ferngeist.feature.chat.ui

import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class FileReferencesTest {
    @Test
    fun markdownFileLinksResolveWithinTheWorkspace() {
        val cwd = "/home/dev/project"
        listOf("src/../internal/api/server.go:321", "/home/dev/project/internal/api/server.go:321").forEach {
            assertEquals("internal/api/server.go" to ":321", fileLink(it, cwd))
        }
        listOf("../other/file.kt", "/home/dev/other/file.kt", "https://example.com/a.kt", "#section").forEach {
            assertEquals(null, fileLink(it, cwd))
        }
    }

    @Test
    fun aBareNameChipsTheCallPathNotTheProseToken() {
        val paths = listOf(edit("c0", "/repo/src/FileChange.kt")).fileReferences()

        assertEquals(listOf(10..22 to "/repo/src/FileChange.kt"), findFileReferences("I updated FileChange.kt.", paths))
    }

    @Test
    fun readsAndEditsBothCount() {
        val paths = listOf(call(ToolKind.READ, "a.kt"), edit("c1", "b.kt")).fileReferences()

        assertEquals(setOf("a.kt", "b.kt"), paths)
    }

    @Test
    fun oneFileNamedAbsoluteAndRelativeIsNotAmbiguous() {
        val paths = setOf("/repo/src/A.kt", "src/A.kt")

        assertEquals(listOf(4..7 to "/repo/src/A.kt"), findFileReferences("see A.kt", paths))
    }

    @Test
    fun aNameTwoFilesShareStaysTextButAPathStillResolves() {
        val paths = setOf("/repo/a/Util.kt", "/repo/b/Util.kt")

        assertEquals(emptyList<Pair<IntRange, String>>(), findFileReferences("see Util.kt", paths))
        assertEquals(listOf(4..12 to "/repo/b/Util.kt"), findFileReferences("see b/Util.kt", paths))
    }

    @Test
    fun anAbsoluteMentionMatchesARelativeCallPath() {
        assertEquals(listOf(0..13 to "src/A.kt"), findFileReferences("/repo/src/A.kt", setOf("src/A.kt")))
    }

    @Test
    fun aLineSuffixStaysTextAndADotFileAndExtensionlessNameMatch() {
        assertEquals(
            listOf(0..3 to ".env", 9..18 to "Dockerfile"),
            findFileReferences(".env and Dockerfile:3", setOf(".env", "Dockerfile")),
        )
    }

    @Test
    fun aLongerNameIsNotAMatchButAnUntouchedRelativePathOpensAsWritten() {
        assertEquals(listOf(6..13 to "lib/A.kt"), findFileReferences("A.kts lib/A.kt", setOf("src/A.kt")))
    }

    @Test
    fun aRelativePathNoCallTouchedOpensAsWritten() {
        assertEquals(
            listOf(0..20 to "internal/api/proof.go", 31..54 to ".github/workflows/ci.yml"),
            findFileReferences("internal/api/proof.go:54 and ./.github/workflows/ci.yml.", emptySet()),
        )
    }

    @Test
    fun proseThatOnlyLooksLikeAPathStaysText() {
        val none = emptyList<Pair<IntRange, String>>()
        listOf("and/or", "1/2.0", "example.com/a.html", "/abs/a.go", "../up/a.go", "~/a.go", "proof.go").forEach {
            assertEquals(it, none, findFileReferences(it, emptySet()))
        }
    }

    @Test
    fun aRelativePathWithALineInACodeSpanMatchesATouchedFile() {
        assertEquals(
            listOf(0..20 to "/home/me/gw/internal/api/proof.go"),
            findFileReferences("internal/api/proof.go:54", setOf("/home/me/gw/internal/api/proof.go")),
        )
    }

    @Test
    fun aBareNameTheListHasOnceOpensItAndASharedOneOffersEveryCandidate() {
        val workspace = workspace("internal/api/proof.go", "a/Util.kt", "b/Util.kt")

        val found = findFileReferences("proof.go:57-60,78 and Util.kt", emptySet(), workspace)

        assertEquals(listOf(0..7 to "internal/api/proof.go", 22..28 to "a/Util.kt\nb/Util.kt"), found)
        assertEquals(listOf("a/Util.kt", "b/Util.kt"), chipCandidates(found[1].second))
    }

    @Test
    fun aPathNarrowsTheListAndATurnPathCoversANameTheListLacks() {
        val workspace = workspace("a/x/Util.kt", "b/Util.kt")

        assertEquals(listOf(0..8 to "a/x/Util.kt"), findFileReferences("x/Util.kt", emptySet(), workspace))
        assertEquals(listOf(0..3 to "/gen/B.kt"), findFileReferences("B.kt", setOf("/gen/B.kt"), workspace))
    }

    @Test
    fun aListedNameWithoutADotStaysText() {
        assertEquals(emptyList<Pair<IntRange, String>>(), findFileReferences("run it", emptySet(), workspace("run")))
    }

    private fun workspace(vararg files: String) = files.groupBy { it.substringAfterLast('/') }

    private fun edit(
        id: String,
        path: String,
    ) = AssistantSegment(
        id = id,
        kind = AssistantSegment.Kind.TOOL_CALL,
        toolCall =
            ToolCallDisplay(
                kind = ToolKind.EDIT,
                status = ToolCallStatus.COMPLETED,
                content = listOf(ToolCallContent.Diff(path = path, oldText = "a", newText = "b")),
            ),
    )

    private fun call(
        kind: ToolKind,
        path: String,
    ) = AssistantSegment(
        kind = AssistantSegment.Kind.TOOL_CALL,
        toolCall = ToolCallDisplay(kind = kind, rawInput = buildJsonObject { put("path", path) }),
    )
}
