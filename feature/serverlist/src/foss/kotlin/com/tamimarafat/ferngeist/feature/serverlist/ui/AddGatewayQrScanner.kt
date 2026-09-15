package com.tamimarafat.ferngeist.feature.serverlist.ui

import android.content.Context
import android.content.res.Resources
import com.tamimarafat.ferngeist.feature.serverlist.AddGatewayViewModel
import com.tamimarafat.ferngeist.feature.serverlist.R

/**
 * FOSS flavour: no proprietary barcode scanner is linked, so QR scanning is unavailable.
 * Unreachable in practice — the scan button is hidden when
 * `serverlist_qr_scan_available` is false — but the seam requires a body.
 * Reuses the paste-first error copy so any stray call still guides correctly.
 */
fun performQrScan(
    context: Context,
    resources: Resources,
    viewModel: AddGatewayViewModel,
) {
    viewModel.showMessage(resources.getString(R.string.serverlist_error_scan_or_paste))
}
