package com.tamimarafat.ferngeist.feature.chat.ui.transcript

import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedPromptTest {
    private val prompts = listOf(1, 4, 7)

    private fun rows(vararg spans: Pair<Int, IntRange>): (Int) -> IntRange? = spans.toMap()::get

    private val long: (Int) -> Boolean = { false }

    @Test
    fun nothingPinsWhileEveryPromptIsOnScreen() {
        assertEquals(emptyList<Int>(), pinnedPromptRows(prompts, 1, rows(1 to 0..80), fadePx = 0, fits = long))
    }

    @Test
    fun aPromptStillPartlyOnScreenDoesNotPin() {
        assertEquals(listOf(1), pinnedPromptRows(prompts, 4, rows(4 to -60..20), fadePx = 0, fits = long))
    }

    @Test
    fun aPromptPinsOnceItsBubbleIsEntirelyUnderTheBar() {
        assertEquals(listOf(1, 4), pinnedPromptRows(prompts, 4, rows(4 to -80..0), fadePx = 0, fits = long))
    }

    @Test
    fun theDisplacedPromptRidesAlongWhileThePinnedRowIsLaidOut() {
        assertEquals(listOf(4, 7), pinnedPromptRows(prompts, 7, rows(7 to -90..-10), fadePx = 0, fits = long))
        assertEquals(listOf(7), pinnedPromptRows(prompts, 9, rows(), fadePx = 0, fits = long))
    }

    @Test
    fun aFittingAnswerDropsOnlyItsOwnChip() {
        // A short answer taking over must not cut off the chip it is pushing out.
        assertEquals(listOf(1), pinnedPromptRows(prompts, 4, rows(4 to -80..0), fadePx = 0) { it == 4 })
    }

    @Test
    fun promptReenteringWithinFadeWindowStaysPinned() {
        assertEquals(listOf(1, 4), pinnedPromptRows(prompts, 4, rows(4 to -80..120), fadePx = 200, fits = long))
    }

    @Test
    fun aShortPromptBackAtTheTopUnpinsInsideTheWindow() {
        assertEquals(emptyList<Int>(), pinnedPromptRows(listOf(4), 4, rows(4 to 0..40), fadePx = 48, fits = long))
    }

    @Test
    fun promptPastFadeWindowUnpins() {
        assertEquals(emptyList<Int>(), pinnedPromptRows(listOf(4), 4, rows(4 to 50..300), fadePx = 200, fits = long))
    }

    @Test
    fun anAnswerAtMostAViewportTallFits() {
        assertEquals(true, answerFits(900, viewportEnd = 1000))
        assertEquals(true, answerFits(1000, viewportEnd = 1000))
        assertEquals(false, answerFits(1001, viewportEnd = 1000))
    }

    @Test
    fun anUnknownAnswerCountsAsNotFitting() {
        assertEquals(false, answerFits(null, viewportEnd = 1000))
    }

    @Test
    fun fadeAlphaIsOpaqueWhileFullyUnderTheBar() {
        assertEquals(1f, pinnedPromptFadeAlpha(null, 200), 0.001f)
        assertEquals(1f, pinnedPromptFadeAlpha(-50, 200), 0.001f)
        assertEquals(1f, pinnedPromptFadeAlpha(0, 200), 0.001f)
    }

    @Test
    fun fadeAlphaCrossfadesAcrossTheWindow() {
        assertEquals(0.5f, pinnedPromptFadeAlpha(100, 200), 0.001f)
        assertEquals(0f, pinnedPromptFadeAlpha(200, 200), 0.001f)
        assertEquals(0f, pinnedPromptFadeAlpha(300, 200), 0.001f)
    }

    @Test
    fun zeroFadeRestoresTheHardUnpin() {
        assertEquals(1f, pinnedPromptFadeAlpha(-10, 0), 0.001f)
        assertEquals(0f, pinnedPromptFadeAlpha(10, 0), 0.001f)
    }

    private fun exit(
        nextRowTop: Int?,
        answerPx: Int,
        chipHeight: Int = 40,
    ) = pinnedPromptExitAlpha(nextRowTop, answerPx, chipHeight, fadePx = 100)

    @Test
    fun theChipIsGoneOnce90PercentOfTheAnswerHasPassed() {
        // 2000px answer: the exit is 200px from the top, faded across the 100px before it.
        assertEquals(1f, exit(null, 2000), 0.001f)
        assertEquals(1f, exit(300, 2000), 0.001f)
        assertEquals(0.5f, exit(250, 2000), 0.001f)
        assertEquals(0f, exit(200, 2000), 0.001f)
    }

    @Test
    fun theChipIsGoneBeforeTheNextPromptReachesIt() {
        // A 400px answer's 10% is under the chip's height, so the chip's bottom is the exit.
        assertEquals(0f, exit(60, 400, chipHeight = 60), 0.001f)
    }
}
