package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.gateway.GatewayPairingPayload
import com.tamimarafat.ferngeist.gateway.GatewayStatus

private const val CHALLENGE_ID_DISPLAY_LENGTH = 10

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ImportedPayloadCard(payload: GatewayPairingPayload) {
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
                style = MaterialTheme.typography.labelLargeEmphasized,
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun GatewayStatusCard(status: GatewayStatus) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(status.name, style = MaterialTheme.typography.bodyLargeEmphasized)
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
