package com.tamimarafat.ferngeist.feature.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedPromptTest {
    private val prompts = listOf(1, 4, 7)

    private fun rows(vararg spans: Pair<Int, IntRange>): (Int) -> IntRange? = spans.toMap()::get

    @Test
    fun nothingPinsWhileEveryPromptIsOnScreen() {
        assertEquals(
            emptyList<Int>(),
            pinnedPromptRows(
                prompts,
                firstVisibleIndex = 1,
                rows(1 to 0..80),
                viewportEnd = 1000,
                lastIndex = 10,
                fadePx = 0,
            ),
        )
    }

    @Test
    fun aPromptStillPartlyOnScreenDoesNotPin() {
        val partly = rows(4 to -60..20)
        assertEquals(
            listOf(1),
            pinnedPromptRows(
                prompts,
                firstVisibleIndex = 4,
                partly,
                viewportEnd = 1000,
                lastIndex = 10,
                fadePx = 0,
            ).takeLast(1),
        )
    }

    @Test
    fun aPromptPinsOnceItsBubbleIsEntirelyUnderTheBar() {
        val gone = rows(4 to -80..0)
        assertEquals(
            4,
            pinnedPromptRows(
                prompts,
                firstVisibleIndex = 4,
                gone,
                viewportEnd = 1000,
                lastIndex = 10,
                fadePx = 0,
            ).last(),
        )
    }

    @Test
    fun theDisplacedPromptRidesAlongWhileThePinnedRowIsLaidOut() {
        assertEquals(
            listOf(4, 7),
            pinnedPromptRows(
                prompts,
                firstVisibleIndex = 7,
                rows(7 to -90..-10),
                viewportEnd = 1000,
                lastIndex = 10,
                fadePx = 0,
            ),
        )
        assertEquals(
            listOf(7),
            pinnedPromptRows(
                prompts,
                firstVisibleIndex = 9,
                rows(),
                viewportEnd = 1000,
                lastIndex = 10,
                fadePx = 0,
            ),
        )
    }

    @Test
    fun shortAnswerDoesNotPin() {
        val short = rows(4 to -80..0, 7 to 300..380)
        assertEquals(
            emptyList<Int>(),
            pinnedPromptRows(
                prompts,
                firstVisibleIndex = 4,
                short,
                viewportEnd = 1000,
                lastIndex = 10,
                fadePx = 0,
            ),
        )
    }

    @Test
    fun longAnswerPins() {
        val long = rows(4 to -80..0, 7 to 1200..1280)
        assertEquals(
            listOf(1, 4),
            pinnedPromptRows(
                prompts,
                firstVisibleIndex = 4,
                long,
                viewportEnd = 1000,
                lastIndex = 10,
                fadePx = 0,
            ),
        )
    }

    @Test
    fun tailFittingOnScreenDoesNotPin() {
        val tail = rows(4 to -80..0, 6 to 500..900)
        assertEquals(
            emptyList<Int>(),
            pinnedPromptRows(
                listOf(1, 4),
                firstVisibleIndex = 4,
                tail,
                viewportEnd = 1000,
                lastIndex = 6,
                fadePx = 0,
            ),
        )
    }

    @Test
    fun tailPastTheScreenPins() {
        val tail = rows(4 to -80..0, 6 to 1200..1600)
        assertEquals(
            listOf(1, 4),
            pinnedPromptRows(
                listOf(1, 4),
                firstVisibleIndex = 4,
                tail,
                viewportEnd = 1000,
                lastIndex = 6,
                fadePx = 0,
            ),
        )
    }

    @Test
    fun promptReenteringWithinFadeWindowStaysPinned() {
        val reentering = rows(4 to -80..120)
        assertEquals(
            listOf(1, 4),
            pinnedPromptRows(
                prompts,
                firstVisibleIndex = 4,
                reentering,
                viewportEnd = 1000,
                lastIndex = 10,
                fadePx = 200,
            ),
        )
    }

    @Test
    fun promptPastFadeWindowUnpins() {
        val past = rows(4 to 50..300)
        assertEquals(
            emptyList<Int>(),
            pinnedPromptRows(
                listOf(4),
                firstVisibleIndex = 4,
                past,
                viewportEnd = 1000,
                lastIndex = 10,
                fadePx = 200,
            ),
        )
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
