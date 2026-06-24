package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthMethodInfo
import com.tamimarafat.ferngeist.feature.serverlist.PendingAuthentication
import com.tamimarafat.ferngeist.feature.serverlist.PendingLaunchConsent
import com.tamimarafat.ferngeist.feature.serverlist.R

@Composable
internal fun LaunchRiskConsentDialog(
    pending: PendingLaunchConsent,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var acknowledgedRisk by rememberSaveable(pending.serverId) { mutableStateOf(false) }
    val riskLines =
        launchRiskLines(
            res = LocalContext.current.resources,
            serverName = pending.serverName,
            agentId = pending.agentId,
            gatewayHost = pending.gatewayHost,
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.serverlist_launch_risk_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                riskLines.forEach { line ->
                    Text(
                        text = "- $line",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = acknowledgedRisk,
                        onCheckedChange = { acknowledgedRisk = it },
                    )
                    Text(
                        text = stringResource(R.string.serverlist_launch_risk_accept),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = acknowledgedRisk,
                onClick = onConfirm,
            ) {
                Text(stringResource(R.string.serverlist_continue))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.serverlist_cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ServerListTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    onAboutClick: () -> Unit,
) {
    LargeFlexibleTopAppBar(
        title = { Text(text = stringResource(R.string.serverlist_title)) },
        actions = {
            IconButton(onClick = onAboutClick) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = stringResource(R.string.about),
                )
            }
        },
        scrollBehavior = scrollBehavior,
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    )
}

@Composable
internal fun AboutDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val privacyPolicyUrl = stringResource(R.string.privacy_policy_url)
    val githubRepoUrl = stringResource(R.string.github_repo_url)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.serverlist_about_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = { uriHandler.openUri(privacyPolicyUrl) },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(stringResource(R.string.privacy_policy))
                    }
                    TextButton(
                        onClick = { uriHandler.openUri(githubRepoUrl) },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(stringResource(R.string.report_issue))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.serverlist_ok))
            }
        },
    )
}

@Composable
internal fun PendingAuthenticationDialog(
    pendingAuthentication: PendingAuthentication,
    selectedAuthMethodId: String?,
    onSelectedAuthMethodChange: (String) -> Unit,
    envValues: MutableMap<String, String>,
    onSubmit: (String, Map<String, String>) -> Unit,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val scrollState = rememberScrollState()
    val selectedMethod =
        pendingAuthentication.authMethods.firstOrNull { it.id == selectedAuthMethodId }
            ?: pendingAuthentication.authMethods.firstOrNull()
    val isGatewayEnvAuth = selectedMethod?.type == "env" && pendingAuthentication.gatewayRuntime != null
    val isManualEnvAuth = selectedMethod?.type == "env" && pendingAuthentication.gatewayRuntime == null
    val requiredEnvVarsFilled =
        selectedMethod
            ?.envVars
            ?.all { envVar -> envVar.optional || !envValues[envVar.name].isNullOrBlank() }
            ?: false

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.serverlist_auth_title, pendingAuthentication.serverName)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.serverlist_auth_body, pendingAuthentication.agentName),
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
                                    text = method.description ?: stringResource(R.string.serverlist_auth_method_fallback, method.type),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (selectedMethod?.id == method.id) {
                                    AuthenticationMethodDetails(
                                        method = method,
                                        envValues = envValues,
                                        isGatewayBacked = pendingAuthentication.gatewayRuntime != null,
                                        onOpenLink = { uriHandler.openUri(it) },
                                        onEnvValueChange = { name, value -> envValues[name] = value },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
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
                Text(if (isManualEnvAuth) stringResource(R.string.serverlist_auth_reconnect) else stringResource(R.string.serverlist_auth_authenticate))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.serverlist_auth_cancel))
            }
        },
    )
}

@Composable
private fun AuthenticationMethodDetails(
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
        Text(
            text = stringResource(R.string.serverlist_auth_cmd, method.args.joinToString(" ")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (method.type != "env") {
        return
    }
    if (!isGatewayBacked) {
        Text(
            text = stringResource(R.string.serverlist_auth_env_instructions),
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
                            append(stringResource(R.string.serverlist_auth_optional_suffix))
                        }
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
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
                            append(stringResource(R.string.serverlist_auth_optional_suffix))
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
