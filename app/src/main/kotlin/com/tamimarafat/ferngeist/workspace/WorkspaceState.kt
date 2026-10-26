package com.tamimarafat.ferngeist.workspace

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver as ComposeSaver

/**
 * The session a pane hosts: its identity and metadata a pane-built `ChatViewModel` cannot
 * obtain anywhere else.
 *
 * A pane is not a nav destination, so it has no route arguments to read the cwd and title from —
 * the listed activity time travels with them so both layouts can guard recent external activity.
 */
data class WorkspaceSelection(
    val serverId: String,
    val sessionId: String,
    val cwd: String,
    val title: String,
    val updatedAt: Long? = null,
)

/** A project the middle pane shows instead of an agent's sessions: one repo on one gateway. */
data class WorkspaceProject(
    val gatewaySourceId: String,
    val repo: String,
)

/**
 * Which agent/session the workspace is showing, and whether the left pane has stepped up to
 * the agent list while the chat stays pinned.
 *
 * Every field is saveable, so the workspace survives the Activity recreation that every fold,
 * rotate and resize causes (the manifest declares no `configChanges`).
 */
@Stable
class WorkspaceState(
    selectedServerId: String?,
    selectedSessionId: String?,
    agentsInLeft: Boolean,
    cwd: String = "",
    title: String = "",
    updatedAt: Long? = null,
) {
    var selectedServerId by mutableStateOf(selectedServerId)
        private set

    var selectedSessionId by mutableStateOf(selectedSessionId)
        private set

    var agentsInLeft by mutableStateOf(agentsInLeft)
        private set

    /**
     * The project the middle pane shows, or null for [selectedServerId]'s sessions. A chat opened
     * from it keeps it, so the pane stays on the project the chat was picked from.
     */
    var selectedProject by mutableStateOf<WorkspaceProject?>(null)
        private set

    /** What the middle pane shows, or null when it shows nothing yet. */
    val placeId: String?
        get() = selectedProject?.let { "${it.gatewaySourceId}:${it.repo}" } ?: selectedServerId

    /**
     * The real session id a create-on-arrival chat minted, carried so it survives process
     * death. `ChatViewModel` writes this into its `SavedStateHandle`, but a pane builds that
     * handle by hand (panes are not nav destinations), so nothing else would persist it.
     */
    var mintedSessionId by mutableStateOf<String?>(null)

    /**
     * Working directory and title of [selectedSessionId]. The pane-built `ChatViewModel`
     * reads both out of its `SavedStateHandle`, which a nav destination would have seeded
     * from its route arguments — a pane has no route, so the selection carries them.
     */
    var cwd by mutableStateOf(cwd)
        private set

    var title by mutableStateOf(title)
        private set

    var updatedAt by mutableStateOf(updatedAt)
        private set

    fun selectAgent(serverId: String) {
        updatedAt = null
        selectedProject = null
        selectedServerId = serverId
        selectedSessionId = null
        agentsInLeft = false
    }

    fun selectProject(project: WorkspaceProject) {
        updatedAt = null
        selectedProject = project
        selectedServerId = null
        selectedSessionId = null
        mintedSessionId = null
        agentsInLeft = false
    }

    fun selectSession(selection: WorkspaceSelection) {
        apply(selection, agentsInLeft = false)
    }

    /** Restores into `[agents][chat]` — the cold-start and pinned-back target. */
    fun resumeSession(selection: WorkspaceSelection) {
        selectedProject = null
        apply(selection, agentsInLeft = true)
    }

    private fun apply(
        selection: WorkspaceSelection,
        agentsInLeft: Boolean,
    ) {
        selectedServerId = selection.serverId
        selectedSessionId = selection.sessionId
        cwd = selection.cwd
        title = selection.title
        updatedAt = selection.updatedAt
        this.agentsInLeft = agentsInLeft
    }

    /** Steps the left pane up to the agent list; the selected chat stays pinned on the right. */
    fun backToAgents() {
        agentsInLeft = true
    }

    /**
     * Drops the selection because the compact routes have taken the presentation over.
     *
     * Keeping it would leave the two fighting: back from the chat would land on the agent list
     * with the selection still set, and the selection would immediately reopen the chat. The
     * route carries the same identity, and widening converts it back — see `WorkspaceRouting`.
     */
    fun clearSelection() {
        selectedProject = null
        selectedServerId = null
        selectedSessionId = null
        mintedSessionId = null
        cwd = ""
        title = ""
        updatedAt = null
        agentsInLeft = false
    }

    @OptIn(ExperimentalMaterial3AdaptiveApi::class)
    fun paneValue(maxPartitions: Int): ThreePaneScaffoldValue =
        workspacePaneValue(maxPartitions, placeId, selectedSessionId, agentsInLeft)

    companion object {
        val saver: ComposeSaver<WorkspaceState, Any> =
            listSaver(
                save = {
                    listOf(
                        it.selectedServerId,
                        it.selectedSessionId,
                        it.agentsInLeft,
                        it.mintedSessionId,
                        it.cwd,
                        it.title,
                        it.selectedProject?.gatewaySourceId,
                        it.selectedProject?.repo,
                        it.updatedAt,
                    )
                },
                restore = {
                    WorkspaceState(
                        selectedServerId = it[0] as String?,
                        selectedSessionId = it[1] as String?,
                        agentsInLeft = it[2] as Boolean,
                        cwd = it[4] as String,
                        title = it[5] as String,
                        updatedAt = it.getOrNull(8) as Long?,
                    ).apply {
                        mintedSessionId = it[3] as String?
                        selectedProject =
                            (it[6] as String?)?.let { gatewaySourceId ->
                                WorkspaceProject(gatewaySourceId, it[7] as String)
                            }
                    }
                },
            )
    }
}

@Composable
fun rememberWorkspaceState(): WorkspaceState =
    rememberSaveable(saver = WorkspaceState.saver) {
        WorkspaceState(selectedServerId = null, selectedSessionId = null, agentsInLeft = false)
    }
