package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.gateway.GatewayPairingPayload
import com.tamimarafat.ferngeist.gateway.GatewayStatus

private const val HTTP_SCHEME = "http"
private const val HTTPS_SCHEME = "https"

@Composable
internal fun ReviewGatewayStep(
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

        Button(
            onClick = onCheckStatus,
            enabled = !isCheckingStatus,
            modifier = Modifier.handCursor(enabled = !isCheckingStatus),
        ) {
            if (isCheckingStatus) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.serverlist_add_gateway_check))
            }
        }
    }
}

@Composable
internal fun GatewayProtocolSelector(
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GatewayProtocolOption(
            label = stringResource(R.string.serverlist_add_gateway_http),
            code = HTTP_SCHEME,
            isSelected = selected.equals(HTTP_SCHEME, ignoreCase = true) || selected.equals("ws", ignoreCase = true),
            onClick = { onSelect(HTTP_SCHEME) },
            modifier = Modifier.weight(1f),
        )
        GatewayProtocolOption(
            label = stringResource(R.string.serverlist_add_gateway_https),
            code = HTTPS_SCHEME,
            isSelected = selected.equals(HTTPS_SCHEME, ignoreCase = true) || selected.equals("wss", ignoreCase = true),
            onClick = { onSelect(HTTPS_SCHEME) },
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
                .handCursor()
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
            if (code == HTTPS_SCHEME) {
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
