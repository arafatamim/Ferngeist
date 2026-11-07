package com.tamimarafat.ferngeist.core.common.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.R
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.status.InProgressIndicator
import com.tamimarafat.ferngeist.core.model.auth.AuthenticationMethod
import com.tamimarafat.ferngeist.core.model.auth.AuthenticationRequest

@Composable
fun AuthenticationDialog(
    request: AuthenticationRequest,
    title: String,
    gatewayBacked: Boolean = false,
    allowEnvironmentAuth: Boolean = true,
    selectedAuthMethodId: String?,
    onSelectedAuthMethodChange: (String) -> Unit,
    envValues: MutableMap<String, String>,
    onSubmit: (String, Map<String, String>) -> Unit,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val selectedMethod =
        request.methods.firstOrNull { it.id == selectedAuthMethodId }
            ?: request.methods.firstOrNull()
    val isGatewayEnvAuth = selectedMethod?.type == "env" && gatewayBacked
    val isManualEnvAuth = selectedMethod?.type == "env" && !gatewayBacked

    // All non-optional env vars must be filled before the button is enabled.
    val requiredEnvVarsFilled =
        selectedMethod?.envVars?.all { envVar ->
            envVar.optional || !envValues[envVar.name].isNullOrBlank()
        } ?: false

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            AuthDialogBody(
                request = request,
                gatewayBacked = gatewayBacked,
                selectedMethod = selectedMethod,
                envValues = envValues,
                onSelectedAuthMethodChange = onSelectedAuthMethodChange,
                onOpenLink = { uriHandler.openUri(it) },
            )
        },
        confirmButton = {
            AuthDialogConfirmButton(
                selectedMethod = selectedMethod,
                isGatewayEnvAuth = isGatewayEnvAuth,
                isManualEnvAuth = isManualEnvAuth,
                requiredEnvVarsFilled = requiredEnvVarsFilled,
                busy = request.isAuthenticating,
                allowEnvironmentAuth = allowEnvironmentAuth,
                envValues = envValues,
                onSubmit = onSubmit,
                onReconnect = onReconnect,
            )
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.handCursor(),
            ) {
                Text(stringResource(R.string.common_auth_cancel))
            }
        },
    )
}

