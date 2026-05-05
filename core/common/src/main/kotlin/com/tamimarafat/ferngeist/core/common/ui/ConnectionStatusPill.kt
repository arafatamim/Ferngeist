package com.tamimarafat.ferngeist.core.common.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState

/**
 * Floating pill showing the ACP connection state as a colored dot (or loading spinner).
 *
 * The pill is a circular [FilledTonalButton] with [secondaryContainer] color.
 * [connectionState] determines the inner indicator:
 * - [AcpConnectionState.Connecting] → [CircularProgressIndicator] (12dp)
 * - [AcpConnectionState.Connected] → [MaterialTheme.colorScheme.primary] dot
 * - [AcpConnectionState.Failed] → [MaterialTheme.colorScheme.error] dot
 * - [AcpConnectionState.Disconnected] → [MaterialTheme.colorScheme.outlineVariant] dot
 *
 * Long-press shows a tooltip with "Connection: {state}" text.
 * Accessible as a button with [stateDescription] set to the connection label.
 *
 * @param connectionState The current ACP connection state to display.
 * @param onClick Called when the pill is tapped (e.g. open diagnostics dialog).
 * @param modifier Optional [Modifier] applied to the outer [FilledTonalButton].
 */
@ExperimentalMaterial3Api
@Composable
fun ConnectionStatusPill(
    connectionState: AcpConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connectionLabel = connectionStateLabel(connectionState)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text("Connection Status: $connectionLabel") } },
        state = rememberTooltipState(),
    ) {
        FilledTonalButton(
            onClick = onClick,
            shape = CircleShape,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
            elevation = ButtonDefaults.filledTonalButtonElevation(defaultElevation = 0.dp),
            contentPadding = PaddingValues(12.dp),
            modifier =
                modifier
                    .size(40.dp)
                    .semantics {
                        contentDescription = "Connection status"
                        stateDescription = connectionLabel
                    },
        ) {
            when (connectionState) {
                is AcpConnectionState.Connecting ->
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                    )

                is AcpConnectionState.Connected ->
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(10.dp),
                    ) {}

                is AcpConnectionState.Failed ->
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(10.dp),
                    ) {}

                is AcpConnectionState.Disconnected ->
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(10.dp),
                    ) {}
            }
        }
    }
}
