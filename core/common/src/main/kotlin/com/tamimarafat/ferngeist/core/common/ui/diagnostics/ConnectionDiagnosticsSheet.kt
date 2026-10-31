package com.tamimarafat.ferngeist.core.common.ui.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.R
import com.tamimarafat.ferngeist.core.common.ui.connectionStateLabel
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.model.AgentQuota
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.UsageState
import com.tamimarafat.ferngeist.core.model.repository.CodexBarNotFoundException
import kotlinx.coroutines.CancellationException
import java.net.URI

private data class QuotaDisplayState(
    val quota: List<AgentQuota>?,
    val codexBarMissing: Boolean = false,
)

/**
 * Bottom sheet showing connection health, this chat's usage, plan quota and, collapsed,
 * recent problems and transport details. [usage] is null where no chat is open; [quota] is null while
 * the gateway relays none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionDiagnosticsSheet(
    connectionState: ChatConnectionState,
    diagnostics: ChatConnectionDiagnostics,
    onDismiss: () -> Unit,
    usage: UsageState? = null,
    quota: List<AgentQuota>? = null,
    fetchQuota: (suspend () -> List<AgentQuota>?)? = null,
) {
    val loadedQuota by produceState(initialValue = QuotaDisplayState(quota), fetchQuota) {
        if (fetchQuota != null) {
            value =
                try {
                    QuotaDisplayState(fetchQuota())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: CodexBarNotFoundException) {
                    QuotaDisplayState(null, codexBarMissing = true)
                } catch (_: Exception) {
                    QuotaDisplayState(null)
                }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState =
            rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
    ) {
        DiagnosticsSheetContent(connectionState, diagnostics, usage, loadedQuota.quota, loadedQuota.codexBarMissing)
    }
}

/** The sheet body, split out so previews can render it without a [ModalBottomSheet] window. */
@Composable
internal fun DiagnosticsSheetContent(
    connectionState: ChatConnectionState,
    diagnostics: ChatConnectionDiagnostics,
    usage: UsageState?,
    quota: List<AgentQuota>?,
    codexBarMissing: Boolean = false,
) {
    Column(
        modifier =
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DiagnosticsHeader(connectionState, diagnostics.serverUrl)
        usage?.let { UsageSection(it) }
        if (codexBarMissing) {
            Text(
                stringResource(R.string.common_quota_install_codexbar),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            QuotaSection(quota)
        }
        HorizontalDivider()
        Column {
            ProblemsSection(diagnostics.recentErrors)
            DetailsSection(diagnostics)
        }
    }
}

@Composable
private fun DiagnosticsHeader(
    connectionState: ChatConnectionState,
    serverUrl: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                shape = CircleShape,
                color = connectionStateColor(connectionState),
                modifier = Modifier.size(12.dp),
            ) {}
            Text(connectionStateLabel(connectionState), style = MaterialTheme.typography.headlineSmall)
        }
        // Host only: the full URL (path, token query) is noise here and lives under Details.
        serverUrl?.let { url ->
            Text(
                text = runCatching { URI(url).host }.getOrNull() ?: url,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        (connectionState as? ChatConnectionState.Failed)?.errorMessage?.let { message ->
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun connectionStateColor(state: ChatConnectionState): Color =
    when (state) {
        is ChatConnectionState.Connecting -> MaterialTheme.colorScheme.secondary
        is ChatConnectionState.Connected -> MaterialTheme.colorScheme.primary
        is ChatConnectionState.Failed -> MaterialTheme.colorScheme.error
        is ChatConnectionState.Disconnected -> MaterialTheme.colorScheme.outline
    }

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** A collapsed-by-default row that reveals [content]; [trailing] sits before the chevron. */
@Composable
internal fun ExpandableSection(
    title: String,
    trailing: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f)
    val stateLabel = stringResource(if (expanded) R.string.common_expanded else R.string.common_collapsed)
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .handCursor()
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = stateLabel }
                    .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            trailing()
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.rotate(chevronRotation))
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        }
    }
}
