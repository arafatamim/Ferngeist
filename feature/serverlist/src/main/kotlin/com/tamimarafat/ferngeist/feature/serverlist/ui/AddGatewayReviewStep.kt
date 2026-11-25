package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        FormSection(title = stringResource(R.string.serverlist_add_gateway_section_connection)) {
            Text(
                text = stringResource(R.string.serverlist_add_gateway_manual_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GatewayProtocolSelector(selected = scheme, onSelect = onSelectScheme)
            OutlinedTextField(
                value = host,
                onValueChange = onUpdateHost,
                label = { Text(stringResource(R.string.serverlist_add_gateway_host_label)) },
                placeholder = { Text(stringResource(R.string.serverlist_add_gateway_host_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
            )
            GatewayCheckButton(isCheckingStatus = isCheckingStatus, onCheckStatus = onCheckStatus)
            status?.let { GatewayStatusCard(status = it) }
        }
        FormSection(title = stringResource(R.string.serverlist_add_gateway_section_names)) {
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
        }
    }
}

@Composable
private fun FormSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun GatewayCheckButton(
    isCheckingStatus: Boolean,
    onCheckStatus: () -> Unit,
) {
    OutlinedButton(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GatewayProtocolSelector(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val secure = selected.equals(HTTPS_SCHEME, ignoreCase = true) || selected.equals("wss", ignoreCase = true)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !secure,
            onClick = { onSelect(HTTP_SCHEME) },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
            modifier = Modifier.handCursor(),
        ) {
            Text(stringResource(R.string.serverlist_add_gateway_http))
        }
        SegmentedButton(
            selected = secure,
            onClick = { onSelect(HTTPS_SCHEME) },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
            icon = {
                SegmentedButtonDefaults.Icon(
                    active = secure,
                    activeContent = { Icon(Icons.Default.Lock, null) },
                )
            },
            modifier = Modifier.handCursor(),
        ) {
            Text(stringResource(R.string.serverlist_add_gateway_https))
        }
    }
}
