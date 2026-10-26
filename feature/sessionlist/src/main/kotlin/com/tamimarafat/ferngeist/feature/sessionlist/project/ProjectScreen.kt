package com.tamimarafat.ferngeist.feature.sessionlist.project

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TwoRowsTopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectChat
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectSummary
import com.tamimarafat.ferngeist.core.common.ui.ProjectSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.ProjectTitleSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.model.isSameCwd
import com.tamimarafat.ferngeist.core.model.pathSegments
import com.tamimarafat.ferngeist.core.model.projectName
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListEvent
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.WorktreeScope
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.displayName
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.worktreeScopeOptions
import com.tamimarafat.ferngeist.feature.sessionlist.ui.NewWorktreeSheet
import com.tamimarafat.ferngeist.feature.sessionlist.ui.RemoveWorktreeDialog
import com.tamimarafat.ferngeist.feature.sessionlist.ui.SessionListEmptyContent
import com.tamimarafat.ferngeist.feature.sessionlist.ui.SessionListTopBarTitle
import com.tamimarafat.ferngeist.feature.sessionlist.ui.WorktreeToolbar
import com.tamimarafat.ferngeist.feature.sessionlist.ui.groupSessionsByDate
import com.tamimarafat.ferngeist.feature.sessionlist.ui.scopeLabel
import com.tamimarafat.ferngeist.gateway.GatewayWorktree

/** Opens a chat: the row's own agent, since one project mixes several. */
typealias OnOpenProjectChat = (
    serverId: String,
    sessionId: String,
    cwd: String,
    updatedAt: Long?,
    title: String?,
) -> Unit

