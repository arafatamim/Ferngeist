package com.tamimarafat.ferngeist.feature.serverlist.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.formContentMaxWidth
import com.tamimarafat.ferngeist.feature.serverlist.AddGatewayEvent
import com.tamimarafat.ferngeist.feature.serverlist.AddGatewayUiState
import com.tamimarafat.ferngeist.feature.serverlist.AddGatewayViewModel
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.gateway.GatewayPairingPayload
import com.tamimarafat.ferngeist.gateway.GatewayStatus

private const val CHALLENGE_ID_DISPLAY_LENGTH = 10

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
            FilledTonalIconButton(onClick = onBack) {
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
    ElevatedCard(
        modifier = Modifier.weight(1f),
        colors =
            CardDefaults.elevatedCardColors(
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
            modifier = Modifier.weight(1f),
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
            Button(
                onClick = onConfirm,
                enabled = code.isNotBlank() && !isLoading,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.serverlist_add_gateway_pair_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text(stringResource(R.string.serverlist_add_gateway_cancel))
            }
        },
    )
}

@Composable
private fun RunFerngeistPairStep() {
    val instruction1 = stringResource(R.string.serverlist_add_gateway_instruction_1)
    val instruction2 = stringResource(R.string.serverlist_add_gateway_instruction_2)
    val instruction3 = stringResource(R.string.serverlist_add_gateway_instruction_3)
    val instruction4 = stringResource(R.string.serverlist_add_gateway_instruction_4)
    OnboardingBulletList(
        items = listOf(instruction1, instruction2, instruction3, instruction4),
    )

    ElevatedCard(
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.serverlist_add_gateway_command_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Text(
                    text = "ferngeist-gateway pair",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.serverlist_add_gateway_scan_instruction),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditGatewayScreen(
    onNavigateBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    viewModel: AddGatewayViewModel,
    name: String,
    scheme: String,
    host: String,
    uiState: com.tamimarafat.ferngeist.feature.serverlist.AddGatewayUiState,
) {
    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(stringResource(R.string.serverlist_add_gateway_edit_title)) },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.serverlist_back_desc),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier
                        .formContentMaxWidth()
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                EditGatewayDetailsSection(
                    name = name,
                    scheme = scheme,
                    host = host,
                    uiState = uiState,
                    onUpdateName = viewModel::updateName,
                    onSelectScheme = viewModel::updateScheme,
                    onUpdateHost = viewModel::updateHost,
                    onCheckStatus = viewModel::checkStatus,
                    onSave = viewModel::saveGateway,
                )
            }
        }
    }
}

@Composable
private fun EditGatewayDetailsSection(
    name: String,
    scheme: String,
    host: String,
    uiState: com.tamimarafat.ferngeist.feature.serverlist.AddGatewayUiState,
    onUpdateName: (String) -> Unit,
    onSelectScheme: (String) -> Unit,
    onUpdateHost: (String) -> Unit,
    onCheckStatus: () -> Unit,
    onSave: () -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.serverlist_add_gateway_details_title),
        subtitle = stringResource(R.string.serverlist_add_gateway_details_subtitle),
        icon = Icons.Default.Computer,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onUpdateName,
            label = { Text(stringResource(R.string.serverlist_add_gateway_name_label)) },
            placeholder = { Text(stringResource(R.string.serverlist_add_gateway_name_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(12.dp))
        GatewayProtocolSelector(selected = scheme, onSelect = onSelectScheme)
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = host,
            onValueChange = onUpdateHost,
            label = { Text(stringResource(R.string.serverlist_add_gateway_host_label)) },
            placeholder = { Text(stringResource(R.string.serverlist_add_gateway_host_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(
                    Icons.Default.Link,
                    contentDescription = stringResource(R.string.serverlist_add_server_host_label),
                )
            },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onCheckStatus, enabled = !uiState.isCheckingStatus) {
            if (uiState.isCheckingStatus) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.serverlist_add_gateway_check))
            }
        }
        uiState.status?.let { status ->
            Spacer(modifier = Modifier.height(12.dp))
            GatewayStatusCard(status = status)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onSave, enabled = !uiState.isSaving) {
            if (uiState.isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.serverlist_add_gateway_save))
            }
        }
    }
}

@Composable
private fun ImportPairingStep(
    pairingQrPayload: String,
    importedPayload: GatewayPairingPayload?,
    onUpdatePayload: (String) -> Unit,
    onScanQr: () -> Unit,
) {
    val innerPadding = 14.dp

    ElevatedCard(
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = pairingQrPayload,
                onValueChange = onUpdatePayload,
                label = { Text(stringResource(R.string.serverlist_add_gateway_payload_label)) },
                placeholder = { Text(stringResource(R.string.serverlist_add_gateway_payload_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(
                        Icons.Default.ContentPaste,
                        contentDescription = null,
                    )
                },
            )

            if (booleanResource(R.bool.serverlist_qr_scan_available)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.serverlist_add_gateway_or),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f))
                }

                OutlinedButton(
                    onClick = onScanQr,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.serverlist_add_gateway_step_scan_title))
                }
            }

            importedPayload?.let { payload ->
                ImportedPayloadCard(payload = payload)
            }
        }
    }
}

