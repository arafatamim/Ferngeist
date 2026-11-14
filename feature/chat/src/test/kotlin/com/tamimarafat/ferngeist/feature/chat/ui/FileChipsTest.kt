package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.tamimarafat.ferngeist.feature.chat.markdown.InlineChips
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which workspace links become source chips: source and HTML do; media and other files are cards only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileChipsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `source and html links chip, media and documents do not`() {
        var chips: InlineChips? = null
        compose.setContent {
            CompositionLocalProvider(localFileOpener provides { _, _ -> }, localWorkspaceRoot provides "/home/u/app") {
                chips = rememberFileChips(emptySet(), isStreaming = false)
            }
        }
        val links = listOf("src/A.kt", "report/index.html", "out/clip.mp4", "docs/a.pdf")

        assertEquals(
            listOf(true, true, false, false),
            links.map { requireNotNull(chips).link(it) != null },
        )
    }
}
