package com.tamimarafat.ferngeist.feature.serverlist.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.dismissSheet
import com.tamimarafat.ferngeist.feature.serverlist.R
import kotlinx.coroutines.launch

/**
 * Level 1: the session list's filter toolbar is `HorizontalFloatingToolbar` with a FAB, whose
 * expanded default is `ContainerExpandedElevationWithFab` = 1 dp (collapsed is 0).
 */
private val homeControlElevation = 1.dp

/** One value for both gaps in the bar, so the tab pair is spaced like the tab group to the FAB. */
private val homeBarGap = 8.dp

enum class HomeTab(
    val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    PROJECTS(R.string.serverlist_section_projects, Icons.Outlined.Folder, Icons.Filled.Folder),
    AGENTS(R.string.serverlist_section_agents, agentOutlinedIcon, agentFilledIcon),
}

/**
 * Connected tabs that switch the home sheet's pages, with the page's main action as its FAB.
 * The selected tab shows its label; the other remains compact.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeTabBar(
    pager: HomePagerMotion,
    quickChatAvailable: Boolean,
    gatewayCount: Int,
    onQuickChat: () -> Unit,
    onGateways: () -> Unit,
    onAddAgent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val tab = pager.selectedTab
    val showTabs = pager.tabs.size > 1
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(homeBarGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A single page has nothing to switch to; only the add action remains.
        if (showTabs) {
            Row(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(homeBarGap),
            ) {
                pager.tabs.forEach { entry ->
                    HomeTabItem(
                        tab = entry,
                        selected = entry == tab,
                        onClick = {
                            scope.launch { pager.select(entry) }
                        },
                    )
                }
            }
        }
        HomeFab(
            quickChat = showTabs && tab == HomeTab.PROJECTS && quickChatAvailable,
            gatewayCount = gatewayCount,
            onQuickChat = onQuickChat,
            onGateways = onGateways,
            onAddAgent = onAddAgent,
        )
    }
}

/** New chat on Projects; on Agents, or with nothing to chat in, a sheet of ways to add an agent. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeFab(
    quickChat: Boolean,
    gatewayCount: Int,
    onQuickChat: () -> Unit,
    onGateways: () -> Unit,
    onAddAgent: () -> Unit,
) {
    var showSheet by rememberSaveable { mutableStateOf(false) }
    Box {
        FloatingActionButton(
            onClick = { if (quickChat) onQuickChat() else showSheet = true },
            // The vibrant toolbar's FAB role, so the action separates from the tab pills.
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ) {
            HomeFabIcon(quickChat)
        }
        if (showSheet) {
            AddAgentSheet(
                onDismiss = { showSheet = false },
                gatewayCount = gatewayCount,
                onGateways = onGateways,
                onAddAgent = onAddAgent,
            )
        }
    }
}

@Composable
private fun HomeFabIcon(quickChat: Boolean) {
    // The icon swaps with the page; a crossfade keeps the FAB itself from seeming to change.
    Crossfade(targetState = quickChat, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()) { chat ->
        Icon(
            if (chat) Icons.AutoMirrored.Filled.Chat else Icons.Default.Add,
            contentDescription =
                stringResource(if (chat) R.string.serverlist_quick_chat else R.string.serverlist_fab_add),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAgentSheet(
    onDismiss: () -> Unit,
    gatewayCount: Int,
    onGateways: () -> Unit,
    onAddAgent: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier =
                Modifier
                    .padding(horizontal = 16.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            Text(
                text = stringResource(R.string.serverlist_add_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 12.dp),
            )
            AddAgentRow(
                index = 0,
                icon = Icons.Default.Devices,
                label =
                    stringResource(
                        if (gatewayCount > 0) {
                            R.string.serverlist_add_paired_btn
                        } else {
                            R.string.serverlist_add_gateway_btn
                        },
                    ),
                subtitle =
                    gatewayCount.takeIf { it > 0 }?.let {
                        pluralStringResource(R.plurals.serverlist_gateways_paired, it, it)
                    },
                onClick = { scope.dismissSheet(sheetState) { onDismiss().also { onGateways() } } },
            )
            AddAgentRow(
                index = 1,
                icon = Icons.Default.Add,
                label = stringResource(R.string.serverlist_add_agent_btn),
                subtitle = null,
                onClick = { scope.dismissSheet(sheetState) { onDismiss().also { onAddAgent() } } },
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddAgentRow(
    index: Int,
    icon: ImageVector,
    label: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, 2),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        leadingContent = {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(10.dp),
                )
            }
        },
        supportingContent = subtitle?.let { { Text(it) } },
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeTabItem(
    tab: HomeTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(tab.label)
    val motion = MaterialTheme.motionScheme
    ToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        elevation =
            ToggleButtonDefaults.elevation(
                defaultElevation = homeControlElevation,
                pressedElevation = homeControlElevation,
                focusedElevation = homeControlElevation,
                hoveredElevation = homeControlElevation,
                disabledElevation = homeControlElevation,
            ),
        shapes =
            if (tab == HomeTab.PROJECTS) {
                ButtonGroupDefaults.connectedLeadingButtonShapes().copy(shape = CircleShape)
            } else {
                ButtonGroupDefaults.connectedTrailingButtonShapes().copy(shape = CircleShape)
            },
        modifier =
            Modifier
                .height(FloatingToolbarDefaults.ContainerSize)
                .semantics { role = Role.Tab },
    ) {
        Icon(
            if (selected) tab.selectedIcon else tab.icon,
            contentDescription = if (selected) null else label,
            modifier = if (tab == HomeTab.AGENTS) Modifier.size(26.dp) else Modifier,
        )
        // Keep the icon-only tab accessible when its label is hidden.
        AnimatedVisibility(
            visible = selected,
            enter =
                expandHorizontally(motion.fastSpatialSpec(), expandFrom = Alignment.Start) +
                    fadeIn(motion.fastEffectsSpec()),
            exit =
                shrinkHorizontally(motion.fastSpatialSpec(), shrinkTowards = Alignment.Start) +
                    fadeOut(motion.fastEffectsSpec()),
        ) {
            Text(
                label,
                modifier = Modifier.padding(start = 8.dp, end = 6.dp),
                style = MaterialTheme.typography.labelLargeEmphasized,
            )
        }
    }
}