@Composable
private fun AuthDialogBody(
    request: AuthenticationRequest,
    gatewayBacked: Boolean,
    selectedMethod: AuthenticationMethod?,
    envValues: MutableMap<String, String>,
    onSelectedAuthMethodChange: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.common_auth_body, request.agentName),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (request.isAuthenticating) {
            Row(
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InProgressIndicator()
                Text(
                    text = stringResource(R.string.common_auth_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (gatewayBacked) {
            Text(
                text = stringResource(R.string.common_auth_gateway_guidance),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        request.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        request.methods.forEach { method ->
            AuthMethodOption(
                method = method,
                selectedMethod = selectedMethod,
                onSelectedAuthMethodChange = onSelectedAuthMethodChange,
                envValues = envValues,
                isGatewayBacked = gatewayBacked,
                onOpenLink = onOpenLink,
            )
        }
    }
}

@Composable
private fun AuthDialogConfirmButton(
    selectedMethod: AuthenticationMethod?,
    isGatewayEnvAuth: Boolean,
    isManualEnvAuth: Boolean,
    requiredEnvVarsFilled: Boolean,
    busy: Boolean,
    allowEnvironmentAuth: Boolean,
    envValues: MutableMap<String, String>,
    onSubmit: (String, Map<String, String>) -> Unit,
    onReconnect: () -> Unit,
) {
    // Enabled: method selected; for gateway env auth all required vars must be filled.
    val canConfirm =
        !busy &&
            (selectedMethod?.type != "env" || allowEnvironmentAuth) &&
            computeConfirmEnabled(
                selectedMethod = selectedMethod,
                isGatewayEnvAuth = isGatewayEnvAuth,
                requiredEnvVarsFilled = requiredEnvVarsFilled,
            )
    TextButton(
        modifier = Modifier.handCursor(enabled = canConfirm),
        enabled = canConfirm,
        onClick = {
            when {
                selectedMethod == null -> Unit
                isManualEnvAuth -> onReconnect()
                else -> onSubmit(selectedMethod.id, envValues)
            }
        },
    ) {
        Text(
            if (isManualEnvAuth) {
                stringResource(R.string.common_auth_reconnect)
            } else {
                stringResource(R.string.common_auth_authenticate)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthMethodOption(
    method: AuthenticationMethod,
    selectedMethod: AuthenticationMethod?,
    onSelectedAuthMethodChange: (String) -> Unit,
    envValues: MutableMap<String, String>,
    isGatewayBacked: Boolean,
    onOpenLink: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(
                selected = selectedMethod?.id == method.id,
                onClick = { onSelectedAuthMethodChange(method.id) },
                modifier = Modifier.handCursor(),
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = method.name, fontWeight = FontWeight.SemiBold)
                Text(
                    text =
                        method.description
                            ?: stringResource(R.string.common_auth_method_fallback, method.type),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (selectedMethod?.id == method.id) {
                    AuthenticationMethodDetails(
                        method = method,
                        envValues = envValues,
                        isGatewayBacked = isGatewayBacked,
                        onOpenLink = onOpenLink,
                        onEnvValueChange = { name, value -> envValues[name] = value },
                    )
                }
            }
        }
    }
}

private fun computeConfirmEnabled(
    selectedMethod: AuthenticationMethod?,
    isGatewayEnvAuth: Boolean,
    requiredEnvVarsFilled: Boolean,
): Boolean =
    when {
        selectedMethod == null -> false
        selectedMethod.type !in setOf("agent", "env") -> false
        isGatewayEnvAuth -> requiredEnvVarsFilled
        else -> true
    }

/**
 * Renders auth method-specific details below the selected method.
 *
 * Three branches:
 * 1. Method has a link → `TextButton` to open it
 * 2. Method is manual-env ("env" but not gateway-backed) → lists required env vars with instructions
 * 3. Method is gateway-env ("env" + gateway-backed) → inline `OutlinedTextField` per env var
 */
@Composable
private fun AuthenticationMethodDetails(
    method: AuthenticationMethod,
    envValues: MutableMap<String, String>,
    isGatewayBacked: Boolean,
    onOpenLink: (String) -> Unit,
    onEnvValueChange: (String, String) -> Unit,
) {
    method.link?.let { link ->
        TextButton(onClick = { onOpenLink(link) }, modifier = Modifier.handCursor()) {
            Text(link)
        }
    }
    if (method.args.isNotEmpty()) {
        Text(
            text = stringResource(R.string.common_auth_cmd, method.args.joinToString(" ")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (method.type != "env") {
        return
    }
    if (!isGatewayBacked) {
        ManualEnvVarInstructions(method)
        return
    }
    GatewayEnvVarInputs(
        method = method,
        envValues = envValues,
        onEnvValueChange = onEnvValueChange,
    )
}

@Composable
private fun ManualEnvVarInstructions(method: AuthenticationMethod) {
    Text(
        text = stringResource(R.string.common_auth_env_instructions),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    method.envVars.forEach { envVar ->
        Text(
            text =
                buildString {
                    append(envVar.label ?: envVar.name)
                    append(" -> ")
                    append(envVar.name)
                    if (envVar.optional) {
                        append(stringResource(R.string.common_auth_optional_suffix))
                    }
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GatewayEnvVarInputs(
    method: AuthenticationMethod,
    envValues: MutableMap<String, String>,
    onEnvValueChange: (String, String) -> Unit,
) {
    method.envVars.forEach { envVar ->
        OutlinedTextField(
            value = envValues[envVar.name].orEmpty(),
            onValueChange = { onEnvValueChange(envVar.name, it) },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    buildString {
                        append(envVar.label ?: envVar.name)
                        if (envVar.optional) {
                            append(stringResource(R.string.common_auth_optional_suffix))
                        }
                    },
                )
            },
            supportingText = { Text(envVar.name) },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = if (envVar.secret) KeyboardType.Password else KeyboardType.Text,
                ),
            visualTransformation =
                if (envVar.secret) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
        )
    }
}
