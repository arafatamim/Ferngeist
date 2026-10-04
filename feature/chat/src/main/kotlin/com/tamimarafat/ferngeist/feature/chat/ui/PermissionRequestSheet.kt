@file:OptIn(ExperimentalMaterial3Api::class)

package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.model.AcpPermissionOption
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.feature.chat.R

private const val AGENT_TEXT_MAX_LINES = 3

// An option label longer than a short sentence means the agent is using permission elicitation
// to ask an open question, not offering a one-tap approve/reject. Those labels are paragraphs,
// so they need reading time and an explicit commit rather than being buttons.
private const val ELICITATION_LABEL_MAX_CHARS = 60

internal data class PendingPermissionRequest(
    val toolCallId: String,
    val requestId: String?,
    val title: String,
    val kind: ToolKind?,
    val options: List<AcpPermissionOption>,
)

internal fun List<ChatMessage>.latestPendingPermissionRequest(): PendingPermissionRequest? {
    return asReversed().firstNotNullOfOrNull { message ->
        message.segments.asReversed().firstNotNullOfOrNull { segment ->
            val toolCall = segment.toolCall ?: return@firstNotNullOfOrNull null
            val toolCallId = toolCall.toolCallId ?: return@firstNotNullOfOrNull null
            val permissionOptions =
                toolCall.permissionOptions?.takeIf { it.isNotEmpty() }
                    ?: return@firstNotNullOfOrNull null
            PendingPermissionRequest(
                toolCallId = toolCallId,
                requestId = toolCall.permissionRequestId,
                title = toolCall.title,
                kind = toolCall.kind,
                options = permissionOptions,
            )
        }
    }
}

@Composable
internal fun PermissionRequestSheet(
    request: PendingPermissionRequest,
    onGrantPermission: (String, String) -> Unit,
    onDenyPermission: (String) -> Unit,
) {
    // Agents that use permission elicitation for open questions put a paragraph of text in each
    // option label. Stacking those as buttons makes each one multi-line and the sheet unusable,
    // so fall back to a radio list the user reads before committing with Submit.
    val choiceLayout = request.options.requiresChoiceLayout()
    var selectedOptionId by rememberSaveable(request.toolCallId) { mutableStateOf<String?>(null) }

    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            confirmValueChange = { value -> value != SheetValue.Hidden },
        )
    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_permission_title),
                style = MaterialTheme.typography.titleLarge,
            )
            PermissionRequestHeader(request = request)
            PermissionRequestOptions(
                request = request,
                choiceLayout = choiceLayout,
                selectedOptionId = selectedOptionId,
                onSelectOption = { selectedOptionId = it },
                onGrantPermission = onGrantPermission,
            )
            PermissionRequestActions(
                choiceLayout = choiceLayout,
                selectedOptionId = selectedOptionId,
                onSubmit = { optionId -> onGrantPermission(request.toolCallId, optionId) },
                onCancel = { onDenyPermission(request.toolCallId) },
            )
        }
    }
}

private fun List<AcpPermissionOption>.requiresChoiceLayout(): Boolean =
    any { it.label.length > ELICITATION_LABEL_MAX_CHARS }

@Composable
private fun PermissionRequestHeader(request: PendingPermissionRequest) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = request.title.ifBlank { stringResource(R.string.chat_permission_request) },
            style = MaterialTheme.typography.titleMedium,
            // Agents can dump an entire transcript into the title, which pushes the option
            // buttons past the top of the sheet with no way to scroll back to them.
            maxLines = AGENT_TEXT_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        request.kind?.let { kind ->
            Text(
                text = toolKindLabel(kind),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.chat_permission_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionRequestOptions(
    request: PendingPermissionRequest,
    choiceLayout: Boolean,
    selectedOptionId: String?,
    onSelectOption: (String) -> Unit,
    onGrantPermission: (String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        request.options.forEach { option ->
            if (choiceLayout) {
                PermissionChoiceRow(
                    option = option,
                    selected = option.id == selectedOptionId,
                    onSelect = { onSelectOption(option.id) },
                )
            } else {
                OutlinedButton(
                    onClick = { onGrantPermission(request.toolCallId, option.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = AGENT_TEXT_MAX_LINES,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = permissionKindLabel(option.kind),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionChoiceRow(
    option: AcpPermissionOption,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    // The whole row is the touch target: elicitation options are paragraphs, and a bare
    // RadioButton would make the user aim at a 20dp dot to read the text beside it.
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.size(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = permissionKindLabel(option.kind),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionRequestActions(
    choiceLayout: Boolean,
    selectedOptionId: String?,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    if (choiceLayout) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.chat_cancel))
            }
            Button(
                onClick = { selectedOptionId?.let(onSubmit) },
                enabled = selectedOptionId != null,
            ) {
                Text(stringResource(R.string.chat_submit))
            }
        }
    } else {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.chat_deny))
            }
        }
    }
}
