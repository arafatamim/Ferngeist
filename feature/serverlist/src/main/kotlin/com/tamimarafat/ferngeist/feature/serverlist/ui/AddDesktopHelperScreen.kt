package com.tamimarafat.ferngeist.feature.serverlist.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.tamimarafat.ferngeist.feature.serverlist.AddDesktopHelperEvent
import com.tamimarafat.ferngeist.feature.serverlist.AddDesktopHelperViewModel
import com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperPairingPayload
import com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDesktopHelperScreen(
    onNavigateBack: () -> Unit,
    viewModel: AddDesktopHelperViewModel,
) {
    val name by viewModel.name.collectAsState()
    val scheme by viewModel.scheme.collectAsState()
    val host by viewModel.host.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()
    val pairingQrPayload by viewModel.pairingQrPayload.collectAsState()
    val pairingCode by viewModel.pairingCode.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val steps = remember {
        listOf(
            CompanionPairingStep(
                title = "Run ferngeist pair",
                body = "Start pairing on your computer first.",
                icon = Icons.Default.Computer,
            ),
            CompanionPairingStep(
                title = "Scan QR",
                body = "Scan the QR code, or paste the payload from the terminal.",
                icon = Icons.Default.QrCode2,
            ),
            CompanionPairingStep(
                title = "Add companion",
                body = "Check the details, name the companion, then save it.",
                icon = Icons.Default.Link,
            ),
        )
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                AddDesktopHelperEvent.HelperSaved -> onNavigateBack()
                is AddDesktopHelperEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    LaunchedEffect(viewModel.isEditMode, uiState.importedPairingPayload?.challengeId) {
        if (!viewModel.isEditMode && uiState.importedPairingPayload != null) {
            stepIndex = 2
        }
    }

    if (viewModel.isEditMode) {
        EditDesktopCompanionScreen(
            onNavigateBack = onNavigateBack,
            snackbarHostState = snackbarHostState,
            viewModel = viewModel,
            name = name,
            scheme = scheme,
            host = host,
            uiState = uiState,
        )
        return
    }

    val step = steps[stepIndex]

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalIconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        text = "Pair Desktop Companion",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                TextButton(onClick = onNavigateBack) {
                    Text("Cancel")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Step ${stepIndex + 1} of ${steps.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(12.dp))

            PairingStepProgress(currentStep = stepIndex, totalSteps = steps.size)

            Spacer(modifier = Modifier.height(20.dp))

            ElevatedCard(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
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

                    when (stepIndex) {
                        0 -> RunFerngeistPairStep()

                        1 -> ImportPairingStep(
                            pairingQrPayload = pairingQrPayload,
                            pairingCode = pairingCode,
                            importedPayload = uiState.importedPairingPayload,
                            onUpdatePayload = viewModel::updatePairingQrPayload,
                            onUpdatePairingCode = viewModel::updatePairingCode,
                            onApplyPayload = viewModel::applyPairingPayload,
                            onScanQr = {
                                val activity = context as? Activity ?: return@ImportPairingStep
                                val options = GmsBarcodeScannerOptions.Builder()
                                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                    .build()
                                GmsBarcodeScanning.getClient(activity, options)
                                    .startScan()
                                    .addOnSuccessListener { barcode: Barcode ->
                                        val payload = barcode.rawValue.orEmpty()
                                        viewModel.updatePairingQrPayload(payload)
                                        viewModel.applyPairingPayload()
                                    }
                                    .addOnFailureListener { error: Exception ->
                                        viewModel.showMessage("QR scan failed: ${error.message ?: "unknown error"}")
                                    }
                            },
                        )

                        else -> ReviewCompanionStep(
                            name = name,
                            onUpdateName = viewModel::updateName,
                            deviceName = deviceName,
                            onUpdateDeviceName = viewModel::updateDeviceName,
                            scheme = scheme,
                            onSelectScheme = viewModel::updateScheme,
                            host = host,
                            onUpdateHost = viewModel::updateHost,
                            status = uiState.status,
                            isCheckingStatus = uiState.isCheckingStatus,
                            importedPayload = uiState.importedPairingPayload,
                            onCheckStatus = viewModel::checkStatus,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (stepIndex > 0) {
                    OutlinedButton(
                        onClick = { stepIndex -= 1 },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Back")
                    }
                }

                Button(
                    onClick = {
                        when (stepIndex) {
                            0 -> stepIndex = 1
                            1 -> {
                                if (pairingQrPayload.isNotBlank()) {
                                    viewModel.applyPairingPayload()
                                } else {
                                    stepIndex = 2
                                }
                            }

                            else -> viewModel.saveDesktopCompanion()
                        }
                    },
                    enabled = !uiState.isSaving && when (stepIndex) {
                        0 -> true
                        1 -> pairingQrPayload.isNotBlank() || pairingCode.isNotBlank()
                        else -> uiState.importedPairingPayload != null || pairingCode.isNotBlank()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    if (uiState.isSaving && stepIndex == steps.lastIndex) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            when (stepIndex) {
                                0 -> "Next"
                                1 -> "Next"
                                else -> "Pair and save"
                            }
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RunFerngeistPairStep() {
    OnboardingBulletList(
        items = listOf(
            "Open a terminal on the computer running the desktop companion.",
            "Run `ferngeist pair`.",
            "Keep that terminal open.",
        ),
    )

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Command to run",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Text(
                    text = "ferngeist pair",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = "When the QR code appears, continue to the next step.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditDesktopCompanionScreen(
    onNavigateBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    viewModel: AddDesktopHelperViewModel,
    name: String,
    scheme: String,
    host: String,
    uiState: com.tamimarafat.ferngeist.feature.serverlist.AddDesktopHelperUiState,
) {
    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Edit Desktop Companion") },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(
                title = "Desktop Companion Details",
                subtitle = "Update how this companion is saved in Ferngeist.",
                icon = Icons.Default.Computer,
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = viewModel::updateName,
                    label = { Text("Name") },
                    placeholder = { Text("Workstation") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(12.dp))
                DesktopCompanionProtocolSelector(selected = scheme, onSelect = viewModel::updateScheme)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = viewModel::updateHost,
                    label = { Text("Desktop companion host") },
                    placeholder = { Text("192.168.1.42:5788") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = viewModel::checkStatus, enabled = !uiState.isCheckingStatus) {
                    if (uiState.isCheckingStatus) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Check desktop companion")
                    }
                }
                uiState.status?.let { status ->
                    Spacer(modifier = Modifier.height(12.dp))
                    CompanionStatusCard(status = status)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = viewModel::saveDesktopCompanion, enabled = !uiState.isSaving) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Save desktop companion")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportPairingStep(
    pairingQrPayload: String,
    pairingCode: String,
    importedPayload: DesktopHelperPairingPayload?,
    onUpdatePayload: (String) -> Unit,
    onUpdatePairingCode: (String) -> Unit,
    onApplyPayload: () -> Unit,
    onScanQr: () -> Unit,
) {
    val innerPadding = 14.dp

    // OnboardingBulletList(
    //     items = listOf(
    //         "Scan the QR code from the terminal, or paste the payload below.",
    //         "If you already know the companion host, entering the pairing code alone is enough.",
    //     ),
    // )

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = pairingQrPayload,
                    onValueChange = onUpdatePayload,
                    label = { Text("Pairing payload") },
                    placeholder = { Text("ferngeist-helper://pair?scheme=...") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.QrCode2, contentDescription = null) },
                )

                BoxWithConstraints {
                    val isNarrow = maxWidth < 320.dp
                    if (isNarrow) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = onScanQr,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Scan QR")
                            }
                            OutlinedButton(
                                onClick = onApplyPayload,
                                enabled = pairingQrPayload.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.QrCode2, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Use payload")
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = onScanQr,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Scan QR")
                            }
                            OutlinedButton(
                                onClick = onApplyPayload,
                                enabled = pairingQrPayload.isNotBlank(),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.QrCode2, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Use payload")
                            }
                        }
                    }
                }

                importedPayload?.let { payload ->
                    ImportedPayloadCard(payload = payload)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(
                    text = "OR",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = onUpdatePairingCode,
                    label = { Text("Pairing code") },
                    placeholder = { Text("Enter 6-digit code") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                    singleLine = true,
                )

                Text(
                    text = "Only needed if the companion host is not already known.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReviewCompanionStep(
    name: String,
    onUpdateName: (String) -> Unit,
    deviceName: String,
    onUpdateDeviceName: (String) -> Unit,
    scheme: String,
    onSelectScheme: (String) -> Unit,
    host: String,
    onUpdateHost: (String) -> Unit,
    status: DesktopHelperStatus?,
    isCheckingStatus: Boolean,
    importedPayload: DesktopHelperPairingPayload?,
    onCheckStatus: () -> Unit,
) {
    importedPayload?.let { payload ->
        ImportedPayloadCard(payload = payload)
        Spacer(modifier = Modifier.height(4.dp))
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = onUpdateName,
                label = { Text("Name") },
                placeholder = { Text("Workstation") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = deviceName,
                onValueChange = onUpdateDeviceName,
                label = { Text("This device name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            DesktopCompanionProtocolSelector(selected = scheme, onSelect = onSelectScheme)
            OutlinedTextField(
                value = host,
                onValueChange = onUpdateHost,
                label = { Text("Desktop companion host") },
                placeholder = { Text("192.168.1.42:5788") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
            )

            Text(
                text = "The payload usually fills these details for you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(onClick = onCheckStatus, enabled = !isCheckingStatus) {
                if (isCheckingStatus) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Check desktop companion")
                }
            }

            status?.let { CompanionStatusCard(status = it) }
        }
    }
}

@Composable
private fun ImportedPayloadCard(payload: DesktopHelperPairingPayload) {
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
                "Pairing payload loaded",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text("${payload.scheme}://${payload.host}", style = MaterialTheme.typography.bodyMedium)
            Text("Challenge ${payload.challengeId.take(10)}...", style = MaterialTheme.typography.bodySmall)
            Text("Code ${payload.code}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CompanionStatusCard(status: DesktopHelperStatus) {
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
            Text("Version ${status.version}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Remote: ${status.remote.mode ?: "local_only"}",
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
            val color = if (index <= currentStep) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .background(color = color, shape = RoundedCornerShape(999.dp))
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
private fun DesktopCompanionProtocolSelector(
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CompanionProtocolOption(
            label = "HTTP",
            code = "http",
            isSelected = selected.equals("http", ignoreCase = true) || selected.equals("ws", ignoreCase = true),
            onClick = { onSelect("http") },
            modifier = Modifier.weight(1f),
        )
        CompanionProtocolOption(
            label = "HTTPS",
            code = "https",
            isSelected = selected.equals("https", ignoreCase = true) || selected.equals("wss", ignoreCase = true),
            onClick = { onSelect("https") },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CompanionProtocolOption(
    label: String,
    code: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = if (isSelected) {
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
        modifier = modifier.height(52.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (code == "https") {
                Icon(
                    Icons.Default.Key,
                    contentDescription = null,
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

private data class CompanionPairingStep(
    val title: String,
    val body: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)
