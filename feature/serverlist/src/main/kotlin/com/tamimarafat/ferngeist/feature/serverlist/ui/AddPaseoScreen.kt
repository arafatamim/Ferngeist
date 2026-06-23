package com.tamimarafat.ferngeist.feature.serverlist.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.tamimarafat.ferngeist.feature.serverlist.AddPaseoViewModel

/**
 * Minimal pairing screen for a local-network Paseo daemon: enter a host:port (and
 * optional bearer password) or scan a connection QR, then pair. On success the daemon's
 * providers are persisted as launchable targets and the screen closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPaseoScreen(
    onNavigateBack: () -> Unit,
    viewModel: AddPaseoViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.paired) {
        if (state.paired) onNavigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair Paseo daemon") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text =
                    "Scan the Paseo pairing QR or paste its pairing link (app.paseo.sh/#offer=…). " +
                        "You can also enter a local address directly (host:port). The pairing link " +
                        "connects over Paseo's end-to-end encrypted relay; no password is needed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::updateName,
                label = { Text("Name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.endpoint,
                onValueChange = viewModel::updateEndpoint,
                label = { Text("Host:port or scanned connection") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { startQrScan(context as? Activity, viewModel) }) {
                        Icon(Icons.Default.QrCode2, contentDescription = "Scan QR")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::updatePassword,
                label = { Text("Password (if set)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = viewModel::pair,
                enabled = state.endpoint.isNotBlank() && !state.isPairing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isPairing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Pair")
                }
            }
        }
    }
}

private fun startQrScan(
    activity: Activity?,
    viewModel: AddPaseoViewModel,
) {
    if (activity == null) {
        viewModel.showMessage("Cannot start the scanner from this screen.")
        return
    }
    val availability = GoogleApiAvailability.getInstance()
    if (availability.isGooglePlayServicesAvailable(activity) != ConnectionResult.SUCCESS) {
        viewModel.showMessage("Google Play services is required to scan a QR code.")
        return
    }
    val scanner =
        try {
            GmsBarcodeScanning.getClient(activity)
        } catch (_: Exception) {
            viewModel.showMessage("No QR scanner is available on this device.")
            return
        }
    try {
        scanner
            .startScan()
            .addOnSuccessListener { barcode: Barcode ->
                val raw = barcode.rawValue.orEmpty()
                if (raw.isBlank()) {
                    viewModel.showMessage("The scanned code was empty.")
                } else {
                    viewModel.onQrScanned(raw)
                }
            }
            .addOnCanceledListener { }
            .addOnFailureListener { error ->
                viewModel.showMessage("Scan failed: ${error.message ?: "unknown error"}")
            }
    } catch (_: Exception) {
        viewModel.showMessage("Could not start the QR scanner.")
    }
}
