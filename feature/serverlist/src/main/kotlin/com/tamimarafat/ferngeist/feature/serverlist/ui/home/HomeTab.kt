package com.tamimarafat.ferngeist.feature.serverlist.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.feature.serverlist.R
import kotlinx.coroutines.launch

/**
 * Level 1: the session list's filter toolbar is `HorizontalFloatingToolbar` with a FAB, whose
 * expanded default is `ContainerExpandedElevationWithFab` = 1 dp (collapsed is 0).
 */
private val HomeControlElevation = 1.dp

/** One value for both gaps in the bar, so the tab pair is spaced like the tab group to the FAB. */
private val HomeBarGap = 8.dp

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
    hasGateways: Boolean,
    onQuickChat: () -> Unit,
    onGateways: () -> Unit,
    onAddAgent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val tab = HomeTab.entries[pager.state.currentPage]
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(HomeBarGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(HomeBarGap),
        ) {
            HomeTab.entries.forEach { entry ->
                HomeTabItem(
                    tab = entry,
                    selected = entry == tab,
                    onClick = {
                        scope.launch { pager.select(entry) }
                    },
                )
            }
        }
        HomeFab(
            quickChat = tab == HomeTab.PROJECTS && quickChatAvailable,
            hasGateways = hasGateways,
            onQuickChat = onQuickChat,
            onGateways = onGateways,
            onAddAgent = onAddAgent,
        )
    }
}

/** New chat on Projects; on Agents, or with nothing to chat in, a menu of ways to add an agent. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeFab(
    quickChat: Boolean,
    hasGateways: Boolean,
    onQuickChat: () -> Unit,
    onGateways: () -> Unit,
    onAddAgent: () -> Unit,
) {
    var showMenu by rememberSaveable { mutableStateOf(false) }
    Box {
        FloatingActionButton(
            onClick = { if (quickChat) onQuickChat() else showMenu = true },
            // The vibrant toolbar's FAB role, so the action separates from the tab pills.
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ) {
            // The icon swaps with the page; a crossfade keeps the FAB itself from seeming to change.
            Crossfade(targetState = quickChat, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()) { chat ->
                Icon(
                    if (chat) Icons.AutoMirrored.Filled.Chat else Icons.Default.Add,
                    contentDescription =
                        stringResource(if (chat) R.string.serverlist_quick_chat else R.string.serverlist_fab_add),
                )
            }
        }
        AddAgentMenu(
            expanded = showMenu,
            onDismiss = { showMenu = false },
            hasGateways = hasGateways,
            onGateways = onGateways,
            onAddAgent = onAddAgent,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddAgentMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    hasGateways: Boolean,
    onGateways: () -> Unit,
    onAddAgent: () -> Unit,
) {
    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuGroup(
            shapes = MenuDefaults.groupShape(0, 1),
            interactionSource = remember { MutableInteractionSource() },
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (hasGateways) {
                                R.string.serverlist_add_paired_btn
                            } else {
                                R.string.serverlist_add_gateway_btn
                            },
                        ),
                    )
                },
                leadingIcon = { Icon(Icons.Default.Devices, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onGateways()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.serverlist_add_agent_btn)) },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onAddAgent()
                },
            )
        }
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
                defaultElevation = HomeControlElevation,
                pressedElevation = HomeControlElevation,
                focusedElevation = HomeControlElevation,
                hoveredElevation = HomeControlElevation,
                disabledElevation = HomeControlElevation,
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
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
