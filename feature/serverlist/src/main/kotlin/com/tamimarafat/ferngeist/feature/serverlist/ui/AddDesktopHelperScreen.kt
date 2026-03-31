package com.tamimarafat.ferngeist.feature.serverlist.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.tamimarafat.ferngeist.feature.serverlist.AddDesktopHelperEvent
import com.tamimarafat.ferngeist.feature.serverlist.DesktopHelperPairingInputMode
import com.tamimarafat.ferngeist.feature.serverlist.AddDesktopHelperViewModel

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
    val pairingInputMode by viewModel.pairingInputMode.collectAsState()
    val pairingQrPayload by viewModel.pairingQrPayload.collectAsState()
    val pairingCode by viewModel.pairingCode.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                AddDesktopHelperEvent.HelperSaved -> onNavigateBack()
                is AddDesktopHelperEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (viewModel.isEditMode) "Edit Desktop Companion" else "Pair Desktop Companion")
                },
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
            SectionCard(title = "Helper Details", icon = Icons.Default.Computer) {
                OutlinedTextField(
                    value = name,
                    onValueChange = viewModel::updateName,
                    label = { Text("Name") },
                    placeholder = { Text("Workstation") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(12.dp))
                ProtocolSelector(selected = scheme, onSelect = viewModel::updateScheme)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = viewModel::updateHost,
                    label = { Text("Helper host") },
                    placeholder = { Text("192.168.1.42:5788") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = viewModel::checkStatus, enabled = !uiState.isCheckingStatus) {
                    if (uiState.isCheckingStatus) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp))
                    } else {
                        Text("Check helper")
                    }
                }
                uiState.status?.let { status ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(status.name, fontWeight = FontWeight.SemiBold)
                            Text("Version ${status.version}", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "Remote: ${status.remote.mode ?: "local_only"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            status.remote.warning?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            SectionCard(title = "Pairing", icon = Icons.Default.Key) {
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = viewModel::updateDeviceName,
                    label = { Text("This device name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "You can scan or paste a QR pairing payload, or enter a code manually with the helper host.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                PairingModeOption(
                    selected = pairingInputMode == DesktopHelperPairingInputMode.QR_PAYLOAD,
                    title = "QR payload",
                    description = "Use the helper QR code when it includes host details and the pairing code.",
                    onClick = { viewModel.updatePairingInputMode(DesktopHelperPairingInputMode.QR_PAYLOAD) },
                )
                PairingModeOption(
                    selected = pairingInputMode == DesktopHelperPairingInputMode.MANUAL_CODE,
                    title = "Manual code",
                    description = "Type the host yourself and enter the code shown by the helper.",
                    onClick = { viewModel.updatePairingInputMode(DesktopHelperPairingInputMode.MANUAL_CODE) },
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (pairingInputMode == DesktopHelperPairingInputMode.QR_PAYLOAD) {
                    OutlinedTextField(
                        value = pairingQrPayload,
                        onValueChange = viewModel::updatePairingQrPayload,
                        label = { Text("QR payload") },
                        placeholder = { Text("ferngeist-helper://pair?host=192.168.1.42:5788&code=123456") },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.QrCode2, contentDescription = null) },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val activity = context as? Activity ?: return@OutlinedButton
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
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scan QR")
                        }
                        OutlinedButton(onClick = viewModel::applyPairingPayload) {
                            Icon(Icons.Default.QrCode2, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Apply payload")
                        }
                    }
                }
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = viewModel::updatePairingCode,
                    label = { Text("Pairing code") },
                    placeholder = { Text("123456") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                uiState.importedPairingPayload?.let { payload ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Loaded pairing payload for ${payload.host}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = viewModel::startPairing, enabled = !uiState.isPairing) {
                    Text("Start pairing")
                }
                uiState.pairingChallenge?.let { challenge ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Pairing code", style = MaterialTheme.typography.labelLarge)
                            Text(challenge.code, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("Expires at ${challenge.expiresAt}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = viewModel::completePairingAndSave, enabled = !uiState.isSaving) {
                        Text(if (viewModel.isEditMode) "Save helper" else "Pair and save")
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingModeOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
