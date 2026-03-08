package com.tamimarafat.ferngeist.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun FirstLaunchOnboardingScreen(
    uiState: OnboardingUiState,
    onSkip: () -> Unit,
    onStartSetup: () -> Unit,
    onLoadRegistry: () -> Unit,
    onSelectAgent: (String) -> Unit,
    onSelectPlatform: (PcPlatform) -> Unit,
    onUpdatePort: (String) -> Unit,
    onRetryLoad: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val steps = remember { onboardingSteps() }
    val step = steps[stepIndex]
    val launchInstructions = uiState.launchInstructions
    val canAdvance = step.kind != OnboardingStepKind.ChooseAgent || uiState.selectedAgent != null

    LaunchedEffect(Unit) {
        onLoadRegistry()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "First Agent Setup",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onSkip) {
                    Text("Skip")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Step ${stepIndex + 1} of ${steps.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(12.dp))

            StepProgress(currentStep = stepIndex, totalSteps = steps.size)

            Spacer(modifier = Modifier.height(20.dp))

            ElevatedCard(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = step.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }

                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )

                    Text(
                        text = step.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    when (step.kind) {
                        OnboardingStepKind.Welcome -> OnboardingBulletList(
                            items = listOf(
                                "Ferngeist connects to ACP agents over WebSocket from your phone.",
                                "If an agent only speaks ACP over stdio, Ferngeist needs a stdio-to-ws bridge on your computer.",
                                "This setup fetches agent launch instructions from the ACP registry and builds the command for you.",
                            )
                        )

                        OnboardingStepKind.ChooseAgent -> AgentSelectionCard(
                            uiState = uiState,
                            onSelectAgent = onSelectAgent,
                            onRetryLoad = onRetryLoad,
                        )

                        OnboardingStepKind.StartOnComputer -> StartOnComputerCard(
                            uiState = uiState,
                            onSelectPlatform = onSelectPlatform,
                            onUpdatePort = onUpdatePort,
                            onCopy = {
                                launchInstructions?.command?.let { command ->
                                    clipboardManager.setText(AnnotatedString(command))
                                }
                            },
                        )

                        OnboardingStepKind.FindAddress -> OnboardingBulletList(
                            items = listOf(
                                "Keep the terminal window open after starting the bridge.",
                                "Use your PC's local IP address, such as 192.168.1.42:${uiState.parsedPort}.",
                                "Do not use localhost or 127.0.0.1 from your phone.",
                                "Your phone and PC must be on the same network.",
                            )
                        )

                        OnboardingStepKind.AddAgent -> OnboardingBulletList(
                            items = listOf(
                                "Name: ${uiState.selectedAgent?.name ?: "Your Agent"}",
                                "Protocol: ws",
                                "Host: your-pc-ip:${uiState.parsedPort}",
                                "Working directory: your project folder on the agent side",
                            )
                        )

                        OnboardingStepKind.ConnectAndChat -> OnboardingBulletList(
                            items = listOf(
                                "After saving, connect to the server from the server list.",
                                "If the agent does not support session listing, Ferngeist will open the new session dialog for you.",
                                "Your first prompt can be something like: Summarize this project.",
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (stepIndex > 0) {
                    OutlinedButton(
                        onClick = { stepIndex -= 1 },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Back")
                    }
                }

                Button(
                    onClick = {
                        if (stepIndex == steps.lastIndex) {
                            onStartSetup()
                        } else {
                            if (step.kind == OnboardingStepKind.StartOnComputer) {
                                launchInstructions?.command?.let { command ->
                                    clipboardManager.setText(AnnotatedString(command))
                                }
                            }
                            stepIndex += 1
                        }
                    },
                    enabled = canAdvance,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (stepIndex == steps.lastIndex) "Open Setup Form" else "Next")
                    Spacer(modifier = Modifier.size(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                    )
                }
            }
        }
    }

    LaunchedEffect(stepIndex, launchInstructions?.command) {
        if (steps[stepIndex].kind == OnboardingStepKind.StartOnComputer && launchInstructions?.command != null) {
            snackbarHostState.showSnackbar("Copy the command, run it on your PC, then keep that terminal open.")
        }
    }
}

@Composable
private fun StepProgress(
    currentStep: Int,
    totalSteps: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(totalSteps) { index ->
            val color = if (index <= currentStep) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .background(color = color, shape = RoundedCornerShape(999.dp))
            )
        }
    }
}

