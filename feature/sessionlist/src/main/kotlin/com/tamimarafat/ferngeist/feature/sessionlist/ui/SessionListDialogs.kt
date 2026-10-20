package com.tamimarafat.ferngeist.feature.sessionlist.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.tamimarafat.ferngeist.core.common.ui.ConnectionDiagnosticsDialog
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListEvent
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListPendingAuthentication

@Composable
internal fun SessionListOverlays(
    showCwdDialog: Boolean,
    recentCwds: List<String>,
    sessions: List<SessionSummary>,
    cwdDialogValue: String,
    currentCwd: String?,
    onCwdDialogValueChange: (String) -> Unit,
    onDismissCwdDialog: () -> Unit,
    updateCurrentCwd: (String) -> Unit,
    removeRecentCwd: (String) -> Unit,
    worktree: CwdWorktreeState?,
    onCreateWorktreeSession: (repo: String, base: String?, branch: String?) -> Unit,
    pendingWorktreeRemoval: SessionListEvent.ConfirmRemoveWorktree?,
    onDismissWorktreeRemoval: () -> Unit,
    onConfirmWorktreeRemoval: (SessionListEvent.ConfirmRemoveWorktree) -> Unit,
    showConnectionStatusDialog: Boolean,
    connectionState: ChatConnectionState,
    connectionDiagnostics: ChatConnectionDiagnostics,
    onDismissConnectionStatusDialog: () -> Unit,
    pendingAuthentication: SessionListPendingAuthentication?,
    selectedAuthMethodId: String?,
    onSelectedAuthMethodChange: (String) -> Unit,
    envValues: MutableMap<String, String>,
    onSubmit: (String, Map<String, String>) -> Unit,
    onReconnect: () -> Unit,
    onDismissAuthentication: () -> Unit,
) {
    if (showCwdDialog) {
        SessionCwdDialog(
            recentCwds = recentCwds,
            sessions = sessions,
            cwdDialogValue = cwdDialogValue,
            currentCwd = currentCwd,
            onCwdDialogValueChange = onCwdDialogValueChange,
            onDismiss = onDismissCwdDialog,
            updateCurrentCwd = updateCurrentCwd,
            removeRecentCwd = removeRecentCwd,
            worktree = worktree,
            onCreateWorktreeSession = onCreateWorktreeSession,
        )
    }

    pendingWorktreeRemoval?.let { pending ->
        RemoveWorktreeDialog(
            pending = pending,
            onDismiss = onDismissWorktreeRemoval,
            onConfirm = onConfirmWorktreeRemoval,
        )
    }

    if (showConnectionStatusDialog) {
        SessionConnectionStatusDialog(
            connectionState = connectionState,
            diagnostics = connectionDiagnostics,
            onDismiss = onDismissConnectionStatusDialog,
        )
    }

    pendingAuthentication?.let { pending ->
        PendingAuthenticationDialog(
            pendingAuthentication = pending,
            selectedAuthMethodId = selectedAuthMethodId,
            onSelectedAuthMethodChange = onSelectedAuthMethodChange,
            envValues = envValues,
            onSubmit = onSubmit,
            onReconnect = onReconnect,
            onDismiss = onDismissAuthentication,
        )
    }
}

@Composable
private fun SessionCwdDialog(
    recentCwds: List<String>,
    sessions: List<SessionSummary>,
    cwdDialogValue: String,
    currentCwd: String?,
    onCwdDialogValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    updateCurrentCwd: (String) -> Unit,
    removeRecentCwd: (String) -> Unit,
    worktree: CwdWorktreeState?,
    onCreateWorktreeSession: (repo: String, base: String?, branch: String?) -> Unit,
) {
    CwdDialog(
        recentCwds = recentCwds,
        sessions = sessions,
        cwdDialogValue = cwdDialogValue,
        onCwdDialogValueChange = onCwdDialogValueChange,
        onSave = {
            val match = worktree?.match
            if (worktree?.enabled == true && match != null) {
                worktree.onOpenExisting(match)
            } else if (worktree?.enabled == true) {
                // The field is the repo now, and the worktree path — not the repo — is what
                // the new chat opens with.
                onCreateWorktreeSession(
                    cwdDialogValue,
                    worktree.base.ifBlank { null },
                    worktree.branch.ifBlank { null },
                )
            } else {
                onDismiss()
                updateCurrentCwd(cwdDialogValue)
            }
        },
        onClear =
            if (currentCwd != null) {
                {
                    onDismiss()
                    updateCurrentCwd("")
                }
            } else {
                null
            },
        onDismiss = onDismiss,
        onRemoveRecentCwd = removeRecentCwd,
        worktree = worktree,
    )
}

@Composable
private fun SessionConnectionStatusDialog(
    connectionState: ChatConnectionState,
    diagnostics: ChatConnectionDiagnostics,
    onDismiss: () -> Unit,
) {
    ConnectionDiagnosticsDialog(
        connectionState = connectionState,
        diagnostics = diagnostics,
        onDismiss = onDismiss,
    )
}

@Composable
private fun PendingAuthenticationDialog(
    pendingAuthentication: SessionListPendingAuthentication,
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
    val isGatewayEnvAuth = selectedMethod?.type == "env" && pendingAuthentication.gatewayRuntimeId != null
    val isManualEnvAuth = selectedMethod?.type == "env" && pendingAuthentication.gatewayRuntimeId == null
    // All non-optional env vars must be filled before the button is enabled.
    val requiredEnvVarsFilled =
        selectedMethod
            ?.envVars
            ?.all { envVar -> envVar.optional || !envValues[envVar.name].isNullOrBlank() }
            ?: false

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    R.string.sessionlist_auth_title,
                    pendingAuthentication.serverName,
                ),
            )
        },
        text = {
            SessionAuthDialogBody(
                pendingAuthentication = pendingAuthentication,
                selectedMethod = selectedMethod,
                onSelectedAuthMethodChange = onSelectedAuthMethodChange,
                envValues = envValues,
                onOpenLink = { uriHandler.openUri(it) },
                onEnvValueChange = { name, value -> envValues[name] = value },
                scrollState = scrollState,
            )
        },
        confirmButton = {
            SessionAuthConfirmButton(
                selectedMethod = selectedMethod,
                isGatewayEnvAuth = isGatewayEnvAuth,
                isManualEnvAuth = isManualEnvAuth,
                requiredEnvVarsFilled = requiredEnvVarsFilled,
                envValues = envValues,
                onReconnect = onReconnect,
                onSubmit = onSubmit,
            )
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.handCursor()) {
                Text(stringResource(R.string.sessionlist_auth_cancel))
            }
        },
    )
}
