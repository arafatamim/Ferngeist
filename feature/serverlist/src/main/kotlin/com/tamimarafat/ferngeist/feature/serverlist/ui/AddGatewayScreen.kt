package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.formContentMaxWidth
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.feature.serverlist.AddGatewayEvent
import com.tamimarafat.ferngeist.feature.serverlist.AddGatewayUiState
import com.tamimarafat.ferngeist.feature.serverlist.AddGatewayViewModel
import com.tamimarafat.ferngeist.feature.serverlist.R
import kotlin.math.abs

private const val STEP_CARD_SLIDE_MS = 260
private const val STEP_CARD_SLIDE_FRACTION = 0.08f
private const val STEP_CARD_MIN_ALPHA = 0.6f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddGatewayScreen(
    onNavigateBack: () -> Unit,
    viewModel: AddGatewayViewModel,
) {
    val ui = rememberAddGatewayUi(viewModel, onNavigateBack)

    if (viewModel.isEditMode) {
        EditGatewayScreen(
            onNavigateBack = onNavigateBack,
            snackbarHostState = ui.snackbarHostState,
            viewModel = viewModel,
            name = ui.name,
            scheme = ui.scheme,
            host = ui.host,
            uiState = ui.uiState,
        )
        return
    }

    AddGatewayFlow(
        ui = ui,
        viewModel = viewModel,
        onNavigateBack = onNavigateBack,
    )
}

@Composable
private fun AddGatewayFlow(
    ui: AddGatewayUi,
    viewModel: AddGatewayViewModel,
    onNavigateBack: () -> Unit,
) {
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    var pairingDialogRequest by rememberSaveable { mutableIntStateOf(0) }
    val steps = rememberGatewaySteps()
    val step = steps[stepIndex]

    Scaffold(
        snackbarHost = { SnackbarHost(ui.snackbarHostState) },
    ) { padding ->
        AddGatewayScaffoldContent(
            modifier = Modifier.padding(padding),
            stepIndex = stepIndex,
            totalSteps = steps.size,
            step = step,
            pairingQrPayload = ui.pairingQrPayload,
            name = ui.name,
            deviceName = ui.deviceName,
            scheme = ui.scheme,
            host = ui.host,
            uiState = ui.uiState,
            onBack = {
                if (stepIndex > 0) {
                    stepIndex -= 1
                } else {
                    onNavigateBack()
                }
            },
            onUpdatePayload = ui.onUpdatePayload,
            onScanQr = ui.onScanQr,
            onUpdateName = viewModel::updateName,
            onUpdateDeviceName = viewModel::updateDeviceName,
            onSelectScheme = viewModel::updateScheme,
            onUpdateHost = viewModel::updateHost,
            onCheckStatus = viewModel::checkStatus,
            onNext = {
                when (stepIndex) {
                    0 -> stepIndex = 1
                    1 -> stepIndex = 2
                    else ->
                        if (ui.uiState.importedPairingPayload != null) {
                            viewModel.saveGateway()
                        } else {
                            pairingDialogRequest += 1
                        }
                }
            },
        )
    }

    AddGatewayPairingDialogHost(
        openRequest = pairingDialogRequest,
        isSaving = ui.uiState.isSaving,
        onConfirm = viewModel::pairAndSaveWithCode,
    )
}

@Composable
private fun AddGatewayPairingDialogHost(
    openRequest: Int,
    isSaving: Boolean,
    onConfirm: (String) -> Unit,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    var dialogPairingCode by rememberSaveable { mutableStateOf("") }
    var dialogOpenedWhileSaving by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(openRequest) {
        if (openRequest > 0) {
            visible = true
        }
    }

    if (!visible) return
    PairingCodeDialog(
        code = dialogPairingCode,
        onCodeChange = { dialogPairingCode = it },
        onDismiss = {
            if (!isSaving) {
                visible = false
                dialogPairingCode = ""
                dialogOpenedWhileSaving = false
            }
        },
        onConfirm = {
            dialogOpenedWhileSaving = true
            onConfirm(dialogPairingCode)
        },
        isLoading = isSaving,
    )
    LaunchedEffect(isSaving) {
        if (dialogOpenedWhileSaving && !isSaving) {
            visible = false
            dialogPairingCode = ""
            dialogOpenedWhileSaving = false
        }
    }
}

@Composable
private fun rememberAddGatewayUi(
    viewModel: AddGatewayViewModel,
    onNavigateBack: () -> Unit,
): AddGatewayUi {
    val name by viewModel.name.collectAsState()
    val scheme by viewModel.scheme.collectAsState()
    val host by viewModel.host.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()
    val pairingQrPayload by viewModel.pairingQrPayload.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                AddGatewayEvent.GatewaySaved -> onNavigateBack()
                is AddGatewayEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    return AddGatewayUi(
        name = name,
        scheme = scheme,
        host = host,
        deviceName = deviceName,
        pairingQrPayload = pairingQrPayload,
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onScanQr = { performQrScan(context, resources, viewModel) },
        onUpdatePayload = { value ->
            viewModel.updatePairingQrPayload(value)
            val parsed =
                com.tamimarafat.ferngeist.gateway.GatewayPairingPayloadParser
                    .parse(value)
            if (parsed != null) {
                viewModel.applyPairingPayload()
            }
        },
    )
}