@Composable
private fun OnboardingBulletList(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { item ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "-",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun AgentSelectionCard(
    uiState: OnboardingUiState,
    onSelectAgent: (String) -> Unit,
    onRetryLoad: () -> Unit,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                uiState.isLoadingAgents -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            text = "Loading the ACP registry...",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                uiState.loadError != null -> {
                    Text(
                        text = uiState.loadError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = onRetryLoad) {
                        Text("Retry")
                    }
                }

                else -> {
                    var expanded by remember { mutableStateOf(false) }
                    val selectedAgent = uiState.selectedAgent

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(selectedAgent?.name ?: "Choose an agent")
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                        ) {
                            uiState.agents.forEach { agent ->
                                DropdownMenuItem(
                                    text = { Text(agent.name) },
                                    onClick = {
                                        expanded = false
                                        onSelectAgent(agent.id)
                                    },
                                )
                            }
                        }
                    }

                    if (selectedAgent != null) {
                        Text(
                            text = selectedAgent.description.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Registry source: ${selectedAgent.repository ?: "ACP registry"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StartOnComputerCard(
    uiState: OnboardingUiState,
    onSelectPlatform: (PcPlatform) -> Unit,
    onUpdatePort: (String) -> Unit,
    onCopy: () -> Unit,
) {
    val launchInstructions = uiState.launchInstructions
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Generate a PC command",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PcPlatform.entries.forEach { platform ->
                    FilterChip(
                        selected = uiState.platform == platform,
                        onClick = { onSelectPlatform(platform) },
                        label = { Text(platform.label) },
                    )
                }
            }

            OutlinedTextField(
                value = uiState.port,
                onValueChange = onUpdatePort,
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "Ferngeist connects over WebSocket. The generated command starts the agent and exposes it through stdio-to-ws when needed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SelectionContainer {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        text = launchInstructions?.command ?: "Choose an agent to generate a command.",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            launchInstructions?.notes?.forEach { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!launchInstructions?.environment.isNullOrEmpty()) {
                Text(
                    text = "Set these environment variables before running the command:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                launchInstructions?.environment?.entries?.forEach { (key, value) ->
                    SelectionContainer {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Text(
                                text = "$key=$value",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Ferngeist will connect to your-pc-ip:${uiState.parsedPort}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    enabled = launchInstructions?.command != null,
                    onClick = onCopy,
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy command")
                }
            }
        }
    }
}

private fun onboardingSteps(): List<OnboardingStep> {
    return listOf(
        OnboardingStep(
            title = "Start your first agent",
            body = "Ferngeist talks to ACP agents over WebSocket. This guide will fetch agents from the ACP registry, generate the PC command, then hand you off to the real setup form.",
            icon = Icons.Default.RocketLaunch,
            kind = OnboardingStepKind.Welcome,
        ),
        OnboardingStep(
            title = "Choose an agent",
            body = "Pick the agent you want to run on your computer. Ferngeist reads the latest ACP registry so the launch command stays current.",
            icon = Icons.Default.Extension,
            kind = OnboardingStepKind.ChooseAgent,
        ),
        OnboardingStep(
            title = "Run it on your PC",
            body = "Run the generated command in a terminal on your computer. If the agent is not ACP-over-WebSocket natively, Ferngeist wraps it with stdio-to-ws.",
            icon = Icons.Default.Computer,
            kind = OnboardingStepKind.StartOnComputer,
        ),
        OnboardingStep(
            title = "Use your PC's local address",
            body = "Ferngeist needs the network address of your computer, not localhost. If your firewall asks about access, allow it for your local network.",
            icon = Icons.Default.Wifi,
            kind = OnboardingStepKind.FindAddress,
        ),
        OnboardingStep(
            title = "Add the agent in Ferngeist",
            body = "Add your agent after this tutorial is finished. Save the same WebSocket port there so Ferngeist can connect back to the bridge on your computer.",
            icon = Icons.Default.Dns,
            kind = OnboardingStepKind.AddAgent,
        ),
        OnboardingStep(
            title = "Connect and start a session",
            body = "After the server is saved, connect to it and create your first session. Ferngeist will branch based on the agent capabilities it discovers.",
            icon = Icons.Default.RocketLaunch,
            kind = OnboardingStepKind.ConnectAndChat,
        ),
    )
}

private data class OnboardingStep(
    val title: String,
    val body: String,
    val icon: ImageVector,
    val kind: OnboardingStepKind,
)

private enum class OnboardingStepKind {
    Welcome,
    ChooseAgent,
    StartOnComputer,
    FindAddress,
    AddAgent,
    ConnectAndChat,
}
