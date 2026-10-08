package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.formContentMaxWidth
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.feature.serverlist.AddCustomAgentEvent
import com.tamimarafat.ferngeist.feature.serverlist.AddCustomAgentViewModel
import com.tamimarafat.ferngeist.feature.serverlist.R

/**
 * Registers a client-owned ACP agent on the gateway this screen was opened for.
 * The created agent is *not* added to the launch list: that stays behind the
 * existing risk-consent dialog on the catalog screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AddCustomAgentScreen(
    onNavigateBack: () -> Unit,
    onCreated: () -> Unit,
    viewModel: AddCustomAgentViewModel,
) {
    val displayName by viewModel.displayName.collectAsState()
    val command by viewModel.command.collectAsState()
    val arguments by viewModel.arguments.collectAsState()
    val hint by viewModel.hint.collectAsState()
    val isSubmitting by viewModel.isSubmitting.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                // A not-detected result is not an error the user must acknowledge:
                // the catalog card carries the warning (Task 5).
                AddCustomAgentEvent.Created -> onCreated()
                is AddCustomAgentEvent.CreatedNotDetected -> onCreated()
                is AddCustomAgentEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.serverlist_custom_agent_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onNavigateBack, modifier = Modifier.handCursor()) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.serverlist_back_desc),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        AddCustomAgentContent(
            padding = padding,
            displayName = displayName,
            onUpdateDisplayName = viewModel::updateDisplayName,
            command = command,
            onUpdateCommand = viewModel::updateCommand,
            arguments = arguments,
            onUpdateArguments = viewModel::updateArguments,
            hint = hint,
            onUpdateHint = viewModel::updateHint,
            isSubmitting = isSubmitting,
            onSubmit = viewModel::submit,
        )
    }
}

@Composable
private fun AddCustomAgentContent(
    padding: PaddingValues,
    displayName: String,
    onUpdateDisplayName: (String) -> Unit,
    command: String,
    onUpdateCommand: (String) -> Unit,
    arguments: String,
    onUpdateArguments: (String) -> Unit,
    hint: String,
    onUpdateHint: (String) -> Unit,
    isSubmitting: Boolean,
    onSubmit: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .formContentMaxWidth()
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            SectionCard(
                title = stringResource(R.string.serverlist_custom_agent_identity_title),
                subtitle = stringResource(R.string.serverlist_custom_agent_identity_subtitle),
                icon = Icons.Default.Badge,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = onUpdateDisplayName,
                        label = { Text(stringResource(R.string.serverlist_custom_agent_name_label)) },
                        placeholder = { Text(stringResource(R.string.serverlist_custom_agent_name_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = sectionTextFieldColors(),
                    )
                    OutlinedTextField(
                        value = hint,
                        onValueChange = onUpdateHint,
                        label = { Text(stringResource(R.string.serverlist_custom_agent_hint_label)) },
                        placeholder = { Text(stringResource(R.string.serverlist_custom_agent_hint_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = sectionTextFieldColors(),
                    )
                }
            }

            SectionCard(
                title = stringResource(R.string.serverlist_custom_agent_command_title),
                subtitle = stringResource(R.string.serverlist_custom_agent_command_subtitle),
                icon = Icons.Default.Terminal,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = onUpdateCommand,
                        label = { Text(stringResource(R.string.serverlist_custom_agent_command_label)) },
                        placeholder = { Text(stringResource(R.string.serverlist_custom_agent_command_placeholder)) },
                        supportingText = { Text(stringResource(R.string.serverlist_custom_agent_command_support)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = sectionTextFieldColors(),
                    )
                    OutlinedTextField(
                        value = arguments,
                        onValueChange = onUpdateArguments,
                        label = { Text(stringResource(R.string.serverlist_custom_agent_arguments_label)) },
                        placeholder = { Text(stringResource(R.string.serverlist_custom_agent_arguments_placeholder)) },
                        supportingText = { Text(stringResource(R.string.serverlist_custom_agent_arguments_support)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = sectionTextFieldColors(),
                    )
                    WarningSurface(stringResource(R.string.serverlist_custom_agent_warning))
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            AddCustomAgentButton(isSubmitting = isSubmitting, onSubmit = onSubmit)

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun AddCustomAgentButton(
    isSubmitting: Boolean,
    onSubmit: () -> Unit,
) {
    Button(
        onClick = onSubmit,
        modifier = Modifier.widthIn(min = 240.dp).height(56.dp).handCursor(enabled = !isSubmitting),
        enabled = !isSubmitting,
        shape = RoundedCornerShape(16.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
    ) {
        if (isSubmitting) {
            LoadingIndicator(modifier = Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary)
            Spacer(modifier = Modifier.width(10.dp))
        }
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.serverlist_custom_agent_submit),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
