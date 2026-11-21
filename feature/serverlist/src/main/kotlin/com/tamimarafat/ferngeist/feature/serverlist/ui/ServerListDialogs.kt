package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.auth.AuthenticationDialog
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.isWindowShort
import com.tamimarafat.ferngeist.core.model.auth.AuthenticationEnvVar
import com.tamimarafat.ferngeist.core.model.auth.AuthenticationMethod
import com.tamimarafat.ferngeist.core.model.auth.AuthenticationRequest
import com.tamimarafat.ferngeist.feature.serverlist.PendingAuthentication
import com.tamimarafat.ferngeist.feature.serverlist.PendingLaunchConsent
import com.tamimarafat.ferngeist.feature.serverlist.R

@Composable
internal fun LaunchRiskConsentDialog(
    pending: PendingLaunchConsent,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AgentConsentDialog(
        stateKey = pending.serverId,
        title = stringResource(R.string.serverlist_launch_risk_title),
        subject = pending.serverName,
        gatewayHost = pending.gatewayHost,
        confirmLabel = stringResource(R.string.serverlist_launch_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * The one prompt every gateway action that runs agent code answers for: a question as the
 * title, the agent as the subject, one line of what actually happens, and an explicit
 * acknowledgement. The action stays disabled until the box is ticked, so the tick is the
 * consent and the dialog is only the reading.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AgentConsentDialog(
    stateKey: Any,
    title: String,
    subject: String,
    gatewayHost: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var acknowledged by rememberSaveable(stateKey) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { ConsentMedallion() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = subject, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = agentRiskLine(LocalResources.current, gatewayHost),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = acknowledged,
                        onCheckedChange = { acknowledged = it },
                        modifier = Modifier.handCursor(),
                    )
                    Text(
                        text = stringResource(R.string.serverlist_risk_trust),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = acknowledged,
                modifier = Modifier.handCursor(enabled = acknowledged),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.handCursor()) {
                Text(stringResource(R.string.serverlist_cancel))
            }
        },
    )
}

/** One expressive accent: an abstract shape behind the icon, in the tertiary role. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConsentMedallion() {
    Box(
        modifier =
            Modifier
                .size(56.dp)
                .clip(MaterialShapes.SoftBurst.toShape())
                .background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(28.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ServerListTopBar(
    collapsed: Boolean,
    onAboutClick: () -> Unit,
) {
    val colors =
        TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    // Short windows never earn the taller header back: vertical space is the constraint.
    if (collapsed || isWindowShort()) {
        TopAppBar(
            title = { Text(text = stringResource(R.string.serverlist_title)) },
            actions = {
                IconButton(onClick = onAboutClick, modifier = Modifier.handCursor()) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = stringResource(R.string.about),
                    )
                }
            },
            colors = colors,
        )
    } else {
        MediumFlexibleTopAppBar(
            title = { Text(text = stringResource(R.string.serverlist_title)) },
            actions = {
                IconButton(onClick = onAboutClick, modifier = Modifier.handCursor()) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = stringResource(R.string.about),
                    )
                }
            },
            colors = colors,
        )
    }
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
                        modifier = Modifier.handCursor(),
                    ) {
                        Text(stringResource(R.string.privacy_policy))
                    }
                    TextButton(
                        onClick = { uriHandler.openUri(githubRepoUrl) },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.handCursor(),
                    ) {
                        Text(stringResource(R.string.report_issue))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.handCursor()) {
                Text(stringResource(R.string.serverlist_ok))
            }
        },
    )
}

@Composable
internal fun PendingAuthenticationDialog(
    pendingAuthentication: PendingAuthentication,
    isAuthenticating: Boolean,
    selectedAuthMethodId: String?,
    onSelectedAuthMethodChange: (String) -> Unit,
    onSubmit: (String, Map<String, String>) -> Unit,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    key(pendingAuthentication.serverId) {
        AuthenticationDialog(
            request =
                AuthenticationRequest(
                    agentName = pendingAuthentication.agentName,
                    methods =
                        pendingAuthentication.authMethods.map { method ->
                            AuthenticationMethod(
                                id = method.id,
                                name = method.name,
                                description = method.description,
                                type = method.type,
                                envVars =
                                    method.envVars.map {
                                        AuthenticationEnvVar(
                                            it.name,
                                            it.label,
                                            it.secret,
                                            it.optional,
                                        )
                                    },
                                link = method.link,
                                args = method.args,
                            )
                        },
                    errorMessage = pendingAuthentication.authErrorMessage,
                    isAuthenticating = isAuthenticating,
                ),
            title = stringResource(R.string.serverlist_auth_title, pendingAuthentication.serverName),
            gatewayBacked = pendingAuthentication.gatewayBacked,
            selectedAuthMethodId = selectedAuthMethodId,
            onSelectedAuthMethodChange = onSelectedAuthMethodChange,
            initialEnvValues = pendingAuthentication.persistedEnvValues,
            onSubmit = onSubmit,
            onReconnect = onReconnect,
            onDismiss = onDismiss,
        )
    }
}
