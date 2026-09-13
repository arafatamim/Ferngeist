package com.tamimarafat.ferngeist.workspace

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldRole
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tamimarafat.ferngeist.R
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.feature.chat.ChatViewModel
import com.tamimarafat.ferngeist.feature.chat.ui.ChatScreen
import com.tamimarafat.ferngeist.feature.serverlist.RecentSession

/**
 * The wide-window workspace: agents, sessions and chat side by side, chat pinned on the right.
 *
 * Pane count is computed, not chosen — [calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth]
 * yields 2 partitions from the medium width class and 3 from expanded, and
 * [WorkspaceState.paneValue] clamps the visible panes to that count (the scaffold itself would
 * otherwise squeeze three panes into two partitions).
 *
 * Slots rather than constructed panes: the host already owns the agents and sessions wiring,
 * including their ViewModels and navigation, so the workspace must not re-create it.
 *
 * @param agentsPane the agent list (left, `Secondary`).
 * @param sessionsPane the session list for the given agent (middle, `Primary`).
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun WorkspaceScreen(
    workspace: WorkspaceState,
    recentSessions: List<RecentSession>,
    chatConnectionHub: ChatConnectionHub,
    chatViewModelFactory: ChatViewModelFactory,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    agentsPane: @Composable () -> Unit,
    sessionsPane: @Composable (serverId: String, showBackButton: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val directive =
        remember(adaptiveInfo) {
            // Zero the inter-pane gutters: `horizontalPartitionSpacerSize` is both the gap
            // between partitions and their outer margin, so this makes the panes meet and
            // reach the window edges. The workspace is a dense three-pane layout — 48dp of
            // M3's default spacing is width the chat can use.
            calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(adaptiveInfo)
                .copy(horizontalPartitionSpacerSize = 0.dp)
        }
    val maxPartitions = directive.maxHorizontalPartitions
    val value = workspace.paneValue(maxPartitions)
    val panes =
        desirablePanes(
            maxPartitions,
            workspace.selectedServerId,
            workspace.selectedSessionId,
            workspace.agentsInLeft,
        )
    val widths = workspacePaneWidths(panes)

    // D9: seed the workspace once, wide windows only (this composable is only reached wide).
    // A warm hub entry means the transport is already attached, so mounting the chat reuses it;
    // a cold one would connect, load and can mint a gateway session, so it is skipped.
    // Keyed on the list because `recentSessions` starts empty and fills in asynchronously — a
    // `Unit`-keyed effect would read that first empty value and never retry.
    var seeded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(recentSessions) {
        if (seeded || workspace.selectedServerId != null) return@LaunchedEffect
        val recent = recentSessions.firstOrNull() ?: return@LaunchedEffect
        seeded = true
        // The hub keys a chat as `"<serverId>/<sessionId>"`. `chatIdFor` owns that format but
        // is internal to acp-bridge, so the string is built here.
        val chatId = "${recent.serverId}/${recent.sessionId}"
        if (chatConnectionHub.managerFor(chatId) != null) {
            workspace.resumeSession(
                WorkspaceSelection(
                    serverId = recent.serverId,
                    sessionId = recent.sessionId,
                    cwd = recent.cwd ?: "/",
                    title = recent.title,
                ),
            )
        } else {
            workspace.selectAgent(recent.serverId)
        }
    }

    // D1: at two panes, back steps the left pane up to the agent list while the chat stays
    // pinned. Three panes show everything, so there is nothing to undo; one pane is the
    // compact branch's job. Same predicate gates the panes' own back buttons.
    val backEnabled =
        isWorkspaceBackEnabled(
            maxPartitions,
            workspace.agentsInLeft,
            workspace.selectedSessionId,
        )
    BackHandler(enabled = backEnabled) { workspace.backToAgents() }

    ListDetailPaneScaffold(
        directive = directive,
        value = value,
        modifier = modifier,
        listPane = {
            AnimatedPane(
                modifier = Modifier.preferredWidth(widths.getValue(ThreePaneScaffoldRole.Secondary)),
            ) { agentsPane() }
        },
        detailPane = {
            AnimatedPane(
                modifier = Modifier.preferredWidth(widths.getValue(ThreePaneScaffoldRole.Primary)),
            ) {
                val serverId = workspace.selectedServerId
                if (serverId == null) {
                    EmptyPanePlaceholder()
                } else {
                    // Same condition as the chat pane: the button only exists when the agent
                    // list is not already on screen to go back to.
                    sessionsPane(serverId, backEnabled)
                }
            }
        },
        extraPane = {
            AnimatedPane(
                modifier = Modifier.preferredWidth(widths.getValue(ThreePaneScaffoldRole.Tertiary)),
            ) {
                val serverId = workspace.selectedServerId
                val sessionId = workspace.selectedSessionId
                if (serverId == null || sessionId == null) {
                    EmptyPanePlaceholder()
                } else {
                    ChatPane(
                        selection =
                            WorkspaceSelection(
                                serverId = serverId,
                                sessionId = sessionId,
                                cwd = workspace.cwd,
                                title = workspace.title,
                            ),
                        mintedSessionId = workspace.mintedSessionId,
                        chatViewModelFactory = chatViewModelFactory,
                        // The workspace never gives the chat a back button: at two panes the
                        // session list owns the one back affordance (it is the pane that
                        // changes), and at three there is nothing to go back to. Two buttons
                        // calling the same action on one screen is one button too many.
                        showBackButton = false,
                        onNavigateBack = workspace::backToAgents,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedContentScope = animatedContentScope,
                    )
                }
            }
        },
    )
}

/**
 * Hosts one session's chat in the right pane.
 *
 * The view model is built from this session's ids instead of `hiltViewModel()`: panes are not
 * nav destinations, so a scoped `hiltViewModel()` would read the enclosing entry's arguments.
 * It is scoped to a store that lives exactly as long as this selection, so moving to another
 * session clears it — `ChatViewModel.onCleared` closes that chat's hub presence — instead of
 * the never-popped workspace entry retaining one view model per visited session.
 */
