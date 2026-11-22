package com.tamimarafat.ferngeist.feature.chat.ui.composer

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The collapsed toolbar's mode button names the mode where it fits and falls back to an icon. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModeMenuButtonTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun modeButton_roomForLabel_showsModeName() {
        setModeButton(rowWidth = 300.dp)

        compose.onNodeWithText(LABEL, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun modeButton_narrowBar_showsIconNamedForMode() {
        // Under the button's own padding: no label fits, whatever the test fonts measure.
        setModeButton(rowWidth = 20.dp)

        compose.onNodeWithText(LABEL, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(LABEL, useUnmergedTree = true).assertExists()
    }

    private fun setModeButton(rowWidth: Dp) {
        compose.setContent {
            MaterialExpressiveTheme {
                Row(Modifier.width(rowWidth)) {
                    ModeMenuButton(
                        modifier = Modifier.weight(1f, fill = false),
                        currentModeLabel = LABEL,
                        modeOption = ChatConfigOption.Select(id = "mode", name = "Mode", currentValue = "edits"),
                        maxWidth = 300.dp,
                        expanded = false,
                        onExpandedChange = {},
                        interactionSource = MutableInteractionSource(),
                        onSetConfigOption = { _, _ -> },
                    )
                }
            }
        }
    }

    private companion object {
        const val LABEL = "Accept edits"
    }
}
