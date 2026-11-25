package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.copy.CopyGlyph
import com.tamimarafat.ferngeist.core.common.ui.copy.rememberCopyState
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.gateway.GatewayPairingPayload
import kotlinx.coroutines.launch

private const val COMMAND_INSTRUCTION_INDEX = 2
private val URL_PATTERN = Regex("https?://\\S+")
private const val PAIR_COMMAND = "ferngeist-gateway pair"
private val StepBadgeSize = 28.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RunFerngeistPairStep() {
    val instructions =
        listOf(
            stringResource(R.string.serverlist_add_gateway_instruction_1),
            stringResource(R.string.serverlist_add_gateway_instruction_2),
            stringResource(R.string.serverlist_add_gateway_instruction_3),
            stringResource(R.string.serverlist_add_gateway_instruction_4),
        )
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        instructions.forEachIndexed { index, text ->
            NumberedInstruction(number = index + 1, text = text) {
                if (index == COMMAND_INSTRUCTION_INDEX) CommandBlock(PAIR_COMMAND)
            }
        }
    }
    Text(
        text = stringResource(R.string.serverlist_add_gateway_scan_instruction),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NumberedInstruction(
    number: Int,
    text: String,
    extra: @Composable () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(StepBadgeSize),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(text = number.toString(), style = MaterialTheme.typography.labelLarge)
            }
        }
        Column(
            modifier = Modifier.weight(1f).padding(top = 3.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = linkified(text), style = MaterialTheme.typography.bodyLarge)
            extra()
        }
    }
}

/** Turns any URL in [text] into a tappable link. */
@Composable
private fun linkified(text: String): AnnotatedString {
    val linkStyle =
        TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var cursor = 0
        URL_PATTERN.findAll(text).forEach { match ->
            // A trailing period belongs to the sentence, not the link.
            val url = match.value.trimEnd('.')
            append(text.substring(cursor, match.range.first))
            withLink(LinkAnnotation.Url(url, linkStyle)) { append(url) }
            cursor = match.range.first + url.length
        }
        append(text.substring(cursor))
    }
}

/** The command a user must run on their computer, with one-tap copy so it never gets mistyped. */
@Composable
private fun CommandBlock(command: String) {
    val copyState = rememberCopyState()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = command,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            )
            val description =
                stringResource(
                    if (copyState.copied) {
                        R.string.serverlist_add_gateway_command_copied
                    } else {
                        R.string.serverlist_add_gateway_copy_command
                    },
                )
            IconButton(
                onClick = { copyState.copy(command) },
                modifier = Modifier.handCursor().semantics { contentDescription = description },
            ) {
                CopyGlyph(
                    copied = copyState.copied,
                    size = 20.dp,
                    doneTint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
internal fun ImportPairingStep(
    pairingQrPayload: String,
    importedPayload: GatewayPairingPayload?,
    onUpdatePayload: (String) -> Unit,
    onScanQr: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (booleanResource(R.bool.serverlist_qr_scan_available)) {
            Button(
                onClick = onScanQr,
                modifier = Modifier.fillMaxWidth().height(56.dp).handCursor(),
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.serverlist_add_gateway_step_scan_title))
            }
            OrDivider()
        }
        OutlinedTextField(
            value = pairingQrPayload,
            onValueChange = onUpdatePayload,
            label = { Text(stringResource(R.string.serverlist_add_gateway_payload_label)) },
            placeholder = { Text(stringResource(R.string.serverlist_add_gateway_payload_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(
                    onClick = {
                        scope.launch {
                            clipboard
                                .getClipEntry()
                                ?.clipData
                                ?.getItemAt(0)
                                ?.text
                                ?.let { onUpdatePayload(it.toString()) }
                        }
                    },
                    modifier = Modifier.handCursor(),
                ) {
                    Icon(
                        Icons.Default.ContentPaste,
                        contentDescription = stringResource(R.string.serverlist_add_gateway_paste),
                    )
                }
            },
        )
        importedPayload?.let { ImportedPayloadCard(payload = it) }
    }
}

@Composable
private fun OrDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.serverlist_add_gateway_or),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}