private data class AddGatewayUi(
    val name: String,
    val scheme: String,
    val host: String,
    val deviceName: String,
    val pairingQrPayload: String,
    val uiState: AddGatewayUiState,
    val snackbarHostState: SnackbarHostState,
    val onScanQr: () -> Unit,
    val onUpdatePayload: (String) -> Unit,
)

@Composable
private fun AddGatewayScaffoldContent(
    modifier: Modifier = Modifier,
    stepIndex: Int,
    totalSteps: Int,
    step: GatewayPairingStep,
    pairingQrPayload: String,
    name: String,
    deviceName: String,
    scheme: String,
    host: String,
    uiState: AddGatewayUiState,
    onBack: () -> Unit,
    onUpdatePayload: (String) -> Unit,
    onScanQr: () -> Unit,
    onUpdateName: (String) -> Unit,
    onUpdateDeviceName: (String) -> Unit,
    onSelectScheme: (String) -> Unit,
    onUpdateHost: (String) -> Unit,
    onCheckStatus: () -> Unit,
    onNext: () -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .formContentMaxWidth()
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            AddGatewayTopBar(
                stepIndex = stepIndex,
                totalSteps = totalSteps,
                onBack = onBack,
            )

            Spacer(modifier = Modifier.height(20.dp))

            AddGatewayStepCard(
                step = step,
                stepIndex = stepIndex,
                pairingQrPayload = pairingQrPayload,
                name = name,
                deviceName = deviceName,
                scheme = scheme,
                host = host,
                uiState = uiState,
                onUpdatePayload = onUpdatePayload,
                onScanQr = onScanQr,
                onUpdateName = onUpdateName,
                onUpdateDeviceName = onUpdateDeviceName,
                onSelectScheme = onSelectScheme,
                onUpdateHost = onUpdateHost,
                onCheckStatus = onCheckStatus,
            )

            Spacer(modifier = Modifier.height(16.dp))

            AddGatewayBottomBar(
                stepIndex = stepIndex,
                totalSteps = totalSteps,
                hasImportedPayload = uiState.importedPairingPayload != null,
                isSaving = uiState.isSaving,
                onNext = onNext,
            )
        }
    }
}

@Composable
private fun rememberGatewaySteps(): List<GatewayPairingStep> {
    val stepRunTitle = stringResource(R.string.serverlist_add_gateway_step_run_title)
    val stepRunBody = stringResource(R.string.serverlist_add_gateway_step_run_body)
    val stepScanBody =
        if (booleanResource(R.bool.serverlist_qr_scan_available)) {
            stringResource(R.string.serverlist_add_gateway_step_scan_body)
        } else {
            stringResource(R.string.serverlist_add_gateway_step_paste_body)
        }
    val stepScanTitle =
        if (booleanResource(R.bool.serverlist_qr_scan_available)) {
            stringResource(R.string.serverlist_add_gateway_step_scan_title)
        } else {
            stringResource(R.string.serverlist_add_gateway_step_paste_title)
        }
    val stepScanIcon =
        if (booleanResource(R.bool.serverlist_qr_scan_available)) {
            Icons.Default.QrCode2
        } else {
            Icons.Default.ContentPaste
        }
    val stepAddTitle = stringResource(R.string.serverlist_add_gateway_step_add_title)
    val stepAddBody = stringResource(R.string.serverlist_add_gateway_step_add_body)
    return remember(stepScanTitle, stepScanBody) {
        listOf(
            GatewayPairingStep(
                title = stepRunTitle,
                body = stepRunBody,
                icon = Icons.Default.Computer,
            ),
            GatewayPairingStep(
                title = stepScanTitle,
                body = stepScanBody,
                icon = stepScanIcon,
            ),
            GatewayPairingStep(
                title = stepAddTitle,
                body = stepAddBody,
                icon = Icons.Default.Link,
            ),
        )
    }
}

@Composable
private fun AddGatewayTopBar(
    stepIndex: Int,
    totalSteps: Int,
    onBack: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalIconButton(onClick = onBack, modifier = Modifier.handCursor()) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.serverlist_back_desc),
                )
            }
            Text(
                text = stringResource(R.string.serverlist_add_gateway_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.serverlist_add_gateway_step_count, stepIndex + 1, totalSteps),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(12.dp))

        PairingStepProgress(currentStep = stepIndex, totalSteps = totalSteps)
    }
}

@Composable
private fun PairingStepProgress(
    currentStep: Int,
    totalSteps: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(totalSteps) { index ->
            val color =
                if (index <= currentStep) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .background(color = color, shape = RoundedCornerShape(999.dp)),
            )
        }
    }
}

