package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tamimarafat.ferngeist.feature.serverlist.R

@Composable
internal fun gatewayRemoteModeLabel(mode: String): String =
    when (mode) {
        "local_only" -> stringResource(R.string.serverlist_payload_remote_local_only)
        else -> mode.replace('_', ' ')
    }