/** Every chat of one project across its gateway's agents, narrowed by the toolbar's worktree. */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
fun ProjectScreen(
    viewModel: ProjectViewModel,
    showBackButton: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateToChat: OnOpenProjectChat,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    sharedBoundsEnabled: Boolean = true,
) {
    val project by viewModel.project.collectAsState()
    val scope by viewModel.scope.collectAsState()
    val worktrees by viewModel.worktrees.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingRemoval by remember { mutableStateOf<SessionListEvent.ConfirmRemoveWorktree?>(null) }
    var showWorktreeSheet by rememberSaveable { mutableStateOf(false) }
    var showAgentSheet by rememberSaveable { mutableStateOf(false) }
    val agents by viewModel.agents.collectAsState()
    // A usable single agent starts directly; unavailable agents keep their explanatory sheet.
    val selectedAgentId by viewModel.selectedAgentId.collectAsState()
    val newChatAgent by viewModel.newChatAgent.collectAsState()
    val onNewChat = {
        if (newChatAgent == null || selectedAgentId == null && agents.size > 1) {
            showAgentSheet = true
        } else {
            viewModel.newChat()
        }
    }
    var toolbarExpanded by rememberSaveable { mutableStateOf(true) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    ProjectEvents(
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
        // A worktree create leaves its sheet open until its chat is ready.
        onNavigateToChat = { serverId, sessionId, cwd, updatedAt, title ->
            showWorktreeSheet = false
            onNavigateToChat(serverId, sessionId, cwd, updatedAt, title)
        },
        onConfirmRemove = { pendingRemoval = it },
    )

    val repo = project?.repo
    val options = remember(project, worktrees) { project?.let { worktreeScopeOptions(it, worktrees) }.orEmpty() }

    Box(
        modifier =
            Modifier
                .projectSharedBounds(
                    project.takeIf { sharedBoundsEnabled },
                    sharedTransitionScope,
                    animatedContentScope,
                ).fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .floatingToolbarVerticalNestedScroll(
                    expanded = toolbarExpanded,
                    onExpand = { toolbarExpanded = true },
                    onCollapse = { toolbarExpanded = false },
                ).background(MaterialTheme.colorScheme.surface),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                ProjectTopBar(
                    project,
                    showBackButton,
                    onNavigateBack,
                    scrollBehavior,
                    sharedTransitionScope,
                    animatedContentScope,
                    sharedBoundsEnabled,
                )
            },
            floatingActionButton = {
                val agent by viewModel.newChatAgent.collectAsState()
                WorktreeToolbar(
                    expanded = toolbarExpanded,
                    repo = repo,
                    projects = emptyList(),
                    // Checkouts come from the snapshot; a gateway that sent none has no worktrees to pick.
                    worktreesEnabled = project?.checkout != null,
                    scope = scope,
                    options = options,
                    onSelectRepo = {},
                    onOtherProject = null,
                    onSelectScope = viewModel::selectScope,
                    onNewChat = onNewChat,
                    onNewWorktree = {
                        viewModel.clearWorktreeError()
                        showWorktreeSheet = true
                    },
                    onRemoveWorktree = { viewModel.requestRemoveWorktree(null, it) },
                    newChatEnabled = agents.isNotEmpty(),
                    newChatLabel = agent?.let { stringResource(R.string.sessionlist_project_new_chat_with, it.name) },
                    leadingSelector = { AgentFilter(agents, selectedAgentId, viewModel::selectAgent) },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            val rows by viewModel.rows.collectAsState()
            val refreshing by viewModel.refreshing.collectAsState()
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (project != null && rows.isEmpty()) {
                    SessionListEmptyContent(
                        padding = padding,
                        supportsSessionList = true,
                        scopeName = scope.takeUnless { it == WorktreeScope.All }?.let { scopeLabel(it, options) },
                        onCreateSession = onNewChat,
                    )
                } else {
                    ProjectChatList(
                        viewModel,
                        rows,
                        worktrees,
                        padding,
                        sharedTransitionScope,
                        animatedContentScope,
                        sharedBoundsEnabled,
                    )
                }
            }
        }
    }

    if (showWorktreeSheet && repo != null) {
        val creating by viewModel.creatingWorktree.collectAsState()
        val error by viewModel.worktreeError.collectAsState()
        NewWorktreeSheet(
            repo = repo,
            branches = options.map { it.worktree.branch },
            initialBase = project?.checkout?.branch.orEmpty(),
            error = error,
            creating = creating,
            onFormChanged = viewModel::clearWorktreeError,
            onCreate = viewModel::createWorktree,
            loadRefs = viewModel::repoRefs,
            onDismiss = {
                showWorktreeSheet = false
                viewModel.clearWorktreeError()
            },
        )
    }
    if (showAgentSheet) {
        val usedHere by viewModel.newChatAgentUsedHere.collectAsState()
        val statuses by viewModel.agentStatuses.collectAsState()
        AgentSheet(
            agents = agents,
            firstLastUsed = usedHere,
            statuses = statuses,
            onDismiss = { showAgentSheet = false },
            onPick = {
                showAgentSheet = false
                viewModel.newChat(it)
            },
        )
    }
    pendingRemoval?.let { pending ->
        RemoveWorktreeDialog(
            pending = pending,
            onDismiss = { pendingRemoval = null },
            onConfirm = {
                pendingRemoval = null
                viewModel.removeWorktree(it)
            },
        )
    }
}

@Composable
private fun ProjectEvents(
    viewModel: ProjectViewModel,
    snackbarHostState: SnackbarHostState,
    onNavigateToChat: OnOpenProjectChat,
    onConfirmRemove: (SessionListEvent.ConfirmRemoveWorktree) -> Unit,
) {
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SessionListEvent.NavigateToChat ->
                    onNavigateToChat(event.serverId, event.sessionId, event.cwd, event.updatedAt, event.title)
                is SessionListEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
                is SessionListEvent.ConfirmRemoveWorktree -> onConfirmRemove(event)
            }
        }
    }
}

private fun worktreeScopeOptions(
    project: ProjectSummary,
    worktrees: List<GatewayWorktree>,
) = worktreeScopeOptions(project.repo, worktrees, project.chats.map { it.summary })

