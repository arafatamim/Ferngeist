package com.tamimarafat.ferngeist.feature.serverlist.ui

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.tamimarafat.ferngeist.feature.serverlist.AddGatewayViewModel
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.gateway.GatewayPairingPayloadParser

/**
 * Launches the ML Kit barcode scanner for a pairing QR payload. Pure
 * side-effect helper so the step card stays declarative.
 */
fun performQrScan(
    context: Context,
    resources: Resources,
    viewModel: AddGatewayViewModel,
) {
    val activity = context as? Activity
    if (activity == null) {
        viewModel.showMessage(resources.getString(R.string.serverlist_qr_error_no_activity))
        return
    }
    val availability = GoogleApiAvailability.getInstance()
    val statusCode = availability.isGooglePlayServicesAvailable(activity)
    if (statusCode != com.google.android.gms.common.ConnectionResult.SUCCESS) {
        val msg = availability.getErrorString(statusCode)
        viewModel.showMessage(resources.getString(R.string.serverlist_qr_error_play_services, msg))
        return
    }
    val scanner =
        try {
            GmsBarcodeScanning.getClient(activity)
        } catch (_: Exception) {
            viewModel.showMessage(resources.getString(R.string.serverlist_qr_error_no_scanner))
            return
        }

    try {
        scanner
            .startScan()
            .addOnSuccessListener { barcode: Barcode ->
                val raw = barcode.rawValue.orEmpty()
                if (raw.isBlank()) {
                    viewModel.showMessage(resources.getString(R.string.serverlist_qr_error_empty))
                    return@addOnSuccessListener
                }
                val parsed =
                    GatewayPairingPayloadParser
                        .parse(raw)
                if (parsed == null) {
                    viewModel.showMessage(resources.getString(R.string.serverlist_qr_error_invalid))
                    return@addOnSuccessListener
                }
                viewModel.updatePairingQrPayload(raw)
                viewModel.applyPairingPayload()
            }.addOnCanceledListener {
            }.addOnFailureListener { error: Exception ->
                val errorDetail =
                    error.message
                        ?: resources.getString(R.string.serverlist_error_unknown)
                viewModel.showMessage(
                    resources.getString(
                        R.string.serverlist_qr_error_scan_failed,
                        errorDetail,
                    ),
                )
            }
    } catch (_: Exception) {
        viewModel.showMessage(resources.getString(R.string.serverlist_qr_error_cannot_start))
    }
}
