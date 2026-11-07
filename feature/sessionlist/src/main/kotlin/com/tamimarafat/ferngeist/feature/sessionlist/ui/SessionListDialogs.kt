package com.tamimarafat.ferngeist.feature.sessionlist.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tamimarafat.ferngeist.core.common.ui.auth.AuthenticationDialog
import com.tamimarafat.ferngeist.core.common.ui.diagnostics.ConnectionDiagnosticsSheet
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.auth.AuthenticationEnvVar
import com.tamimarafat.ferngeist.core.model.auth.AuthenticationMethod
import com.tamimarafat.ferngeist.core.model.auth.AuthenticationRequest
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListEvent
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListPendingAuthentication
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.ScopeOption
import com.tamimarafat.ferngeist.gateway.GatewayGitRefs

@Composable
internal fun SessionListOverlays(
    showCwdDialog: Boolean,
    recentCwds: List<String>,
    sessions: List<SessionSummary>,
    cwdRootOf: (String) -> String,
    cwdDialogValue: String,
    currentCwd: String?,
    onCwdDialogValueChange: (String) -> Unit,
    onDismissCwdDialog: () -> Unit,
    updateCurrentCwd: (String) -> Unit,
    removeRecentCwd: (String) -> Unit,
    showWorktreeSheet: Boolean,
    scopeOptions: List<ScopeOption>,
    initialBase: String,
    loadRefs: suspend (String) -> GatewayGitRefs,
    worktreeError: String?,
    creatingWorktree: Boolean,
    onWorktreeFormChanged: () -> Unit,
    onDismissWorktreeSheet: () -> Unit,
    onCreateWorktreeSession: (repo: String, base: String?, branch: String?) -> Unit,
    pendingWorktreeRemoval: SessionListEvent.ConfirmRemoveWorktree?,
    onDismissWorktreeRemoval: () -> Unit,
    onConfirmWorktreeRemoval: (SessionListEvent.ConfirmRemoveWorktree) -> Unit,
    showConnectionStatusDialog: Boolean,
    connectionState: ChatConnectionState,
    connectionDiagnostics: ChatConnectionDiagnostics,
    fetchQuota: suspend () -> List<com.tamimarafat.ferngeist.core.model.AgentQuota>?,
    onDismissConnectionStatusDialog: () -> Unit,
    pendingAuthentication: SessionListPendingAuthentication?,
    isAuthenticating: Boolean,
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
            rootOf = cwdRootOf,
            cwdDialogValue = cwdDialogValue,
            currentCwd = currentCwd,
            onCwdDialogValueChange = onCwdDialogValueChange,
            onDismiss = onDismissCwdDialog,
            updateCurrentCwd = updateCurrentCwd,
            removeRecentCwd = removeRecentCwd,
        )
    }

    if (showWorktreeSheet) {
        NewWorktreeSheet(
            repo = currentCwd.orEmpty(),
            branches = scopeOptions.map { it.worktree.branch },
            initialBase = initialBase,
            error = worktreeError,
            creating = creatingWorktree,
            onFormChanged = onWorktreeFormChanged,
            onCreate = onCreateWorktreeSession,
            loadRefs = loadRefs,
            onDismiss = onDismissWorktreeSheet,
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
            fetchQuota = fetchQuota,
            onDismiss = onDismissConnectionStatusDialog,
        )
    }

    pendingAuthentication?.let { pending ->
        PendingAuthenticationDialog(
            pendingAuthentication = pending,
            isAuthenticating = isAuthenticating,
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
    rootOf: (String) -> String,
    cwdDialogValue: String,
    currentCwd: String?,
    onCwdDialogValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    updateCurrentCwd: (String) -> Unit,
    removeRecentCwd: (String) -> Unit,
) {
    CwdDialog(
        recentCwds = recentCwds,
        sessions = sessions,
        cwdDialogValue = cwdDialogValue,
        onCwdDialogValueChange = onCwdDialogValueChange,
        onSave = {
            onDismiss()
            updateCurrentCwd(cwdDialogValue)
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
        rootOf = rootOf,
    )
}

@Composable
private fun SessionConnectionStatusDialog(
    connectionState: ChatConnectionState,
    diagnostics: ChatConnectionDiagnostics,
    fetchQuota: suspend () -> List<com.tamimarafat.ferngeist.core.model.AgentQuota>?,
    onDismiss: () -> Unit,
) {
    ConnectionDiagnosticsSheet(
        connectionState = connectionState,
        diagnostics = diagnostics,
        fetchQuota = fetchQuota,
        onDismiss = onDismiss,
    )
}

@Composable
private fun PendingAuthenticationDialog(
    pendingAuthentication: SessionListPendingAuthentication,
    isAuthenticating: Boolean,
    selectedAuthMethodId: String?,
    onSelectedAuthMethodChange: (String) -> Unit,
    envValues: MutableMap<String, String>,
    onSubmit: (String, Map<String, String>) -> Unit,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
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
                                    AuthenticationEnvVar(it.name, it.label, it.secret, it.optional)
                                },
                            link = method.link,
                            args = method.args,
                        )
                    },
                errorMessage = pendingAuthentication.authErrorMessage,
                isAuthenticating = isAuthenticating,
            ),
        title = stringResource(R.string.sessionlist_auth_title, pendingAuthentication.serverName),
        gatewayBacked = pendingAuthentication.gatewayRuntimeId != null,
        selectedAuthMethodId = selectedAuthMethodId,
        onSelectedAuthMethodChange = onSelectedAuthMethodChange,
        envValues = envValues,
        onSubmit = onSubmit,
        onReconnect = onReconnect,
        onDismiss = onDismiss,
    )
}