@Composable
private fun ColumnScope.AddGatewayStepCard(
    step: GatewayPairingStep,
    stepIndex: Int,
    pairingQrPayload: String,
    name: String,
    deviceName: String,
    scheme: String,
    host: String,
    uiState: AddGatewayUiState,
    onUpdatePayload: (String) -> Unit,
    onScanQr: () -> Unit,
    onUpdateName: (String) -> Unit,
    onUpdateDeviceName: (String) -> Unit,
    onSelectScheme: (String) -> Unit,
    onUpdateHost: (String) -> Unit,
    onCheckStatus: () -> Unit,
) {
    // Slide in the direction of travel: forward steps enter from the right, back steps from the
    // left, so the wizard reads as one continuous flow rather than a hard cut between steps.
    val previousStep = remember { mutableIntStateOf(stepIndex) }
    val direction = if (stepIndex >= previousStep.intValue) 1 else -1
    val slide = remember { Animatable(0f) }
    LaunchedEffect(stepIndex) {
        previousStep.intValue = stepIndex
        slide.snapTo(STEP_CARD_SLIDE_FRACTION * direction)
        slide.animateTo(0f, tween(STEP_CARD_SLIDE_MS))
    }

    Card(
        modifier =
            Modifier
                .weight(1f)
                .graphicsLayer {
                    translationX = slide.value * size.width
                    alpha = STEP_CARD_MIN_ALPHA + (1f - STEP_CARD_MIN_ALPHA) *
                        (1f - abs(slide.value))
                },
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepHeader(step = step)

            StepBody(
                stepIndex = stepIndex,
                pairingQrPayload = pairingQrPayload,
                name = name,
                deviceName = deviceName,
                scheme = scheme,
                host = host,
                uiState = uiState,
                onUpdatePayload = onUpdatePayload,
                onScanQr = onScanQr,
                onUpdateName = onUpdateName,
                onUpdateDeviceName = onUpdateDeviceName,
                onSelectScheme = onSelectScheme,
                onUpdateHost = onUpdateHost,
                onCheckStatus = onCheckStatus,
            )
        }
    }
}

@Composable
private fun StepHeader(step: GatewayPairingStep) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(56.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = step.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }

    Text(
        text = step.title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )

    Text(
        text = step.body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StepBody(
    stepIndex: Int,
    pairingQrPayload: String,
    name: String,
    deviceName: String,
    scheme: String,
    host: String,
    uiState: AddGatewayUiState,
    onUpdatePayload: (String) -> Unit,
    onScanQr: () -> Unit,
    onUpdateName: (String) -> Unit,
    onUpdateDeviceName: (String) -> Unit,
    onSelectScheme: (String) -> Unit,
    onUpdateHost: (String) -> Unit,
    onCheckStatus: () -> Unit,
) {
    when (stepIndex) {
        0 -> RunFerngeistPairStep()

        1 ->
            ImportPairingStep(
                pairingQrPayload = pairingQrPayload,
                importedPayload = uiState.importedPairingPayload,
                onUpdatePayload = onUpdatePayload,
                onScanQr = onScanQr,
            )

        else ->
            ReviewGatewayStep(
                name = name,
                onUpdateName = onUpdateName,
                deviceName = deviceName,
                onUpdateDeviceName = onUpdateDeviceName,
                scheme = scheme,
                onSelectScheme = onSelectScheme,
                host = host,
                onUpdateHost = onUpdateHost,
                status = uiState.status,
                isCheckingStatus = uiState.isCheckingStatus,
                importedPayload = uiState.importedPairingPayload,
                onCheckStatus = onCheckStatus,
            )
    }
}

@Composable
private fun AddGatewayBottomBar(
    stepIndex: Int,
    totalSteps: Int,
    hasImportedPayload: Boolean,
    isSaving: Boolean,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onNext,
            enabled = !isSaving,
            modifier = Modifier.weight(1f).handCursor(enabled = !isSaving),
        ) {
            Text(
                when (stepIndex) {
                    0 -> stringResource(R.string.serverlist_add_gateway_next)
                    1 ->
                        if (hasImportedPayload) {
                            stringResource(R.string.serverlist_add_gateway_next)
                        } else {
                            stringResource(R.string.serverlist_add_gateway_skip)
                        }
                    else -> stringResource(R.string.serverlist_add_gateway_pair)
                },
            )
            if (stepIndex < totalSteps - 1) {
                Spacer(modifier = Modifier.size(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun PairingCodeDialog(
    code: String,
    onCodeChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    isLoading: Boolean,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.serverlist_add_gateway_pairing_code_title)) },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = onCodeChange,
                label = { Text(stringResource(R.string.serverlist_add_gateway_pairing_code_label)) },
                placeholder = { Text(stringResource(R.string.serverlist_add_gateway_placeholder_code)) },
                singleLine = true,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            val canConfirm = code.isNotBlank() && !isLoading
            Button(
                onClick = onConfirm,
                enabled = canConfirm,
                modifier = Modifier.handCursor(enabled = canConfirm),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.serverlist_add_gateway_pair_save))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading,
                modifier = Modifier.handCursor(enabled = !isLoading),
            ) {
                Text(stringResource(R.string.serverlist_add_gateway_cancel))
            }
        },
    )
}

private data class GatewayPairingStep(
    val title: String,
    val body: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)
