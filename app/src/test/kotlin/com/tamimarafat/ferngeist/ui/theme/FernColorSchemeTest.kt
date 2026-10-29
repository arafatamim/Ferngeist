package com.tamimarafat.ferngeist.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class FernColorSchemeTest {
    @Test
    fun `fern palettes keep primary pairs legible in light and dark modes`() {
        for (dark in listOf(false, true)) {
            val scheme = fernColorScheme(dark)
            assertTrue(contrast(scheme.primary, scheme.onPrimary) >= 4.5)
            assertTrue(contrast(scheme.primaryContainer, scheme.onPrimaryContainer) >= 4.5)
        }
    }

    private fun contrast(
        first: Color,
        second: Color,
    ): Double {
        val a = first.luminance().toDouble()
        val b = second.luminance().toDouble()
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
