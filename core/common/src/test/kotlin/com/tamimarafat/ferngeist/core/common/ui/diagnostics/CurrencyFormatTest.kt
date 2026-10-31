package com.tamimarafat.ferngeist.core.common.ui.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class CurrencyFormatTest {
    @Test
    fun format_usesSelectedCurrencyPrecision() {
        assertEquals("¥123", currencyFormat(Locale.US, "JPY").format(123.4))
        assertEquals("KWD123.456", currencyFormat(Locale.US, "KWD").format(123.456))
    }
}
