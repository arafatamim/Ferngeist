@file:OptIn(ExperimentalMaterial3Api::class)

package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.tamimarafat.ferngeist.core.model.ChatElicitationField
import com.tamimarafat.ferngeist.core.model.ChatElicitationRequest
import com.tamimarafat.ferngeist.core.model.ChatElicitationValue
import com.tamimarafat.ferngeist.core.model.ElicitationFieldKind
import com.tamimarafat.ferngeist.core.model.defaultValues
import com.tamimarafat.ferngeist.core.model.isElicitationValueValid
import com.tamimarafat.ferngeist.feature.chat.R

@Composable
internal fun ElicitationSheet(
    request: ChatElicitationRequest,
    onSubmit: (String, Map<String, ChatElicitationValue>) -> Unit,
    onDecline: (String) -> Unit,
    onCancel: (String) -> Unit,
    onOpenUrl: (ChatElicitationRequest.Url) -> Unit,
) {
    when (request) {
        is ChatElicitationRequest.Form ->
            FormElicitationSheet(
                request = request,
                onSubmit = onSubmit,
                onDecline = onDecline,
                onCancel = onCancel,
            )
        is ChatElicitationRequest.Url ->
            UrlElicitationSheet(
                request = request,
                onDecline = onDecline,
                onCancel = onCancel,
                onOpenUrl = onOpenUrl,
            )
    }
}

@Composable
private fun ElicitationSheetShell(
    title: String,
    content: @Composable () -> Unit,
) {
    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            confirmValueChange = { value -> value != SheetValue.Hidden },
        )
    ModalBottomSheet(onDismissRequest = {}, sheetState = sheetState) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun FormElicitationSheet(
    request: ChatElicitationRequest.Form,
    onSubmit: (String, Map<String, ChatElicitationValue>) -> Unit,
    onDecline: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    // Keyed on the request so each elicitation starts from its schema defaults.
    // Required booleans without a schema default are seeded with what the switch
    // displays (off): otherwise the UI shows false while the form counts the field
    // as unanswered, and Submit can never enable without a double-toggle.
    val values =
        remember(request.key) {
            mutableStateMapOf<String, ChatElicitationValue>().also { seeded ->
                seeded.putAll(request.defaultValues())
                request.fields.forEach { field ->
                    val kind = field.kind
                    if (kind is ElicitationFieldKind.BooleanField &&
                        field.required &&
                        !seeded.containsKey(field.key)
                    ) {
                        seeded[field.key] = ChatElicitationValue.BooleanValue(kind.default ?: false)
                    }
                }
            }
        }
    // Clients must let users review before sending: Submit stays off until every
    // field validates, so an incomplete answer can never leave the sheet.
    val canSubmit = request.fields.all { isElicitationValueValid(it, values[it.key]) }
    val sheetTitle = request.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_elicitation_title)
    ElicitationSheetShell(title = sheetTitle) {
        request.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        request.fields.forEach { field ->
            ElicitationFieldRow(
                formKey = request.key,
                field = field,
                value = values[field.key],
                onChange = { next ->
                    if (next == null) values.remove(field.key) else values[field.key] = next
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onDecline(request.key) }) {
                Text(stringResource(R.string.chat_elicitation_decline))
            }
            TextButton(onClick = { onCancel(request.key) }) {
                Text(stringResource(R.string.chat_cancel))
            }
            Button(onClick = { onSubmit(request.key, values.toMap()) }, enabled = canSubmit) {
                Text(stringResource(R.string.chat_submit))
            }
        }
    }
}

