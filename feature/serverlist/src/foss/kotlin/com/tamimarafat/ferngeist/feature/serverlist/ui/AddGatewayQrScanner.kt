package com.tamimarafat.ferngeist.feature.serverlist.ui

import android.content.Context
import android.content.res.Resources
import com.tamimarafat.ferngeist.feature.serverlist.AddGatewayViewModel
import com.tamimarafat.ferngeist.feature.serverlist.R

/**
 * FOSS flavour: no proprietary barcode scanner is linked, so QR scanning is unavailable.
 * Directs the user to paste the pairing payload instead. The Google flavour uses ML Kit.
 */
fun performQrScan(
    context: Context,
    resources: Resources,
    viewModel: AddGatewayViewModel,
) {
    viewModel.showMessage(resources.getString(R.string.serverlist_qr_error_no_scanner))
}