/** The whole screen grows out of the home's project row, as the session list does out of its agent card. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.projectSharedBounds(
    project: ProjectSummary?,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
): Modifier {
    if (project == null) return this
    return with(sharedTransitionScope) {
        sharedBounds(
            sharedContentState = rememberSharedContentState(ProjectSharedBoundsKey(project.gateway.id, project.repo)),
            animatedVisibilityScope = animatedContentScope,
            enter = fadeIn(),
            exit = fadeOut(),
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
        )
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
private fun ProjectTopBar(
    project: ProjectSummary?,
    showBackButton: Boolean,
    onNavigateBack: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    sharedBoundsEnabled: Boolean,
) {
    val collapse = scrollBehavior.state.collapsedFraction.coerceIn(0f, 1f)
    // Same as the session list: the title eases between the two rows' sizes as the bar collapses.
    val titleStyle = lerp(MaterialTheme.typography.headlineMedium, MaterialTheme.typography.titleLarge, collapse)
    TwoRowsTopAppBar(
        title = { expanded ->
            SessionListTopBarTitle(
                expanded = expanded,
                collapsedFraction = collapse,
                sharedKey = ProjectTitleSharedBoundsKey(project?.gateway?.id.orEmpty(), project?.repo.orEmpty()),
                title = project?.repo?.let(::projectName).orEmpty(),
                titleStyle = titleStyle,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
                sharedBoundsEnabled = sharedBoundsEnabled && project != null,
            )
        },
        subtitle =
            project?.let { shown ->
                { Text(shown.gateway.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
        navigationIcon = {
            if (showBackButton) {
                FilledTonalIconButton(onClick = onNavigateBack, modifier = Modifier.handCursor()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.sessionlist_back_desc),
                    )
                }
            }
        },
        collapsedHeight = TopAppBarDefaults.LargeAppBarCollapsedHeight,
        expandedHeight = TopAppBarDefaults.LargeAppBarExpandedHeight,
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun ProjectChatList(
    viewModel: ProjectViewModel,
    rows: List<ProjectChat>,
    worktrees: List<GatewayWorktree>,
    padding: PaddingValues,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    sharedBoundsEnabled: Boolean,
) {
    val live by viewModel.liveChatKeys.collectAsState()
    val working by viewModel.inProgressChatKeys.collectAsState()
    val running by viewModel.runningChatKeys.collectAsState()
    val chatsBySummary = rows.associateBy { it.summary }
    val refreshing by viewModel.refreshing.collectAsState()
    val grouped = groupSessionsByDate(rows.map { it.summary }, refreshing)
    val removing by viewModel.removingWorktreeIds.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = 16.dp,
                top = padding.calculateTopPadding() + 16.dp,
                end = 16.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        grouped.forEach { (date, summaries) ->
            item(key = "date:$date") {
                Text(
                    date,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(summaries.mapNotNull { chatsBySummary[it] }, key = { it.key }) { chat ->
                val worktree = chat.worktree?.let { path -> worktrees.firstOrNull { isSameCwd(it.path, path) } }
                val isLive = chat.key in live
                ProjectChatRow(
                    chat = chat,
                    worktree = worktree,
                    worktreeBranch = worktree?.displayName ?: chat.worktree?.let { pathSegments(it).lastOrNull() },
                    live = isLive,
                    working = chat.key in working,
                    removing = worktree?.id in removing,
                    actions =
                        ProjectChatActions(
                            onClose = if (isLive || chat.key in running) ({ viewModel.closeChat(chat) }) else null,
                            onRemoveWorktree = worktree?.let { { viewModel.requestRemoveWorktree(chat, it.id) } },
                            onDelete = if (viewModel.canDelete(chat)) ({ viewModel.deleteChat(chat) }) else null,
                        ),
                    onClick = { viewModel.openChat(chat) },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedContentScope = animatedContentScope,
                    sharedBoundsEnabled = sharedBoundsEnabled,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}
