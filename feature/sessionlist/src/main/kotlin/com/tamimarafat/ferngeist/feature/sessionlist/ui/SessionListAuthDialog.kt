package com.tamimarafat.ferngeist.feature.sessionlist.ui

import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthMethodInfo
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListPendingAuthentication

@Composable
internal fun SessionAuthDialogBody(
    pendingAuthentication: SessionListPendingAuthentication,
    selectedMethod: AcpAuthMethodInfo?,
    onSelectedAuthMethodChange: (String) -> Unit,
    envValues: MutableMap<String, String>,
    onOpenLink: (String) -> Unit,
    onEnvValueChange: (String, String) -> Unit,
    scrollState: ScrollState,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text =
                stringResource(
                    R.string.sessionlist_auth_body,
                    pendingAuthentication.agentName,
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
        pendingAuthentication.authErrorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        pendingAuthentication.authMethods.forEach { method ->
            SessionAuthMethodRow(
                method = method,
                selected = selectedMethod?.id == method.id,
                isSelected = selectedMethod?.id == method.id,
                isGatewayBacked = pendingAuthentication.gatewayRuntimeId != null,
                envValues = envValues,
                onSelectedAuthMethodChange = onSelectedAuthMethodChange,
                onOpenLink = onOpenLink,
                onEnvValueChange = onEnvValueChange,
            )
        }
    }
}

@Composable
internal fun SessionAuthMethodRow(
    method: AcpAuthMethodInfo,
    selected: Boolean,
    isSelected: Boolean,
    isGatewayBacked: Boolean,
    envValues: MutableMap<String, String>,
    onSelectedAuthMethodChange: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onEnvValueChange: (String, String) -> Unit,
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
                selected = selected,
                onClick = { onSelectedAuthMethodChange(method.id) },
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
                        method.description ?: stringResource(
                            R.string.sessionlist_auth_method_fallback,
                            method.type,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isSelected) {
                    AuthenticationMethodDetails(
                        method = method,
                        envValues = envValues,
                        isGatewayBacked = isGatewayBacked,
                        onOpenLink = onOpenLink,
                        onEnvValueChange = onEnvValueChange,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SessionAuthConfirmButton(
    selectedMethod: AcpAuthMethodInfo?,
    isGatewayEnvAuth: Boolean,
    isManualEnvAuth: Boolean,
    requiredEnvVarsFilled: Boolean,
    envValues: MutableMap<String, String>,
    onReconnect: () -> Unit,
    onSubmit: (String, Map<String, String>) -> Unit,
) {
    TextButton(
        // Enabled: method selected; for gateway env auth all required vars must be filled.
        enabled =
            when {
                selectedMethod == null -> false
                isGatewayEnvAuth -> requiredEnvVarsFilled
                else -> true
            },
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
                stringResource(
                    R.string.sessionlist_auth_reconnect,
                )
            } else {
                stringResource(
                    R.string.sessionlist_auth_authenticate,
                )
            },
        )
    }
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
internal fun AuthenticationMethodDetails(
    method: AcpAuthMethodInfo,
    envValues: MutableMap<String, String>,
    isGatewayBacked: Boolean,
    onOpenLink: (String) -> Unit,
    onEnvValueChange: (String, String) -> Unit,
) {
    method.link?.let { link ->
        TextButton(onClick = { onOpenLink(link) }) {
            Text(link)
        }
    }
    if (method.args.isNotEmpty()) {
        AuthTerminalCommandHint(method)
    }
    if (method.type != "env") {
        return
    }
    if (!isGatewayBacked) {
        AuthManualEnvVars(method)
        return
    }
    AuthGatewayEnvFields(
        method = method,
        envValues = envValues,
        onEnvValueChange = onEnvValueChange,
    )
}

@Composable
private fun AuthTerminalCommandHint(method: AcpAuthMethodInfo) {
    Text(
        text =
            stringResource(
                R.string.sessionlist_auth_terminal_cmd,
                method.args.joinToString(" "),
            ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AuthManualEnvVars(method: AcpAuthMethodInfo) {
    Text(
        text =
            stringResource(
                R.string.sessionlist_auth_env_instructions,
            ),
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
                        append(
                            stringResource(
                                R.string.sessionlist_auth_optional_suffix,
                            ),
                        )
                    }
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AuthGatewayEnvFields(
    method: AcpAuthMethodInfo,
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
                            append(
                                stringResource(
                                    R.string.sessionlist_auth_optional_suffix,
                                ),
                            )
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
