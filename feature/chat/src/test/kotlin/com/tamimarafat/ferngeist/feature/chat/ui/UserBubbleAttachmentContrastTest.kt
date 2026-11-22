package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus
import com.tamimarafat.ferngeist.feature.chat.ui.message.PREVIEW_IMAGE_BASE64
import com.tamimarafat.ferngeist.feature.chat.ui.message.PREVIEW_PNG_MIME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * An attachment card must stay a card inside its bubble: on paper, not on a tint of the bubble's own
 * container, which melts into it. The card's contrast is invisible to semantics, so this reads
 * pixels — in the light theme these previews and the app's default theme use.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3ExpressiveApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w480dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UserBubbleAttachmentContrastTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun attachmentCard_isPaperRatherThanATintOfTheBubble() {
        var paper = Color.Unspecified
        var deliveredContainer = Color.Unspecified
        var failedContainer = Color.Unspecified
        compose.setContent {
            MaterialExpressiveTheme {
                paper = MaterialTheme.colorScheme.surface
                deliveredContainer = MaterialTheme.colorScheme.primaryContainer
                failedContainer = MaterialTheme.colorScheme.errorContainer
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
                    bubble(MessageDeliveryStatus.SENT, SENT_FILE)
                    bubble(MessageDeliveryStatus.FAILED, FAILED_FILE)
                }
            }
        }

        val delivered = cardColour(SENT_FILE)
        val failed = cardColour(FAILED_FILE)

        assertTrue("a delivered prompt's card must be the paper surface, $delivered vs $paper", near(delivered, paper))
        assertTrue("a failed prompt's card must be the paper surface, $failed vs $paper", near(failed, paper))
        assertTrue(
            "a delivered prompt's card must not be its container, $deliveredContainer",
            far(delivered, deliveredContainer),
        )
        assertTrue("a failed prompt's card must not be its container, $failedContainer", far(failed, failedContainer))
    }

    @Test
    fun smallAttachmentCards_shareOneRow() {
        compose.setContent {
            MaterialExpressiveTheme {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
                    bubble(MessageDeliveryStatus.SENT, SENT_FILE, audio = AUDIO_CLIP)
                }
            }
        }

        val file = compose.onNodeWithContentDescription(SENT_FILE).getUnclippedBoundsInRoot()
        val audio = compose.onNodeWithContentDescription(AUDIO_CLIP).getUnclippedBoundsInRoot()

        assertEquals("a file card and a clip card must start on one row", file.top, audio.top)
        assertTrue("the clip card must sit beside the file card, not under it", file.right <= audio.left)
    }

    @Test
    fun promptPhoto_fillsTheRowInsteadOfLeavingAGap() {
        compose.setContent {
            MaterialExpressiveTheme {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
                    bubble(
                        status = MessageDeliveryStatus.SENT,
                        name = SENT_FILE,
                        image = true,
                    )
                }
            }
        }

        // The bitmap decodes off the composition, so the card starts on its badge face and only
        // widens once the preview lands; the assertion waits for that, not the first frame.
        compose.waitUntil(timeoutMillis = 5_000) { photoWidth().value > PHOTO_ROW_WIDTH.value }
        val width = photoWidth()
        assertTrue("a prompt photo must fill the bubble's row, not stop at the card cap, $width", width > 320.dp)
    }

    private fun photoWidth(): Dp {
        val bounds = compose.onNodeWithContentDescription(PHOTO_LABEL).getUnclippedBoundsInRoot()
        return bounds.right - bounds.left
    }

    @Composable
    private fun bubble(
        status: MessageDeliveryStatus,
        name: String,
        audio: String? = null,
        image: Boolean = false,
    ) {
        UserMessageBubble(
            message =
                ChatMessage(
                    role = ChatMessage.Role.USER,
                    contentBlocks =
                        listOfNotNull(
                            ContentBlock.Resource(
                                EmbeddedResourceResource.BlobResourceContents(
                                    blob = "",
                                    uri = "file:///tmp/$name",
                                    mimeType = "text/plain",
                                ),
                            ),
                            audio?.let { ContentBlock.Audio(data = "", mimeType = "audio/mp4") },
                            image.takeIf { it }?.let {
                                // A 4:1 PNG decodes in layoutlib, so the card takes its preview face.
                                ContentBlock.Image(data = PREVIEW_IMAGE_BASE64, mimeType = PREVIEW_PNG_MIME)
                            },
                        ),
                    status = status,
                ),
            onRetryMessage = null,
            onImageClick = {},
        )
    }

    /** The card's own background, sampled inside its top-left corner, clear of its type badge. */
    private fun cardColour(name: String): Color {
        val bounds = compose.onNodeWithContentDescription(name).getUnclippedBoundsInRoot()
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        return pixels.sample(bounds.left + CORNER_INSET, bounds.top + CORNER_INSET)
    }

    private fun near(
        sampled: Color,
        expected: Color,
    ): Boolean = channels(sampled, expected).all { it < SAME_COLOUR }

    private fun far(
        sampled: Color,
        container: Color,
    ): Boolean = channels(sampled, container).any { it > TINT_DISTANCE }

    private fun channels(
        a: Color,
        b: Color,
    ): List<Float> = listOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    private fun PixelMap.sample(
        x: Dp,
        y: Dp,
    ): Color {
        val px = with(compose.density) { x.toPx() }.toInt().coerceIn(0, width - 1)
        val py = with(compose.density) { y.toPx() }.toInt().coerceIn(0, height - 1)
        return this[px, py]
    }

    private companion object {
        const val SENT_FILE = "sent.txt"
        const val FAILED_FILE = "failed.txt"

        /** The clip card announces its label, which is the localized "Audio" in this theme. */
        const val AUDIO_CLIP = "Audio"

        /** The photo card announces its label, the localized "Image". */
        const val PHOTO_LABEL = "Image"

        /** Above the artifact card's 256dp preview cap: a photo that fills the row clears it. */
        val PHOTO_ROW_WIDTH = 320.dp

        /** Inside the card's 12dp corner, before its type badge starts. */
        val CORNER_INSET = 6.dp

        /** Sampling error: antialiasing and colour-space rounding. */
        const val SAME_COLOUR = 0.03f

        /** A tint of the bubble's own container lands well inside this. */
        const val TINT_DISTANCE = 0.10f
    }
}
