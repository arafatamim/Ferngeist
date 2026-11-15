package com.tamimarafat.ferngeist.feature.chat.ui.scroll

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.ControlledComposition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

class RunwayHoldTest {
    @Test
    fun `releasing a held turn prevents its reserve returning when following relatches`() {
        val hold =
            RunwayHold().apply {
                key = "previous"
                px = 700
            }
        val runway = Runway(turnKey = "current", bottomPx = 40, hold = hold, spacingPx = 12)
        val composition = ControlledComposition(NoOpApplier(), Recomposer(EmptyCoroutineContext))
        var following = true
        var shown: TurnRunway = TurnRunway.None
        try {
            composition.setContent {
                val reserve = runway.of("previous", following)
                SideEffect { shown = reserve }
            }
            assertEquals(TurnRunway.Held(700), shown)

            following = false
            composition.invalidateAll()
            composition.recompose()
            composition.applyChanges()
            assertEquals(TurnRunway.None, shown)

            following = true
            composition.invalidateAll()
            composition.recompose()
            composition.applyChanges()
            assertEquals(TurnRunway.None, shown)
        } finally {
            composition.dispose()
        }
    }

    private class NoOpApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(
            index: Int,
            instance: Unit,
        ) = Unit

        override fun insertBottomUp(
            index: Int,
            instance: Unit,
        ) = Unit

        override fun remove(
            index: Int,
            count: Int,
        ) = Unit

        override fun move(
            from: Int,
            to: Int,
            count: Int,
        ) = Unit

        override fun onClear() = Unit
    }
}
