package com.tamimarafat.ferngeist.feature.chat.ui

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
    fun promptPastFadeWindowUnpins() {
        assertEquals(emptyList<Int>(), pinnedPromptRows(listOf(4), 4, rows(4 to 50..300), fadePx = 200, fits = long))
    }

    private fun extents(vararg sizes: Pair<Int, Int>): (Int) -> Int? = sizes.toMap()::get

    @Test
    fun answerUpToTheNextPromptFits() {
        assertEquals(true, answerFits(4, prompts, 10, extents(5 to 400, 6 to 600), viewportEnd = 1000))
        assertEquals(false, answerFits(4, prompts, 10, extents(5 to 400, 6 to 601), viewportEnd = 1000))
    }

    @Test
    fun lastAnswerRunsToTheListEnd() {
        assertEquals(true, answerFits(7, prompts, 9, extents(8 to 900), viewportEnd = 1000))
        assertEquals(false, answerFits(7, prompts, 10, extents(8 to 900, 9 to 200), viewportEnd = 1000))
    }

    @Test
    fun anUnseenRowCountsAsNotFitting() {
        assertEquals(false, answerFits(4, prompts, 10, extents(5 to 100), viewportEnd = 1000))
        // Known rows already past a viewport decide it without the unseen one.
        assertEquals(false, answerFits(4, prompts, 10, extents(5 to 1200), viewportEnd = 1000))
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

    @Test
    fun theChipRestsOnTheDockUntilTheNextPromptArrives() {
        assertEquals(0, pinnedPromptLift(nextRowTop = null, chipHeight = 40))
        assertEquals(0, pinnedPromptLift(nextRowTop = 500, chipHeight = 40))
    }

    @Test
    fun theNextPromptPushesTheChipUpAheadOfIt() {
        assertEquals(-10, pinnedPromptLift(nextRowTop = 30, chipHeight = 40))
        assertEquals(-140, pinnedPromptLift(nextRowTop = -100, chipHeight = 40))
    }
}
