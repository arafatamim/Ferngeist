package com.tamimarafat.ferngeist.feature.sessionlist.project

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentclientprotocol.annotations.UnstableApi
import com.tamimarafat.ferngeist.acp.bridge.catalog.SessionCatalog
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectCatalog
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectChat
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectSummary
import com.tamimarafat.ferngeist.core.common.IoDispatcher
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.NEW_SESSION_ARG
import com.tamimarafat.ferngeist.core.model.isSameCwd
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListEvent
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListRepositories
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.ScopeSelection
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.WorktreeScope
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.displayName
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.effectiveScope
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.scopeCwd
import com.tamimarafat.ferngeist.feature.sessionlist.worktree.SessionWorktrees
import com.tamimarafat.ferngeist.gateway.GatewayGitRefs
import com.tamimarafat.ferngeist.gateway.GatewayWorktree
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Navigation arguments of the project route, `project/{gatewaySourceId}?repo=`. */
const val PROJECT_GATEWAY_ARG = "gatewaySourceId"
const val PROJECT_REPO_ARG = "repo"

/**
 * One project on one gateway: every chat its bound agents reported in it, narrowed to a worktree.
 *
 * Reads the [ProjectCatalog] snapshot only; opening the screen makes no `/v1/worktrees` call.
 * Row actions run against the row's own agent, since one project mixes several.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProjectViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val savedStateHandle: SavedStateHandle,
        private val projectCatalog: ProjectCatalog,
        private val sessionCatalog: SessionCatalog,
        repos: SessionListRepositories,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val targetRepository = repos.launchableTargetRepository
        val gatewaySourceId: String = savedStateHandle.get<String>(PROJECT_GATEWAY_ARG).orEmpty()

        /** The repo on screen; the project sheet moves it, and the handle keeps it across process death. */
        private val repo: StateFlow<String> = savedStateHandle.getStateFlow(PROJECT_REPO_ARG, "")

        /** This gateway's projects in home order; other gateways are reached through the home. */
        val projects: StateFlow<List<ProjectSummary>> =
            projectCatalog.state
                .map { state -> state.projects.filter { it.gateway.id == gatewaySourceId } }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        val project: StateFlow<ProjectSummary?> =
            combine(projects, repo) { all, picked -> all.firstOrNull { isSameCwd(it.repo, picked) } }
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        private val targets: StateFlow<List<LaunchableTarget.GatewayAgent>> =
            repos.launchableTargetRepository
                .getTargets()
                .map { all ->
                    all.filterIsInstance<LaunchableTarget.GatewayAgent>().filter {
                        it.gatewaySource.id ==
                            gatewaySourceId
                    }
                }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        private val scopeSelection = MutableStateFlow<ScopeSelection?>(null)

        val scope: StateFlow<WorktreeScope> =
            combine(scopeSelection, repo) { selection, picked -> effectiveScope(selection, picked) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, WorktreeScope.All)

        private val agentFilter = savedStateHandle.getStateFlow<String?>("agentFilter", null)
        val selectedAgentId =
            combine(agentFilter, targets) { id, bound ->
                id?.takeIf { picked ->
                    bound.any {
                        it.id ==
                            picked
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        val rows: StateFlow<List<ProjectChat>> =
            combine(project, scope, selectedAgentId) { current, picked, agentId ->
                chatsInScope(current?.chats.orEmpty(), picked, agentId)
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        val worktrees: StateFlow<List<GatewayWorktree>> =
            project
                .map { it?.worktrees().orEmpty() }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        val agentStatuses =
            projectCatalog.state
                .map { it.agentStatuses }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

        /** The agent + starts a chat with, named by the FAB. */
        val newChatAgent: StateFlow<LaunchableTarget.GatewayAgent?> =
            combine(
                project,
                scope,
                targets,
                selectedAgentId,
                agentStatuses,
            ) { current, picked, bound, agentId, statuses ->
                val available = bound.filter { statuses[it.id]?.launchAvailable != false }
                if (agentId != null) {
                    available.firstOrNull { it.id == agentId }
                } else {
                    defaultAgent(current?.chats.orEmpty(), picked, available)
                }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        /** The agents + offers, in the sheet's order: [newChatAgent] first. */
        val agents: StateFlow<List<LaunchableTarget.GatewayAgent>> =
            combine(newChatAgent, targets, ::agentsInSheetOrder)
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        /** True when [newChatAgent] has a chat in this project, so the sheet marks it "Last used here". */
        val newChatAgentUsedHere: StateFlow<Boolean> =
            combine(project, newChatAgent) { current, agent ->
                agent != null && current?.chats.orEmpty().any { it.target.id == agent.id }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

        val inProgressChatKeys = projectCatalog.inProgressChatKeys
        val activityUpdatedAt = projectCatalog.activityUpdatedAt

        /** [ProjectChat.key]s of chats holding a live connection, across this gateway's agents. */
        val liveChatKeys: StateFlow<Set<String>> =
            targets
                .flatMapLatest { bound ->
                    if (bound.isEmpty()) return@flatMapLatest flowOf(emptySet())
                    combine(
                        bound.map { target ->
                            sessionCatalog.connectedSessionIds(target.id).map { ids -> ids.map { "${target.id}/$it" } }
                        },
                    ) { keys -> keys.flatMap { it }.toSet() }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

        private val _events = MutableSharedFlow<SessionListEvent>()
        val events = _events.asSharedFlow()

        private val _removingWorktreeIds = MutableStateFlow<Set<String>>(emptySet())
        val removingWorktreeIds: StateFlow<Set<String>> = _removingWorktreeIds.asStateFlow()

        /**
         * Worktree create and remove go to the gateway, so any of its agents can carry them; the
         * first bound one does.
         */
        private val worktreeOps: StateFlow<SessionWorktrees?> =
            targets
                .map { it.firstOrNull()?.id }
                .distinctUntilChanged()
                .map { serverId ->
                    serverId?.let {
                        SessionWorktrees(
                            context = context,
                            serverId = it,
                            repos = repos,
                            emitEvent = { event -> _events.emit(event) },
                            openChat = { _, path ->
                                selectScope(WorktreeScope.Worktree(path))
                                newChat()
                            },
                            ioDispatcher = ioDispatcher,
                        )
                    }
                }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        val creatingWorktree: StateFlow<Boolean> =
            worktreeOps
                .flatMapLatest { it?.creating ?: flowOf(false) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, false)

        val worktreeError: StateFlow<String?> =
            worktreeOps
                .flatMapLatest { it?.error ?: flowOf(null) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        private val refreshingRunning = MutableStateFlow(false)
        val refreshing: StateFlow<Boolean> =
            combine(projectCatalog.refreshing, refreshingRunning) { gateways, running ->
                gatewaySourceId in gateways ||
                    running
            }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

        private val _runningChatKeys = MutableStateFlow<Set<String>>(emptySet())
        val runningChatKeys = _runningChatKeys.asStateFlow()

        init {
            refresh()
        }

        fun selectAgent(id: String?) {
            savedStateHandle["agentFilter"] = id
        }

        fun refresh() {
            projectCatalog.refresh(gatewaySourceId, force = true)
            viewModelScope.launch(ioDispatcher) {
                refreshingRunning.value = true
                try {
                    _runningChatKeys.value =
                        targetRepository
                            .getTargets()
                            .first()
                            .filterIsInstance<LaunchableTarget.GatewayAgent>()
                            .filter { it.gatewaySource.id == gatewaySourceId }
                            .flatMap { target ->
                                sessionCatalog.runningSessionIds(target.id).orEmpty().map { "${target.id}/$it" }
                            }.toSet()
                } finally {
                    refreshingRunning.value = false
                }
            }
        }

        fun selectScope(picked: WorktreeScope) {
            scopeSelection.value = repo.value.takeIf { it.isNotBlank() }?.let { ScopeSelection(it, picked) }
        }

        fun openChat(chat: ProjectChat) {
            viewModelScope.launch {
                _events.emit(
                    SessionListEvent.NavigateToChat(
                        serverId = chat.target.id,
                        sessionId = chat.summary.id,
                        cwd = chat.summary.cwd ?: repo.value,
                        updatedAt = chat.summary.updatedAt,
                        title = chat.summary.title,
                    ),
                )
            }
        }

        /** A new chat in the picked checkout with [agent]; the chat screen creates it on arrival. */
        fun newChat(agent: LaunchableTarget.GatewayAgent? = newChatAgent.value) {
            agent ?: return
            if (agentStatuses.value[agent.id]?.launchAvailable == false) return
            viewModelScope.launch {
                _events.emit(
                    SessionListEvent.NavigateToChat(
                        serverId = agent.id,
                        sessionId = NEW_SESSION_ARG,
                        cwd = scopeCwd(repo.value, scope.value),
                        updatedAt = System.currentTimeMillis(),
                        title = null,
                    ),
                )
            }
        }

        fun closeChat(chat: ProjectChat) =
            runChatAction(chat) { check(sessionCatalog.closeSession(chat.target.id, chat.summary.id)) }

        fun deleteChat(chat: ProjectChat) =
            runChatAction(chat) {
                sessionCatalog.deleteSession(chat.target.id, chat.summary.id)
                // The snapshot lists the chat until the gateway stops reporting it.
                projectCatalog.refresh(gatewaySourceId, force = true)
            }

        /**
         * Delete is offered only when the row's agent advertises `session/delete`: its live capabilities once
         * connected, else what the gateway saw when it listed the agent's chats.
         */
        @OptIn(UnstableApi::class)
        fun canDelete(chat: ProjectChat): Boolean =
            (
                sessionCatalog.agentCapabilities(chat.target.id)?.sessionCapabilities
                    ?: agentStatuses.value[chat.target.id]?.sessionCapabilities
            )?.delete != null

        /** Asks to confirm removing a worktree and the chats in it; [chat] is the row it was asked from. */
        fun requestRemoveWorktree(
            chat: ProjectChat?,
            worktreeId: String,
        ) {
            val worktree = worktrees.value.firstOrNull { it.id == worktreeId } ?: return
            viewModelScope.launch {
                _events.emit(
                    SessionListEvent.ConfirmRemoveWorktree(
                        sessionId = chat?.summary?.id,
                        worktreeId = worktreeId,
                        branch = worktree.displayName,
                        chatCount =
                            chatsInScope(
                                project.value?.chats.orEmpty(),
                                WorktreeScope.Worktree(worktree.path),
                            ).size,
                        discardChanges = worktree.dirty == true,
                    ),
                )
            }
        }

        fun removeWorktree(request: SessionListEvent.ConfirmRemoveWorktree) {
            viewModelScope.launch(ioDispatcher) {
                _removingWorktreeIds.update { it + request.worktreeId }
                try {
                    removeConfirmedWorktree(request)
                } finally {
                    _removingWorktreeIds.update { it - request.worktreeId }
                }
            }
        }

        fun createWorktree(
            repo: String,
            base: String?,
            branch: String?,
        ) {
            viewModelScope.launch { worktreeOps.value?.create(repo, base, branch, rememberCwd = false) }
        }

        suspend fun repoRefs(repo: String): GatewayGitRefs = worktreeOps.value?.repoRefs(repo) ?: GatewayGitRefs()

        fun clearWorktreeError() {
            worktreeOps.value?.clearError()
        }

        private fun runChatAction(
            chat: ProjectChat,
            action: suspend () -> Unit,
        ) {
            viewModelScope.launch(ioDispatcher) {
                if (sessionCatalog.isStreaming(chat.target.id, chat.summary.id)) {
                    _events.emit(
                        SessionListEvent.ShowError(
                            context.getString(R.string.sessionlist_action_busy),
                        ),
                    )
                    return@launch
                }
                runCatching { action() }.onFailure { error ->
                    if (error is CancellationException) throw error
                    _events.emit(SessionListEvent.ShowError(context.getString(R.string.sessionlist_action_failed)))
                }
            }
        }

        /**
         * Every chat in the worktree goes with it, as in the session list: each is closed first, since
         * its agent process holds the directory, and a chat still responding refuses the removal.
         */
        private suspend fun removeConfirmedWorktree(request: SessionListEvent.ConfirmRemoveWorktree) {
            val ops = worktreeOps.value ?: return
            val path = worktrees.value.firstOrNull { it.id == request.worktreeId }?.path ?: return
            val chats = chatsInScope(project.value?.chats.orEmpty(), WorktreeScope.Worktree(path))
            if (chats.any { sessionCatalog.isStreaming(it.target.id, it.summary.id) }) {
                _events.emit(
                    SessionListEvent.ShowError(context.getString(R.string.sessionlist_worktree_error_chat_busy)),
                )
                return
            }
            chats.forEach { runCatching { sessionCatalog.closeSession(it.target.id, it.summary.id) } }
            if (!ops.remove(request)) return
            val picked = (scope.value as? WorktreeScope.Worktree)?.path
            if (picked != null && isSameCwd(picked, path)) selectScope(WorktreeScope.All)
            chats.forEach { runCatching { sessionCatalog.deleteSession(it.target.id, it.summary.id) } }
            projectCatalog.refresh(gatewaySourceId, force = true)
        }
    }
