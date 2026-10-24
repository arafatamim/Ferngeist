package com.tamimarafat.ferngeist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavHostController
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.push.ChatDeepLinkTarget
import com.tamimarafat.ferngeist.push.resolveChatDeepLink
import com.tamimarafat.ferngeist.service.BatteryOptimizationDialog
import com.tamimarafat.ferngeist.service.BatteryOptimizationHelper
import com.tamimarafat.ferngeist.service.BatteryOptimizationPreferences
import com.tamimarafat.ferngeist.workspace.WorkspaceSelection
import com.tamimarafat.ferngeist.workspace.WorkspaceState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Deep-links a notification tap to the referenced chat session.
 *
 * A wide window shows chat as a pinned pane, not a screen, so the tap selects the session in
 * [workspace] instead of navigating: pushing `chat/...` there would cover the whole workspace
 * with the full-screen chat. Non-chat deep links, and every deep link on a compact window
 * (where [workspace] is null), keep navigating.
 */
@Composable
internal fun DeepLinkEffect(
    navController: NavHostController,
    latestIntent: StateFlow<Intent?>,
    onIntentConsumed: () -> Unit,
    workspace: WorkspaceState?,
) {
    // Taps on our own notifications (connection and push) carry the target chat as extras.
    val pendingIntent by latestIntent.collectAsState()
    LaunchedEffect(pendingIntent) {
        val intent = pendingIntent ?: return@LaunchedEffect
        resolveChatDeepLink(intent)?.let { target ->
            if (workspace != null) {
                selectPinnedChat(workspace, target)
            } else {
                openChatRoute(navController, target)
            }
        }
        onIntentConsumed()
    }
}

private fun selectPinnedChat(
    workspace: WorkspaceState,
    target: ChatDeepLinkTarget,
) {
    // Nothing to do when the pinned chat is already the target: re-resuming it
    // would step the left pane back to the agent list under the user.
    val alreadySelected =
        workspace.selectedServerId == target.serverId &&
            workspace.selectedSessionId == target.sessionId
    if (alreadySelected) return
    workspace.resumeSession(
        WorkspaceSelection(
            serverId = target.serverId,
            sessionId = target.sessionId,
            cwd = target.cwd,
            title = target.title,
        ),
    )
}

private fun openChatRoute(
    navController: NavHostController,
    target: ChatDeepLinkTarget,
) {
    val current = navController.currentBackStackEntry
    val currentChatRoute = current?.destination?.route?.takeIf { it.startsWith("chat/") }
    val alreadyOpen =
        currentChatRoute != null &&
            current.arguments?.getString("serverId") == target.serverId &&
            current.arguments?.getString("sessionId") == target.sessionId
    if (alreadyOpen) return
    val gatewayIdParam = target.gatewayId?.let { "&gatewayId=${Uri.encode(it)}" }.orEmpty()
    val titleParam =
        if (target.title.isNotBlank()) "&title=${Uri.encode(target.title)}" else ""
    navController.navigate(
        "chat/${target.serverId}/${target.sessionId}" +
            "?cwd=${Uri.encode(target.cwd)}$titleParam$gatewayIdParam",
    ) {
        // Single-top alone would keep the open chat's entry, and with it that
        // chat's view model, and only swap its arguments: the tap would land
        // back on the chat already showing. Replace it, as the switcher does.
        currentChatRoute?.let { popUpTo(it) { inclusive = true } }
        launchSingleTop = true
    }
}

@Composable
internal fun BatteryOptimizationGate(
    chatConnectionHub: ChatConnectionHub,
    context: Context,
) {
    val batteryPrefs = remember(context) { BatteryOptimizationPreferences(context) }
    var prefsLoaded by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    val anyConnected by chatConnectionHub.anyConnected.collectAsState()

    LaunchedEffect(batteryPrefs) {
        dismissed = batteryPrefs.isDismissed.first()
        prefsLoaded = true
    }

    // One-shot: latch on the first connected transport and hold the prompt until the user
    // decides. [ChatConnectionHub.anyConnected] counts listing/verification transports too,
    // and drops to false the moment the last of them is torn down — opening a chat releases
    // the agent screen's transport before the chat's own connects — so tracking it live made
    // the prompt blink out and back for as long as the handoff took.
    LaunchedEffect(anyConnected, prefsLoaded) {
        if (!anyConnected) return@LaunchedEffect
        val exemptionMissing = !BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
        if (prefsLoaded && !dismissed && exemptionMissing) {
            showBatteryDialog = true
        }
    }

    if (showBatteryDialog && !dismissed) {
        BatteryOptimizationDialog(
            onDismiss = {
                showBatteryDialog = false
                dismissed = true
                batteryPrefs.markDismissed()
            },
            onBackFromSettings = {
                if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) {
                    showBatteryDialog = false
                    dismissed = true
                    batteryPrefs.markDismissed()
                }
            },
        )
    }
}

/**
 * Apps targeting Android 17 get no LAN traffic without `ACCESS_LOCAL_NETWORK`, and the failure
 * surfaces only as a connect timeout. Asks for it over the add screens, which stay usable either way.
 */
@Composable
internal fun LocalNetworkPermissionGate(content: @Composable () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) {
        content()
        return
    }

    val context = LocalContext.current
    val activity = LocalActivity.current
    val permission = Manifest.permission.ACCESS_LOCAL_NETWORK

    fun isGranted() = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(isGranted()) }
    // Declining only hides the dialog: public HTTPS gateways need no LAN access and must stay pairable.
    var dismissed by rememberSaveable { mutableStateOf(false) }
    // After a second denial the system refuses without showing a dialog; only Settings can grant it.
    var deniedForGood by rememberSaveable { mutableStateOf(false) }
    // The system prompt only follows a tap on Allow: an unprompted re-ask after the launch request
    // would spend the user's last denial before they know why it is asked.
    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { result ->
            granted = result
            deniedForGood = !result && activity?.shouldShowRequestPermissionRationale(permission) == false
        }

    // Picks up a grant made in Settings when the user comes back.
    LifecycleResumeEffect(Unit) {
        granted = isGranted()
        onPauseOrDispose { }
    }

    content()
    if (granted || dismissed) return
    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text(stringResource(R.string.local_network_title)) },
        text = { Text(stringResource(R.string.local_network_body)) },
        confirmButton = {
            Button(
                onClick = {
                    if (deniedForGood) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    } else {
                        launcher.launch(permission)
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (deniedForGood) R.string.local_network_open_settings else R.string.local_network_allow,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { dismissed = true }) {
                Text(stringResource(R.string.local_network_not_now))
            }
        },
    )
}

/**
 * Requests the home screen's missing runtime permissions on first composition, in one prompt:
 * an activity runs one permission request at a time and rejects a second concurrent one.
 * Local network access (Android 17+) covers gateways and servers paired before it was needed.
 * Asks once per launch: saveable state survives rotation and returning to the home screen.
 */
@Composable
internal fun StartupPermissionsEffect() {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val missing =
        remember {
            buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                    add(Manifest.permission.ACCESS_LOCAL_NETWORK)
                }
            }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        }
    if (missing.isEmpty()) return
    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { }
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            launcher.launch(missing.toTypedArray())
        }
    }
}
