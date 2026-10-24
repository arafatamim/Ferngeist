package com.tamimarafat.ferngeist.feature.chat.ui.message

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.ui.TOOL_STATUS_MIN_SCALE
import com.tamimarafat.ferngeist.feature.chat.ui.TOOL_STATUS_SETTLE_MS
import com.tamimarafat.ferngeist.feature.chat.ui.message.PlanBubble
import com.tamimarafat.ferngeist.feature.chat.ui.toolKindIcon

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ToolCallStatusIndicator(toolCall: ToolCallDisplay) {
    val status = toolCall.status ?: return
    val isTerminal = status == ToolCallStatus.COMPLETED || status == ToolCallStatus.FAILED
    val containerColor by animateColorAsState(
        targetValue =
            if (status == ToolCallStatus.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        label = "toolStatusContainer",
    )

    // Scale+fade the terminal state in rather than swapping it in: the swap used to land on the
    // frame the spinner stopped, so a whole step of the agent's work passed with no signal.
    val settle by animateFloatAsState(
        targetValue = if (isTerminal) 1f else 0f,
        animationSpec = tween(if (isTerminal) TOOL_STATUS_SETTLE_MS else 0),
        label = "toolStatusSettle",
    )

    when (status) {
        ToolCallStatus.PENDING, ToolCallStatus.IN_PROGRESS ->
            ContainedLoadingIndicator(
                polygons = pickLoadingPolygons(toolCall.toolCallId ?: toolCall.title),
                containerShape = MaterialTheme.shapes.medium,
                containerColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(24.dp),
            )

        ToolCallStatus.COMPLETED, ToolCallStatus.FAILED ->
            SettledToolStatus(
                kind = toolCall.kind,
                failed = status == ToolCallStatus.FAILED,
                containerColor = containerColor,
                settle = settle,
            )
    }
}

/** The completed or failed badge; [settle] scales and fades it in from the spinner's place. */
@Composable
private fun SettledToolStatus(
    kind: ToolKind?,
    failed: Boolean,
    containerColor: Color,
    settle: Float,
) {
    Surface(
        modifier =
            Modifier
                .size(24.dp)
                .graphicsLayer {
                    scaleX = TOOL_STATUS_MIN_SCALE + (1f - TOOL_STATUS_MIN_SCALE) * settle
                    scaleY = scaleX
                    alpha = settle
                },
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                modifier = Modifier.size(16.dp),
                imageVector = if (failed) Icons.Rounded.Error else toolKindIcon(kind),
                contentDescription =
                    stringResource(
                        if (failed) R.string.chat_error_desc else R.string.chat_completed_desc,
                    ),
                tint =
                    if (failed) {
                        MaterialTheme.colorScheme.onError
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
            )
        }
    }
}
