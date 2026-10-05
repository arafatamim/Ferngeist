package com.tamimarafat.ferngeist.feature.chat.ui

import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolVerbCountsTest {
    @Test
    fun callsAreCountedByVerbInFirstAppearanceOrder() {
        val counts =
            toolVerbCounts(
                calls(ToolKind.EXECUTE, ToolKind.EDIT, ToolKind.EXECUTE, ToolKind.EDIT, ToolKind.EXECUTE),
            )

        assertEquals(listOf(ToolVerb.EXECUTE to 3, ToolVerb.EDIT to 2), counts)
    }

    @Test
    fun unknownThinkSwitchModeAndOtherKindsShareOneBucket() {
        // A THINK call shows no reasoning, so it must not read as "thought N times".
        val counts = toolVerbCounts(calls(null, ToolKind.THINK, ToolKind.SWITCH_MODE, ToolKind.OTHER))

        assertEquals(listOf(ToolVerb.OTHER to 4), counts)
    }

    @Test
    fun failedCallsDoNotCountAsTheirVerbAndComeLast() {
        val counts =
            toolVerbCounts(
                listOf(
                    ToolCallDisplay(kind = ToolKind.EDIT, status = ToolCallStatus.FAILED).summaryVerb(),
                    ToolCallDisplay(kind = ToolKind.EXECUTE, status = ToolCallStatus.COMPLETED).summaryVerb(),
                ),
            )

        assertEquals(listOf(ToolVerb.EXECUTE to 1, ToolVerb.FAILED to 1), counts)
    }

    private fun calls(vararg kinds: ToolKind?) = kinds.map { ToolCallDisplay(kind = it).summaryVerb() }
}
