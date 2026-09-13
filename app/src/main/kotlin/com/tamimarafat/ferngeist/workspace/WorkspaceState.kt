package com.tamimarafat.ferngeist.workspace

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * The session a pane hosts: its two ids plus the two fields a pane-built `ChatViewModel` cannot
 * obtain anywhere else.
 *
 * A pane is not a nav destination, so it has no route arguments to read the cwd and title from —
 * these four always travel together and are only meaningful together.
 */
data class WorkspaceSelection(
    val serverId: String,
    val sessionId: String,
    val cwd: String,
    val title: String,
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
    mintedSessionId: String? = null,
    cwd: String = "",
    title: String = "",
) {
    var selectedServerId by mutableStateOf(selectedServerId)
        private set

    var selectedSessionId by mutableStateOf(selectedSessionId)
        private set

    var agentsInLeft by mutableStateOf(agentsInLeft)
        private set

    /**
     * The real session id a create-on-arrival chat minted, carried so it survives process
     * death. `ChatViewModel` writes this into its `SavedStateHandle`, but a pane builds that
     * handle by hand (panes are not nav destinations), so nothing else would persist it.
     */
    var mintedSessionId by mutableStateOf(mintedSessionId)

    /**
     * Working directory and title of [selectedSessionId]. The pane-built `ChatViewModel`
     * reads both out of its `SavedStateHandle`, which a nav destination would have seeded
     * from its route arguments — a pane has no route, so the selection carries them.
     */
    var cwd by mutableStateOf(cwd)
        private set

    var title by mutableStateOf(title)
        private set

    fun selectAgent(serverId: String) {
        selectedServerId = serverId
        selectedSessionId = null
        agentsInLeft = false
    }

    fun selectSession(selection: WorkspaceSelection) {
        apply(selection, agentsInLeft = false)
    }

    /** Restores into `[agents][chat]` — the cold-start and pinned-back target. */
    fun resumeSession(selection: WorkspaceSelection) {
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
        this.agentsInLeft = agentsInLeft
    }

    /** Steps the left pane up to the agent list; the selected chat stays pinned on the right. */
    fun backToAgents() {
        agentsInLeft = true
    }

    @OptIn(ExperimentalMaterial3AdaptiveApi::class)
    fun paneValue(maxPartitions: Int): ThreePaneScaffoldValue =
        workspacePaneValue(maxPartitions, selectedServerId, selectedSessionId, agentsInLeft)

    companion object {
        val Saver: Saver<WorkspaceState, Any> =
            listSaver(
                save = {
                    listOf(
                        it.selectedServerId,
                        it.selectedSessionId,
                        it.agentsInLeft,
                        it.mintedSessionId,
                        it.cwd,
                        it.title,
                    )
                },
                restore = {
                    WorkspaceState(
                        selectedServerId = it[0] as String?,
                        selectedSessionId = it[1] as String?,
                        agentsInLeft = it[2] as Boolean,
                        mintedSessionId = it[3] as String?,
                        cwd = it[4] as String,
                        title = it[5] as String,
                    )
                },
            )
    }
}

@Composable
fun rememberWorkspaceState(): WorkspaceState =
    rememberSaveable(saver = WorkspaceState.Saver) {
        WorkspaceState(selectedServerId = null, selectedSessionId = null, agentsInLeft = false)
    }
