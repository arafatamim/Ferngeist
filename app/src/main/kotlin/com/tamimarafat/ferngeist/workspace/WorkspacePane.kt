package com.tamimarafat.ferngeist.workspace

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldRole
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue

private const val MAX_PANES = 3

/** The three levels of the workspace spine, in left-to-right render order. */
enum class WorkspacePane {
    AGENTS,
    SESSIONS,
    CHAT,
}

/**
 * Panes to show, left to right, for the current workspace state.
 *
 * `ListDetailPaneScaffold` maps [ThreePaneScaffoldRole.Secondary] to the `listPane` slot
 * (left), [ThreePaneScaffoldRole.Primary] to `detailPane` (middle) and
 * [ThreePaneScaffoldRole.Tertiary] to `extraPane` (right).
 *
 * The scaffold does **not** clamp against `maxHorizontalPartitions` — it scales every
 * expanded pane down to fit. Clamping is therefore this function's job.
 *
 * [agentsInLeft] is meaningful at 2 panes only: it is the pinned-chat back state where the
 * left pane steps up to the agent list while the chat stays on the right.
 */
fun desirablePanes(
    maxPartitions: Int,
    serverId: String?,
    sessionId: String?,
    agentsInLeft: Boolean,
): List<WorkspacePane> =
    when {
        // 3 panes: everything is already visible, so back has nothing to undo.
        maxPartitions >= MAX_PANES -> listOf(WorkspacePane.AGENTS, WorkspacePane.SESSIONS, WorkspacePane.CHAT)

        maxPartitions == 2 && agentsInLeft && sessionId != null ->
            listOf(WorkspacePane.AGENTS, WorkspacePane.CHAT)

        maxPartitions == 2 && sessionId != null ->
            listOf(WorkspacePane.SESSIONS, WorkspacePane.CHAT)

        maxPartitions == 2 && serverId != null ->
            listOf(WorkspacePane.AGENTS, WorkspacePane.SESSIONS)

        maxPartitions == 2 -> listOf(WorkspacePane.AGENTS)

        // 1 pane — matches today's back stack exactly.
        sessionId != null -> listOf(WorkspacePane.CHAT)
        serverId != null -> listOf(WorkspacePane.SESSIONS)
        else -> listOf(WorkspacePane.AGENTS)
    }

/** Maps a [WorkspacePane] onto the scaffold slot it fills. */
fun WorkspacePane.role(): ThreePaneScaffoldRole =
    when (this) {
        WorkspacePane.AGENTS -> ThreePaneScaffoldRole.Secondary
        WorkspacePane.SESSIONS -> ThreePaneScaffoldRole.Primary
        WorkspacePane.CHAT -> ThreePaneScaffoldRole.Tertiary
    }

/**
 * Whether "back" means anything in the workspace.
 *
 * At two panes the left pane can step up to the agent list while the chat stays pinned, so
 * back is live. At three panes everything is already on screen — the chat's own back button
 * and the session list's would both be no-ops, and back belongs to the system (it leaves the
 * screen). At one pane the compact branch owns back entirely.
 *
 * Drives three things that must agree: the [BackHandler], the chat pane's back button and the
 * session list pane's back button. Hence one predicate rather than three copies of the rule.
 */
fun isWorkspaceBackEnabled(
    maxPartitions: Int,
    agentsInLeft: Boolean,
    sessionId: String?,
): Boolean = maxPartitions == 2 && !agentsInLeft && sessionId != null

/** Builds the scaffold value: every pane in [visible] is Expanded, the rest Hidden. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun workspacePaneValue(
    maxPartitions: Int,
    serverId: String?,
    sessionId: String?,
    agentsInLeft: Boolean,
): ThreePaneScaffoldValue {
    val visible = desirablePanes(maxPartitions, serverId, sessionId, agentsInLeft).map { it.role() }

    fun valueOf(role: ThreePaneScaffoldRole) =
        if (role in visible) PaneAdaptedValue.Expanded else PaneAdaptedValue.Hidden

    return ThreePaneScaffoldValue(
        primary = valueOf(ThreePaneScaffoldRole.Primary),
        secondary = valueOf(ThreePaneScaffoldRole.Secondary),
        tertiary = valueOf(ThreePaneScaffoldRole.Tertiary),
    )
}

/**
 * Share of the total pane width each pane should take, keyed by role.
 *
 * Left alone, the scaffold gives every pane the same 412dp preferred width and then awards the
 * *entire* surplus to the highest-priority pane. That pane is [ThreePaneScaffoldRole.Primary] —
 * the middle one, which [role] maps to the session list — so the session list absorbs every
 * spare dp while the chat stays pinned at 412dp.
 *
 * Supplying explicit proportions moves the scaffold onto its proportional path (taken whenever
 * the preferred widths exceed the available width, which these shares guarantee), where the
 * ratios are preserved at any window width.
 *
 * Two constraints the scaffold imposes, both of which have crashed in practice:
 *  - every role must be keyed. Panes compose their content lambda while Hidden, and that
 *    lambda reads its share, so a missing key threw `NoSuchElementException`.
 *  - each share must be in `[0f, 1f]`. Shares are normalised over [panes], so a pane that is
 *    not among them keeps its un-normalised weight, which can exceed one — hence the clamp.
 *    A hidden pane's share is unused, so clamping it costs nothing.
 */
fun workspacePaneWidths(panes: List<WorkspacePane>): Map<ThreePaneScaffoldRole, Float> {
    val total = panes.fold(0f) { sum, pane -> sum + pane.weight() }
    return WorkspacePane.entries.associate { it.role() to (it.weight() / total).coerceIn(0f, 1f) }
}

/**
 * Relative pane weights: the chat is the content, the two lists are navigation beside it.
 * These are the only knobs behind [workspacePaneWidths].
 */
private fun WorkspacePane.weight(): Float =
    when (this) {
        WorkspacePane.AGENTS -> AGENTS_WEIGHT
        WorkspacePane.SESSIONS -> SESSIONS_WEIGHT
        WorkspacePane.CHAT -> CHAT_WEIGHT
    }

private const val AGENTS_WEIGHT = 0.45f
private const val SESSIONS_WEIGHT = 0.60f
private const val CHAT_WEIGHT = 1.0f
