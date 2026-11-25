package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.gateway.GatewayPairingPayload
import com.tamimarafat.ferngeist.gateway.GatewayStatus

private const val CHALLENGE_ID_DISPLAY_LENGTH = 10

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ImportedPayloadCard(payload: GatewayPairingPayload) {
    ResultCard(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        icon = Icons.Default.CheckCircle,
        title = stringResource(R.string.serverlist_add_gateway_imported_title),
    ) {
        Text(
            stringResource(R.string.serverlist_gateway_url_format, payload.scheme, payload.host),
            style = MaterialTheme.typography.bodyLarge,
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun GatewayStatusCard(status: GatewayStatus) {
    val warning = status.remote.warning
    ResultCard(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        icon = if (warning == null) Icons.Default.CheckCircle else Icons.Default.Warning,
        title = status.name,
    ) {
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
        warning?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Icon-led summary tile shared by the "payload loaded" and "gateway reachable" confirmations. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ResultCard(
    color: Color,
    contentColor: Color,
    icon: ImageVector,
    title: String,
    details: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = color,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 2.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
                details()
            }
        }
    }
}
