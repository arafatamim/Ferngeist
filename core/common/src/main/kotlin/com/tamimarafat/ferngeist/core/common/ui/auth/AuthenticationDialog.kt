package com.tamimarafat.ferngeist.core.common.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
    initialEnvValues: Map<String, String> = emptyMap(),
    onSubmit: (String, Map<String, String>) -> Unit,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val selectedMethod =
        request.methods.firstOrNull { it.id == selectedAuthMethodId }
            ?: request.methods.firstOrNull()
    val envVarNames = request.methods.flatMap { method -> method.envVars.map { it.name } }.toSet()
    // Recreate the input state when the server supplies new saved values. Keep credentials out of saved instance state.
    val envFields =
        remember(request.agentName, envVarNames, initialEnvValues) {
            (envVarNames + initialEnvValues.keys).associateWith { TextFieldState(initialEnvValues[it].orEmpty()) }
        }
    val isGatewayEnvAuth = selectedMethod?.type == "env" && gatewayBacked
    val isManualEnvAuth = selectedMethod?.type == "env" && !gatewayBacked

    // All non-optional env vars must be filled before the button is enabled.
    val requiredEnvVarsFilled =
        selectedMethod?.envVars?.all { envVar ->
            envVar.optional || !envFields[envVar.name]?.text.isNullOrBlank()
        } ?: false

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            AuthDialogBody(
                request = request,
                gatewayBacked = gatewayBacked,
                selectedMethod = selectedMethod,
                envFields = envFields,
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
                envFields = envFields,
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
    envFields: Map<String, TextFieldState>,
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
                envFields = envFields,
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
    envFields: Map<String, TextFieldState>,
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
                else -> onSubmit(selectedMethod.id, envFields.mapValues { it.value.text.toString() })
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AuthMethodOption(
    method: AuthenticationMethod,
    selectedMethod: AuthenticationMethod?,
    onSelectedAuthMethodChange: (String) -> Unit,
    envFields: Map<String, TextFieldState>,
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
                Text(text = method.name, style = MaterialTheme.typography.bodyMediumEmphasized)
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
                        envFields = envFields,
                        isGatewayBacked = isGatewayBacked,
                        onOpenLink = onOpenLink,
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
    envFields: Map<String, TextFieldState>,
    isGatewayBacked: Boolean,
    onOpenLink: (String) -> Unit,
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
        envFields = envFields,
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
    envFields: Map<String, TextFieldState>,
) {
    method.envVars.forEach { envVar ->
        val field = envFields.getValue(envVar.name)
        val label =
            buildString {
                append(envVar.label ?: envVar.name)
                if (envVar.optional) {
                    append(stringResource(R.string.common_auth_optional_suffix))
                }
            }
        if (envVar.secret) {
            OutlinedSecureTextField(
                state = field,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(label) },
                supportingText = { Text(envVar.name) },
                textObfuscationMode = TextObfuscationMode.Hidden,
            )
        } else {
            OutlinedTextField(
                state = field,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(label) },
                supportingText = { Text(envVar.name) },
                lineLimits = TextFieldLineLimits.SingleLine,
            )
        }
    }
}
