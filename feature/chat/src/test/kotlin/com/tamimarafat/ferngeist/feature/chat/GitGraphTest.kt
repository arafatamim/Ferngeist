package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.git.graph.GraphEdge
import com.tamimarafat.ferngeist.core.git.graph.GraphStroke
import com.tamimarafat.ferngeist.core.git.graph.graphReach
import com.tamimarafat.ferngeist.core.git.graph.layoutGraph
import com.tamimarafat.ferngeist.core.git.graph.strokesFor
import com.tamimarafat.ferngeist.core.model.ChatGitCommit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitGraphTest {
    private fun commit(
        sha: String,
        vararg parents: String,
    ) = ChatGitCommit(
        sha = sha,
        parents = parents.toList(),
        subject = sha,
        authorName = "A",
        authorEmail = "a@x",
        authorTimeMillis = 0,
    )

    @Test
    fun linearHistory_staysInLaneZero() {
        val rows = layoutGraph(listOf(commit("c", "b"), commit("b", "a"), commit("a")))

        assertEquals(listOf(0, 0, 0), rows.map { it.lane })
        assertEquals(emptyList<GraphEdge>(), rows[0].up)
        assertEquals(listOf(GraphEdge(0, 0, 0)), rows[1].up)
        assertEquals(emptyList<GraphEdge>(), rows.last().down)
    }

    @Test
    fun branchAndMerge_sideCommitsTakeLaneOneAndRejoin() {
        // m merges side branch s2-s1 back onto main; both fork from a.
        val rows =
            layoutGraph(
                listOf(
                    commit("m", "b", "s2"),
                    commit("b", "a"),
                    commit("s2", "s1"),
                    commit("s1", "a"),
                    commit("a"),
                ),
            )

        assertEquals(listOf(0, 0, 1, 1, 0), rows.map { it.lane })
        assertTrue(rows[0].isMerge)
        assertEquals(listOf(GraphEdge(0, 0, 0), GraphEdge(0, 1, 1)), rows[0].down)
        // a collects both lanes: its own and the side branch converging.
        assertEquals(listOf(GraphEdge(0, 0, 0), GraphEdge(1, 0, 1)), rows[4].up)
        assertEquals(1, rows[2].color)
    }

    @Test
    fun secondBranchTip_opensNewLane() {
        val rows = layoutGraph(listOf(commit("x", "a"), commit("y", "a"), commit("a")))

        assertEquals(listOf(0, 1, 0), rows.map { it.lane })
        assertEquals(listOf(GraphEdge(0, 0, 0)), rows[1].up)
        assertEquals(listOf(GraphEdge(0, 0, 0), GraphEdge(1, 0, 1)), rows[2].up)
    }

    @Test
    fun octopusMerge_fansOutToEveryParent() {
        val rows = layoutGraph(listOf(commit("m", "a", "b", "c"), commit("a"), commit("b"), commit("c")))

        assertEquals(
            listOf(GraphEdge(0, 0, 0), GraphEdge(0, 1, 1), GraphEdge(0, 2, 2)),
            rows[0].down,
        )
        assertEquals(listOf(0, 1, 2), rows.drop(1).map { it.lane })
    }

    @Test
    fun freedLane_isReusedByTheNextTip() {
        val rows =
            layoutGraph(
                listOf(
                    commit("x", "a"),
                    commit("y"),
                    commit("z", "a"),
                    commit("a"),
                ),
            )

        // y is a root, so its lane 1 frees and z takes it.
        assertEquals(listOf(0, 1, 1, 0), rows.map { it.lane })
    }

    @Test
    fun bends_spanAFullRow_andNeighbourDropsTheStraightHalf() {
        val rows =
            layoutGraph(
                listOf(
                    commit("m", "b", "s2"),
                    commit("b", "a"),
                    commit("s2", "s1"),
                    commit("s1", "a"),
                    commit("a"),
                ),
            )

        fun strokes(i: Int) = strokesFor(rows[i], rows.getOrNull(i - 1), rows.getOrNull(i + 1))

        // m's fork into lane 1 runs from its node to b's row middle.
        assertTrue(GraphStroke(0, 1, 1, 0.5f, 1.5f) in strokes(0))
        // b leaves lane 1's top half to that bend.
        assertTrue(strokes(1).none { it.from == 1 && it.startY == 0f })
        // a's join from lane 1 starts at s1's node; s1 drops its straight bottom half.
        assertTrue(GraphStroke(1, 0, 1, -0.5f, 0.5f) in strokes(4))
        assertTrue(strokes(3).none { it.from == 1 && it.endY == 1f })
        // Lane 0 stays continuous.
        assertTrue(GraphStroke(0, 0, 0, 0.5f, 1f) in strokes(3))
    }

    @Test
    fun reach_countsOnlyTheLinesInsideEachRow() {
        val rows =
            layoutGraph(
                listOf(
                    commit("m", "b", "s2"),
                    commit("b", "a"),
                    commit("s2", "s1"),
                    commit("s1", "a"),
                    commit("a"),
                ),
            )
        val strokes = rows.indices.map { strokesFor(rows[it], rows.getOrNull(it - 1), rows.getOrNull(it + 1)) }

        // m and a only see the half of a bend that leaves or lands on their own node.
        assertEquals(listOf(0.5f, 1f, 1f, 1f, 0.5f), graphReach(strokes))
    }

    @Test
    fun bends_withNoNeighbour_stayInsideTheirHalfRow() {
        val rows =
            layoutGraph(
                listOf(
                    commit("m", "b", "s1"),
                    commit("b", "a"),
                    commit("s1", "a"),
                    commit("a"),
                ),
            )

        // A date header below m: its fork bends inside m's bottom half instead of reaching b.
        assertTrue(GraphStroke(0, 1, 1, 0.5f, 1f) in strokesFor(rows[0], prev = null, next = null))
        // A header above a: the join from lane 1 bends inside a's top half.
        assertTrue(GraphStroke(1, 0, 1, 0f, 0.5f) in strokesFor(rows[3], prev = null, next = null))
    }
}
