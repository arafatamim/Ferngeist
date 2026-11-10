package com.tamimarafat.ferngeist.core.git.graph

import com.tamimarafat.ferngeist.core.model.ChatGitCommit

/** A line segment in half a graph row, from lane [from] to lane [to], drawn in palette slot [color]. */
data class GraphEdge(
    val from: Int,
    val to: Int,
    val color: Int,
)

/**
 * One commit's slice of the history graph. [up] runs from the row's top edge to the node
 * (or straight through for other lanes); [down] runs from the node to the bottom edge.
 */
data class GraphRow(
    val lane: Int,
    val color: Int,
    val isMerge: Boolean,
    val up: List<GraphEdge>,
    val down: List<GraphEdge>,
)

/**
 * Assigns each commit (newest first, topological order) a lane and the edges that join it
 * to its neighbours. The first commit's first-parent chain keeps lane 0, so linear
 * history draws as one straight line.
 */
fun layoutGraph(commits: List<ChatGitCommit>): List<GraphRow> {
    val lanes = Lanes()
    return commits.map { commit ->
        var lane = lanes.waiting.indexOf(commit.sha)
        if (lane < 0) lane = lanes.allocate()
        val color = lanes.colors[lane]
        val up = lanes.edgesInto(commit.sha, lane)
        // Children converging on this commit stop here.
        lanes.waiting.replaceAll { if (it == commit.sha) null else it }

        lanes.waiting[lane] = commit.parents.firstOrNull()
        val newSlots = mutableSetOf<Int>()
        val mergeTargets =
            commit.parents.drop(1).map { parent ->
                lanes.waiting.indexOf(parent).takeIf { it >= 0 }
                    ?: lanes.allocate().also {
                        lanes.waiting[it] = parent
                        newSlots += it
                    }
            }
        val down =
            lanes.passThrough(skip = newSlots) + mergeTargets.map { GraphEdge(lane, it, lanes.colors[it]) }
        lanes.trim()
        GraphRow(lane = lane, color = color, isMerge = commit.parents.size > 1, up = up, down = down)
    }
}

/** The sha each lane is waiting for, and the palette slot it draws in. */
private class Lanes {
    val waiting = mutableListOf<String?>()
    val colors = mutableListOf<Int>()
    private var nextColor = 0

    fun allocate(): Int {
        val free = waiting.indexOf(null)
        val slot =
            if (free >= 0) {
                free
            } else {
                waiting += null
                colors += 0
                waiting.lastIndex
            }
        colors[slot] = nextColor++
        return slot
    }

    /** Lanes waiting for [sha] bend into [lane]; every other open lane runs straight through. */
    fun edgesInto(
        sha: String,
        lane: Int,
    ): List<GraphEdge> =
        waiting.mapIndexedNotNull { i, target ->
            when (target) {
                null -> null
                sha -> GraphEdge(i, lane, colors[i])
                else -> GraphEdge(i, i, colors[i])
            }
        }

    fun passThrough(skip: Set<Int>): List<GraphEdge> =
        waiting.mapIndexedNotNull { i, target ->
            if (target != null && i !in skip) GraphEdge(i, i, colors[i]) else null
        }

    fun trim() {
        while (waiting.isNotEmpty() && waiting.last() == null) {
            waiting.removeAt(waiting.lastIndex)
            colors.removeAt(colors.lastIndex)
        }
    }
}

/** An edge to draw, with its vertical extent in row heights from this row's top (may spill into neighbours). */
data class GraphStroke(
    val from: Int,
    val to: Int,
    val color: Int,
    val startY: Float,
    val endY: Float,
)

/**
 * The strokes [row] draws. A lane change bends over a full row height, node to node, rather
 * than inside one half row, so it spills into the neighbour and that neighbour drops the
 * straight half it would otherwise draw in the same lane. With no neighbour on that side (the
 * list's end, or a date header between them) the bend stays inside its own half row.
 */
fun strokesFor(
    row: GraphRow,
    prev: GraphRow?,
    next: GraphRow?,
): List<GraphStroke> {
    val bentIn = prev?.down.orEmpty().bentLanes { it.to }
    val bentOut = next?.up.orEmpty().bentLanes { it.from }
    val up =
        row.up.mapNotNull { e ->
            when {
                e.from != e.to -> GraphStroke(e.from, e.to, e.color, if (prev != null) -HALF else 0f, HALF)
                e.from in bentIn -> null
                else -> GraphStroke(e.from, e.to, e.color, 0f, HALF)
            }
        }
    val down =
        row.down.mapNotNull { e ->
            when {
                e.from != e.to -> GraphStroke(e.from, e.to, e.color, HALF, if (next != null) 1 + HALF else 1f)
                e.from in bentOut -> null
                else -> GraphStroke(e.from, e.to, e.color, HALF, 1f)
            }
        }
    return up + down
}

private const val HALF = 0.5f

/**
 * How far right, in lanes, each row's lines reach within its own height, counting the bends
 * that spill in from its neighbours. [strokes] holds [strokesFor] per row, in row order.
 */
fun graphReach(strokes: List<List<GraphStroke>>): List<Float> =
    strokes.indices.map { i ->
        // A neighbour's stroke shifts by one row height to sit in row i's coordinates.
        listOf(-1, 0, 1)
            .flatMap { d -> strokes.getOrNull(i + d).orEmpty().mapNotNull { it.reachWithin(d.toFloat()) } }
            .maxOrNull() ?: 0f
    }

/** Rightmost lane this stroke covers between the row's top and bottom, or null if it misses the row. */
private fun GraphStroke.reachWithin(rowShift: Float): Float? {
    val top = maxOf(startY + rowShift, 0f)
    val bottom = minOf(endY + rowShift, 1f)
    if (top >= bottom) return null

    // Clips always land on a stroke's end or midpoint, where the S-curve matches a straight blend.
    fun laneAt(y: Float) = from + (to - from) * (y - startY - rowShift) / (endY - startY)
    return maxOf(laneAt(top), laneAt(bottom))
}

/** Lanes a bend reaches (via [end]) that have no straight run of their own in the same half row. */
private fun List<GraphEdge>.bentLanes(end: (GraphEdge) -> Int): Set<Int> {
    val straight = filter { it.from == it.to }.map { it.from }.toSet()
    return filter { it.from != it.to }.map(end).toSet() - straight
}