@Composable
private fun ElicitationFieldRow(
    formKey: String,
    field: ChatElicitationField,
    value: ChatElicitationValue?,
    onChange: (ChatElicitationValue?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ElicitationFieldLabel(field = field)
        when (val kind = field.kind) {
            is ElicitationFieldKind.Text ->
                TextElicitationInput(
                    formKey = formKey,
                    field = field,
                    kind = kind,
                    value = value,
                    onChange = onChange,
                )
            is ElicitationFieldKind.SingleSelect ->
                SingleSelectElicitationInput(kind = kind, value = value, onChange = onChange)
            is ElicitationFieldKind.BooleanField ->
                BooleanElicitationInput(field = field, kind = kind, value = value, onChange = onChange)
            is ElicitationFieldKind.IntegerField ->
                IntegerElicitationInput(
                    formKey = formKey,
                    field = field,
                    kind = kind,
                    value = value,
                    onChange = onChange,
                )
            is ElicitationFieldKind.NumberField ->
                NumberElicitationInput(
                    formKey = formKey,
                    field = field,
                    kind = kind,
                    value = value,
                    onChange = onChange,
                )
            is ElicitationFieldKind.MultiSelect ->
                MultiSelectElicitationInput(kind = kind, value = value, onChange = onChange)
        }
        field.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!isElicitationValueValid(field, value) && value != null) {
            Text(
                text = stringResource(R.string.chat_elicitation_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ElicitationFieldLabel(field: ChatElicitationField) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = field.label, style = MaterialTheme.typography.titleSmall)
        if (field.required) {
            Text(
                text = stringResource(R.string.chat_elicitation_required),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TextElicitationInput(
    formKey: String,
    field: ChatElicitationField,
    kind: ElicitationFieldKind.Text,
    value: ChatElicitationValue?,
    onChange: (ChatElicitationValue?) -> Unit,
) {
    // Saveable key includes the request so restored text can never leak across
    // elicitations that reuse a field key.
    var text by rememberSaveable(formKey, field.key) {
        mutableStateOf((value as? ChatElicitationValue.TextValue)?.value ?: kind.default.orEmpty())
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it.takeIf { s -> s.isNotEmpty() }?.let { s -> ChatElicitationValue.TextValue(s) })
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        // No error tint on untouched fields: an empty required field is invalid
        // but must not render red before the user interacts with it.
        isError = value != null && !isElicitationValueValid(field, value),
    )
}

@Composable
private fun SingleSelectElicitationInput(
    kind: ElicitationFieldKind.SingleSelect,
    value: ChatElicitationValue?,
    onChange: (ChatElicitationValue?) -> Unit,
) {
    val selected = (value as? ChatElicitationValue.TextValue)?.value
    Column {
        kind.options.forEach { option ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = option.value == selected,
                            onClick = { onChange(ChatElicitationValue.TextValue(option.value)) },
                            role = Role.RadioButton,
                        ).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option.value == selected, onClick = null)
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(text = option.label, style = MaterialTheme.typography.bodyLarge)
                    option.description?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
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
private fun BooleanElicitationInput(
    field: ChatElicitationField,
    kind: ElicitationFieldKind.BooleanField,
    value: ChatElicitationValue?,
    onChange: (ChatElicitationValue?) -> Unit,
) {
    val checked = (value as? ChatElicitationValue.BooleanValue)?.value ?: kind.default ?: false
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    onValueChange = { onChange(ChatElicitationValue.BooleanValue(it)) },
                    role = Role.Switch,
                ).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = checked, onCheckedChange = null)
        Text(
            text = field.label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun IntegerElicitationInput(
    formKey: String,
    field: ChatElicitationField,
    kind: ElicitationFieldKind.IntegerField,
    value: ChatElicitationValue?,
    onChange: (ChatElicitationValue?) -> Unit,
) {
    val initial = (value as? ChatElicitationValue.IntegerValue)?.value?.toString() ?: kind.default?.toString().orEmpty()
    var text by rememberSaveable(formKey, field.key) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it.toLongOrNull()?.let { number -> ChatElicitationValue.IntegerValue(number) })
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = value != null && !isElicitationValueValid(field, value),
    )
}

@Composable
private fun NumberElicitationInput(
    formKey: String,
    field: ChatElicitationField,
    kind: ElicitationFieldKind.NumberField,
    value: ChatElicitationValue?,
    onChange: (ChatElicitationValue?) -> Unit,
) {
    val initial = (value as? ChatElicitationValue.NumberValue)?.value?.toString() ?: kind.default?.toString().orEmpty()
    var text by rememberSaveable(formKey, field.key) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it.toDoubleOrNull()?.let { number -> ChatElicitationValue.NumberValue(number) })
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = value != null && !isElicitationValueValid(field, value),
    )
}

@Composable
private fun MultiSelectElicitationInput(
    kind: ElicitationFieldKind.MultiSelect,
    value: ChatElicitationValue?,
    onChange: (ChatElicitationValue?) -> Unit,
) {
    val selected = (value as? ChatElicitationValue.StringListValue)?.values.orEmpty().toSet()
    Column {
        kind.options.forEach { option ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = option.value in selected,
                            onValueChange = { checked ->
                                val next =
                                    if (checked) selected + option.value else selected - option.value
                                onChange(ChatElicitationValue.StringListValue(next.toList()))
                            },
                            role = Role.Checkbox,
                        ).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = option.value in selected, onCheckedChange = null)
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(text = option.label, style = MaterialTheme.typography.bodyLarge)
                    option.description?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
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
private fun UrlElicitationSheet(
    request: ChatElicitationRequest.Url,
    onDecline: (String) -> Unit,
    onCancel: (String) -> Unit,
    onOpenUrl: (ChatElicitationRequest.Url) -> Unit,
) {
    // Shown before consent, never prefetched: the link opens only through the
    // Open button below, which is the explicit user consent the spec requires.
    val host =
        remember(request.url) {
            val uri = runCatching { request.url.toUri() }.getOrNull()
            uri?.host
        }
    ElicitationSheetShell(title = stringResource(R.string.chat_elicitation_url_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            host?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = request.url,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.chat_elicitation_url_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onDecline(request.key) }) {
                Text(stringResource(R.string.chat_elicitation_decline))
            }
            TextButton(onClick = { onCancel(request.key) }) {
                Text(stringResource(R.string.chat_cancel))
            }
            Button(onClick = { onOpenUrl(request) }) {
                Text(stringResource(R.string.chat_elicitation_open_link))
            }
        }
    }
}
