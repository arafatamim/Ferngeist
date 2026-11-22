package com.tamimarafat.ferngeist.feature.chat.ui.message

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus
import com.tamimarafat.ferngeist.feature.chat.ui.PendingPromptQueue

/** Every delivery badge the bubble can carry. [MessageDeliveryStatus.SENT] carries none. */
@Preview(name = "Sent", widthDp = 420)
@Composable
internal fun UserBubbleSentPreview() = UserBubblePreview(userMessage(PREVIEW_PROMPT))

@Preview(name = "Queued", widthDp = 420)
@Composable
internal fun UserBubbleQueuedPreview() =
    UserBubblePreview(userMessage(PREVIEW_PROMPT, status = MessageDeliveryStatus.QUEUED))

@Preview(name = "Sending", widthDp = 420)
@Composable
internal fun UserBubbleSendingPreview() =
    UserBubblePreview(userMessage(PREVIEW_PROMPT, status = MessageDeliveryStatus.SENDING))

@Preview(name = "Failed, retryable", widthDp = 420)
@Composable
internal fun UserBubbleFailedPreview() =
    UserBubblePreview(userMessage(PREVIEW_PROMPT, status = MessageDeliveryStatus.FAILED))

/** No retry callback: the badge still says failed, but has no target to tap. */
@Preview(name = "Failed, no retry handler", widthDp = 420)
@Composable
internal fun UserBubbleFailedWithoutRetryPreview() =
    UserBubblePreview(
        message = userMessage(PREVIEW_PROMPT, status = MessageDeliveryStatus.FAILED),
        onRetryMessage = null,
    )

/** The pending strip: one held prompt, one in flight, one that needs the user. */
@Preview(name = "Pending strip", widthDp = 420, heightDp = 320)
@Composable
internal fun PendingPromptQueuePreview() =
    MaterialExpressiveTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            PendingPromptQueue(
                messages =
                    listOf(
                        userMessage(PREVIEW_PROMPT, status = MessageDeliveryStatus.QUEUED),
                        userMessage(PREVIEW_LONG_PROMPT, status = MessageDeliveryStatus.SENDING),
                        userMessage("And update the changelog.", status = MessageDeliveryStatus.FAILED),
                    ),
                onRetryMessage = {},
                onForceSendMessage = {},
                onRemoveMessage = {},
                onRestingHeight = {},
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
