package com.tamimarafat.ferngeist.workspace

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
class WorkspacePanesTest {
    // The scaffold does NOT clamp by maxHorizontalPartitions, so this function must.
    // Visual order is left-to-right: agents, sessions, chat.

    @Test
    fun onePane_followsTodaysBackStack() {
        assertEquals(listOf(WorkspacePane.AGENTS), desirablePanes(1, null, null, false))
        assertEquals(listOf(WorkspacePane.SESSIONS), desirablePanes(1, "a", null, false))
        assertEquals(listOf(WorkspacePane.CHAT), desirablePanes(1, "a", "s", false))
        // agentsInLeft is 2-pane-only; at 1 pane the chat still leads.
        assertEquals(listOf(WorkspacePane.CHAT), desirablePanes(1, "a", "s", true))
    }

    @Test
    fun twoPanes_drillDown() {
        assertEquals(listOf(WorkspacePane.AGENTS), desirablePanes(2, null, null, false))
        assertEquals(
            listOf(WorkspacePane.AGENTS, WorkspacePane.SESSIONS),
            desirablePanes(2, "a", null, false),
        )
        assertEquals(
            listOf(WorkspacePane.SESSIONS, WorkspacePane.CHAT),
            desirablePanes(2, "a", "s", false),
        )
    }

    @Test
    fun twoPanes_backPinsChatAndSwapsLeftPane() {
        // The requested behaviour: [agents][chat]
        assertEquals(
            listOf(WorkspacePane.AGENTS, WorkspacePane.CHAT),
            desirablePanes(2, "a", "s", true),
        )
    }

    @Test
    fun threePanes_allVisibleAndBackIsANoOp() {
        val all = listOf(WorkspacePane.AGENTS, WorkspacePane.SESSIONS, WorkspacePane.CHAT)
        assertEquals(all, desirablePanes(3, null, null, false))
        assertEquals(all, desirablePanes(3, "a", "s", false))
        // agentsInLeft must NOT change the 3-pane result — otherwise back would render
        // [agents][chat][-], contradicting "all panes are visible so back does nothing".
        assertEquals(all, desirablePanes(3, "a", "s", true))
    }

    @Test
    fun paneValue_expandsExactlyTheDesirablePanes() {
        val value = workspacePaneValue(2, "a", "s", true)
        assertEquals(PaneAdaptedValue.Expanded, value[ThreePaneScaffoldRole.Secondary])
        assertEquals(PaneAdaptedValue.Hidden, value[ThreePaneScaffoldRole.Primary])
        assertEquals(PaneAdaptedValue.Expanded, value[ThreePaneScaffoldRole.Tertiary])
    }

    // The scaffold gives every pane the same preferred width and awards the whole surplus to
    // Primary (the middle pane). Left to itself the session list therefore grew wider than the
    // chat. These proportions are what keep the chat the widest pane.

    @Test
    fun threePanes_chatIsWidestAndAgentsNarrowest() {
        val widths = workspacePaneWidths(desirablePanes(3, "a", "s", false))
        val agents = widths.getValue(ThreePaneScaffoldRole.Secondary)
        val sessions = widths.getValue(ThreePaneScaffoldRole.Primary)
        val chat = widths.getValue(ThreePaneScaffoldRole.Tertiary)
        assertTrue("chat must be widest: $widths", chat > sessions)
        assertTrue("sessions must beat agents: $widths", sessions > agents)
    }

    @Test
    fun widths_sumToOneOverTheVisiblePanesOnly() {
        // Shares are normalised across the panes that are on screen, so those always fill
        // the width no matter how many are shown.
        val cases =
            listOf(
                desirablePanes(3, "a", "s", false),
                desirablePanes(2, "a", "s", false),
                desirablePanes(2, "a", "s", true),
                desirablePanes(1, "a", "s", false),
            )
        for (panes in cases) {
            val widths = workspacePaneWidths(panes)
            val visible = panes.fold(0f) { sum, pane -> sum + widths.getValue(pane.role()) }
            assertEquals("visible shares must fill the width: $panes", 1f, visible, 1e-6f)
        }
    }

    @Test
    fun everyRoleIsPresentEvenWhenItsPaneIsHidden() {
        // The scaffold composes a pane's content lambda even while the pane is Hidden, and
        // that lambda reads its share for the role. A missing key threw
        // NoSuchElementException: Key Tertiary is missing in the map — a crash on resize,
        // when the visible panes changed under a live composition.
        val cases =
            listOf(
                desirablePanes(1, "a", "s", false),
                desirablePanes(2, "a", "s", true),
                desirablePanes(3, "a", "s", false),
            )
        for (panes in cases) {
            assertEquals(
                "all roles must be keyed: $panes",
                ThreePaneScaffoldRole.entries.toSet(),
                workspacePaneWidths(panes).keys,
            )
        }
    }

    @Test
    fun everyShareIsAValidWidthProportion() {
        // `preferredWidth(Float)` throws IllegalArgumentException("invalid width proportion")
        // for anything outside [0f, 1f]. Normalising over only the visible panes leaves a
        // hidden pane's share un-normalised, which can exceed one — that crashed on reset.
        val cases =
            listOf(
                desirablePanes(1, "a", "s", false),
                desirablePanes(1, "a", null, false),
                desirablePanes(2, "a", "s", false),
                desirablePanes(2, "a", "s", true),
                desirablePanes(3, "a", "s", false),
            )
        for (panes in cases) {
            for ((role, share) in workspacePaneWidths(panes)) {
                assertTrue(
                    "share for $role out of range in $panes: $share",
                    share in 0f..1f,
                )
            }
        }
    }

    @Test
    fun chatGetsTheWholeWidthWhenItIsTheOnlyPane() {
        val widths = workspacePaneWidths(desirablePanes(1, "a", "s", false))
        assertEquals(1f, widths.getValue(ThreePaneScaffoldRole.Tertiary), 1e-6f)
    }

    // Back is live only where it can act. The session list shipped a back button at three
    // panes, where the agent list is already on screen and the button did nothing.

    @Test
    fun backDisabledAtThreePanes() {
        // Everything is visible, so there is nothing to undo.
        assertFalse(isWorkspaceBackEnabled(3, false, "s"))
        assertFalse(isWorkspaceBackEnabled(3, true, "s"))
        assertFalse(isWorkspaceBackEnabled(3, false, null))
    }

    @Test
    fun backDisabledAtOnePane() {
        // The compact branch owns back.
        assertFalse(isWorkspaceBackEnabled(1, false, "s"))
        assertFalse(isWorkspaceBackEnabled(1, true, null))
    }

    @Test
    fun backEnabledOnlyAtTwoPanesWithTheAgentListHidden() {
        assertTrue(isWorkspaceBackEnabled(2, false, "s"))
        // Already stepped up: back would be a no-op.
        assertFalse(isWorkspaceBackEnabled(2, true, "s"))
        // No chat pinned: back has nothing to preserve.
        assertFalse(isWorkspaceBackEnabled(2, false, null))
    }
}