@Composable
private fun ChatPane(
    selection: WorkspaceSelection,
    mintedSessionId: String?,
    chatViewModelFactory: ChatViewModelFactory,
    showBackButton: Boolean,
    onNavigateBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    val serverId = selection.serverId
    val sessionId = selection.sessionId
    // A fresh owner per selection: replacing the remembered key forgets the old owner, and the
    // effect below clears the store it held — which is what actually runs `onCleared`.
    val paneOwner = remember(serverId, sessionId) { SelectionViewModelStoreOwner() }
    DisposableEffect(paneOwner) { onDispose { paneOwner.viewModelStore.clear() } }

    val viewModel: ChatViewModel =
        viewModel(
            viewModelStoreOwner = paneOwner,
            factory =
                remember(selection, mintedSessionId) {
                    viewModelFactory {
                        initializer {
                            // The handle stands in for the nav arguments ChatViewModel would
                            // read: it decodes the title (`Uri.decode`), so the title must
                            // arrive encoded, exactly as the nav route carries it. `cwd` is
                            // read verbatim.
                            chatViewModelFactory.create(
                                selection = selection.copy(title = Uri.encode(selection.title)),
                                mintedSessionId = mintedSessionId,
                            )
                        }
                    }
                },
        )
    ChatScreen(
        sessionId = sessionId,
        sessionTitle = selection.title,
        onNavigateBack = onNavigateBack,
        // No modifier: ChatScreen's root already fills its constraints
        // (`.fillMaxSize().then(modifier)`), and the pane measures it with fixed bounds — a
        // second `fillMaxSize()` here would be a no-op and a fixed size would fight the pane.
        showBackButton = showBackButton,
        // Never share bounds from a pane. The compact flow's premise is that the session list
        // morphs into the chat, so exactly one of the two is on screen; the workspace can show
        // the session list beside this chat, and two nodes claiming one shared key left the
        // chat's top bar rendered with no title, subtitle or cwd.
        sharedBoundsEnabled = false,
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
        viewModel = viewModel,
    )
}

/**
 * A [ViewModelStoreOwner] whose lifetime is one workspace selection. The workspace route is the
 * wide graph's start destination and is never popped, so its own store would retain every
 * visited session's view model — with `onCleared` unrun — for the Activity's lifetime. The chat
 * pane swaps this owner when the selection changes and clears the store it leaves behind.
 */
private class SelectionViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

/** A pane with nothing to show: the workspace's own empty state, not a blank rectangle. */
@Composable
private fun EmptyPanePlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.workspace_no_selection),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