@Composable
private fun ReviewGatewayStep(
    name: String,
    onUpdateName: (String) -> Unit,
    deviceName: String,
    onUpdateDeviceName: (String) -> Unit,
    scheme: String,
    onSelectScheme: (String) -> Unit,
    host: String,
    onUpdateHost: (String) -> Unit,
    status: GatewayStatus?,
    isCheckingStatus: Boolean,
    importedPayload: GatewayPairingPayload?,
    onCheckStatus: () -> Unit,
) {
    importedPayload?.let { payload ->
        ImportedPayloadCard(payload = payload)
        Spacer(modifier = Modifier.height(4.dp))
    }

    GatewayReviewForm(
        name = name,
        onUpdateName = onUpdateName,
        deviceName = deviceName,
        onUpdateDeviceName = onUpdateDeviceName,
        scheme = scheme,
        onSelectScheme = onSelectScheme,
        host = host,
        onUpdateHost = onUpdateHost,
        status = status,
        isCheckingStatus = isCheckingStatus,
        onCheckStatus = onCheckStatus,
    )
}

@Composable
private fun GatewayReviewActions(
    isCheckingStatus: Boolean,
    onCheckStatus: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = stringResource(R.string.serverlist_add_gateway_manual_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(onClick = onCheckStatus, enabled = !isCheckingStatus) {
            if (isCheckingStatus) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.serverlist_add_gateway_check))
            }
        }
    }
}

@Composable
private fun GatewayReviewForm(
    name: String,
    onUpdateName: (String) -> Unit,
    deviceName: String,
    onUpdateDeviceName: (String) -> Unit,
    scheme: String,
    onSelectScheme: (String) -> Unit,
    host: String,
    onUpdateHost: (String) -> Unit,
    status: GatewayStatus?,
    isCheckingStatus: Boolean,
    onCheckStatus: () -> Unit,
) {
    ElevatedCard(
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = onUpdateName,
                label = { Text(stringResource(R.string.serverlist_add_gateway_name_label)) },
                placeholder = { Text(stringResource(R.string.serverlist_add_gateway_name_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = deviceName,
                onValueChange = onUpdateDeviceName,
                label = { Text(stringResource(R.string.serverlist_add_gateway_device_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            GatewayProtocolSelector(selected = scheme, onSelect = onSelectScheme)
            OutlinedTextField(
                value = host,
                onValueChange = onUpdateHost,
                label = { Text(stringResource(R.string.serverlist_add_gateway_host_label)) },
                placeholder = { Text(stringResource(R.string.serverlist_add_gateway_host_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(
                        Icons.Default.Link,
                        contentDescription = stringResource(R.string.serverlist_add_server_host_label),
                    )
                },
            )

            GatewayReviewActions(
                isCheckingStatus = isCheckingStatus,
                onCheckStatus = onCheckStatus,
            )

            status?.let { GatewayStatusCard(status = it) }
        }
    }
}

@Composable
private fun ImportedPayloadCard(payload: GatewayPairingPayload) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.serverlist_add_gateway_imported_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.serverlist_gateway_url_format, payload.scheme, payload.host),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    R.string.serverlist_add_gateway_challenge_label,
                    payload.challengeId.take(CHALLENGE_ID_DISPLAY_LENGTH),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.serverlist_payload_code_label, payload.code),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun GatewayStatusCard(status: GatewayStatus) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(status.name, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.serverlist_payload_version_label, status.version),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(
                    R.string.serverlist_payload_remote_label,
                    status.remote.mode?.let { gatewayRemoteModeLabel(it) }
                        ?: stringResource(R.string.serverlist_payload_remote_local_only),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            status.remote.warning?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
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
private fun OnboardingBulletList(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { item ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "-",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun GatewayProtocolSelector(
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GatewayProtocolOption(
            label = stringResource(R.string.serverlist_add_gateway_http),
            code = "http",
            isSelected = selected.equals("http", ignoreCase = true) || selected.equals("ws", ignoreCase = true),
            onClick = { onSelect("http") },
            modifier = Modifier.weight(1f),
        )
        GatewayProtocolOption(
            label = stringResource(R.string.serverlist_add_gateway_https),
            code = "https",
            isSelected = selected.equals("https", ignoreCase = true) || selected.equals("wss", ignoreCase = true),
            onClick = { onSelect("https") },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun GatewayProtocolOption(
    label: String,
    code: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor =
        if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    val contentColor =
        if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val borderColor =
        if (isSelected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        contentColor = contentColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier =
            modifier
                .height(52.dp)
                .semantics {
                    contentDescription = label
                },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (code == "https") {
                Icon(
                    Icons.Default.Key,
                    contentDescription = stringResource(R.string.serverlist_add_gateway_https),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

private data class GatewayPairingStep(
    val title: String,
    val body: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)
