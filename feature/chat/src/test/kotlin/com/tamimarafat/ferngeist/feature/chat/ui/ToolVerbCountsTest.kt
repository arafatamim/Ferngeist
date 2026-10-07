package com.tamimarafat.ferngeist.feature.chat.ui

import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.feature.chat.R
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

    @Test
    fun runningGroupUsesContinuousTenseExceptFailed() {
        val parts = summaryParts(listOf(ToolVerb.EXECUTE, ToolVerb.FAILED), running = true)

        assertEquals(
            listOf(
                SummaryPart(R.plurals.chat_tool_summary_execute_running, null, 1),
                SummaryPart(R.plurals.chat_tool_summary_failed, null, 1),
            ),
            parts,
        )
    }

    @Test
    fun settledGroupKeepsPastTense() {
        val parts = summaryParts(listOf(ToolVerb.EXECUTE, ToolVerb.READ), running = false)

        assertEquals(
            listOf(
                SummaryPart(R.plurals.chat_tool_summary_execute, null, 1),
                SummaryPart(R.plurals.chat_tool_summary_read, null, 1),
            ),
            parts,
        )
    }

    @Test
    fun runningTwiceStringIsContinuous() {
        val parts = summaryParts(listOf(ToolVerb.THOUGHT, ToolVerb.THOUGHT), running = true)

        assertEquals(
            listOf(
                SummaryPart(
                    R.plurals.chat_tool_summary_thought_running,
                    R.string.chat_tool_summary_thought_running_twice,
                    2,
                ),
            ),
            parts,
        )
    }

    private fun calls(vararg kinds: ToolKind?) = kinds.map { ToolCallDisplay(kind = it).summaryVerb() }
}
