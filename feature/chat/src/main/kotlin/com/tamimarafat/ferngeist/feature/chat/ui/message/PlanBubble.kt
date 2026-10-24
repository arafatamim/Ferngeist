package com.tamimarafat.ferngeist.feature.chat.ui.message

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.ui.PLAN_TICK_POP_MIN_SCALE

@Composable
internal fun PlanBubble(
    entries: List<PlanEntry>,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            entries.forEachIndexed { index, entry ->
                PlanEntryItem(entry)
                if (index < entries.lastIndex) {
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
internal fun PlanEntryItem(entry: PlanEntry) {
    val isCompleted = entry.status == PlanEntryStatus.COMPLETED
    val isInProgress = entry.status == PlanEntryStatus.IN_PROGRESS
    val activeColor =
        if (isInProgress) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
        }
    val textColor by animateColorAsState(targetValue = activeColor, label = "planTextColor")

    // Pop the checkbox when it ticks, so a completed step is felt rather than read.
    val tick by animateFloatAsState(
        targetValue = if (isCompleted) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "planTick",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlanCheckbox(isCompleted = isCompleted, tint = activeColor, tick = tick)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = entry.content,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            fontWeight = if (isInProgress) FontWeight.Medium else null,
            textDecoration = if (isCompleted) TextDecoration.LineThrough else TextDecoration.None,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PlanCheckbox(
    isCompleted: Boolean,
    tint: Color,
    tick: Float,
) {
    Icon(
        imageVector = if (isCompleted) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
        contentDescription =
            if (isCompleted) {
                stringResource(R.string.chat_completed_desc)
            } else {
                stringResource(R.string.chat_plan_desc)
            },
        tint = tint,
        modifier =
            Modifier
                .size(18.dp)
                .graphicsLayer {
                    val pop =
                        if (isCompleted) {
                            PLAN_TICK_POP_MIN_SCALE + (1f - PLAN_TICK_POP_MIN_SCALE) * tick
                        } else {
                            1f
                        }
                    scaleX = pop
                    scaleY = pop
                },
    )
}

@Preview
@Composable
internal fun PlanBubblePreview() {
    Surface {
        PlanBubble(
            entries =
                listOf(
                    PlanEntry(
                        content = "Analyze the existing codebase structure",
                        priority = PlanEntryPriority.HIGH,
                        status = PlanEntryStatus.COMPLETED,
                    ),
                    PlanEntry(
                        content = "Identify components that need refactoring",
                        priority = PlanEntryPriority.HIGH,
                        status = PlanEntryStatus.IN_PROGRESS,
                    ),
                    PlanEntry(
                        content = "Create unit tests for critical functions",
                        priority = PlanEntryPriority.MEDIUM,
                        status = PlanEntryStatus.PENDING,
                    ),
                ),
        )
    }
}
