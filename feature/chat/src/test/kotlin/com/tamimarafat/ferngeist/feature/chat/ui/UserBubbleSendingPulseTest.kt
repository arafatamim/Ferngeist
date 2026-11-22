package com.tamimarafat.ferngeist.feature.chat.ui

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.agentclientprotocol.model.ContentBlock
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * SENDING is drawn, not labelled: the bubble's own opacity carries the state. A regression that
 * drops the pulse or pulses the wrong status is invisible to semantics, so these read pixels.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3ExpressiveApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w480dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UserBubbleSendingPulseTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sendingBubble_fadesToTheMinimumAndComesBack() {
        compose.mainClock.autoAdvance = false
        showBubble(MessageDeliveryStatus.SENDING)

        val resting = promptLuminance()
        compose.mainClock.advanceTimeBy(PULSE_HALF_PERIOD_MILLIS)
        val faded = promptLuminance()
        compose.mainClock.advanceTimeBy(PULSE_HALF_PERIOD_MILLIS)
        val recovered = promptLuminance()

        assertTrue("the bubble should fade while in flight, $resting -> $faded", faded > resting + 0.02f)
        assertTrue("one breath should return to full opacity, $resting vs $recovered", recovered < faded - 0.02f)
    }

    @Test
    fun reducedMotion_sendingBubbleHoldsStillAndSaysSending() {
        Settings.Global.putFloat(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            0f,
        )
        compose.mainClock.autoAdvance = false
        showBubble(MessageDeliveryStatus.SENDING)

        compose.onNodeWithText("Sending").assertExists()
        val resting = promptLuminance()
        compose.mainClock.advanceTimeBy(PULSE_HALF_PERIOD_MILLIS)
        val later = promptLuminance()

        assertTrue("motion off must not fade, $resting vs $later", abs(later - resting) < 0.01f)
    }

    @Test
    fun sentBubble_holdsFullOpacity() {
        compose.mainClock.autoAdvance = false
        showBubble(MessageDeliveryStatus.SENT)

        val resting = promptLuminance()
        compose.mainClock.advanceTimeBy(PULSE_HALF_PERIOD_MILLIS)
        val later = promptLuminance()

        assertTrue("a delivered bubble must not breathe, $resting vs $later", abs(later - resting) < 0.01f)
    }

    private fun showBubble(status: MessageDeliveryStatus) {
        compose.setContent {
            MaterialExpressiveTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    UserMessageBubble(
                        message =
                            ChatMessage(
                                role = ChatMessage.Role.USER,
                                contentBlocks = listOf(ContentBlock.Text(PROMPT)),
                                status = status,
                            ),
                        onRetryMessage = null,
                        onImageClick = {},
                    )
                }
            }
        }
    }

    /** Mean luminance of the prompt's rect: the card fades under it, so the mean tracks the alpha. */
    private fun promptLuminance(): Float {
        val bounds = compose.onNodeWithText(PROMPT).getUnclippedBoundsInRoot()
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val left = with(compose.density) { bounds.left.toPx() }.toInt()
        val top = with(compose.density) { bounds.top.toPx() }.toInt()
        val right = with(compose.density) { bounds.right.toPx() }.toInt()
        val bottom = with(compose.density) { bounds.bottom.toPx() }.toInt()
        var total = 0f
        var samples = 0
        for (x in left.coerceAtLeast(0) until right.coerceAtMost(pixels.width)) {
            for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(pixels.height)) {
                total += pixels[x, y].luminance()
                samples++
            }
        }
        return total / samples
    }

    private companion object {
        const val PROMPT = "Ship the transcript scroll fix tonight."
        const val PULSE_HALF_PERIOD_MILLIS = 800L
    }
}
