package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.ChatGitCommit

/**
 * How much of the loaded history is this branch's own work: the first [ownCount] commits, drawn in
 * the lane's own colour, with everything from the base commit down receding to the muted one.
 * [baseLoaded] is true when the base commit is itself loaded, so [ownCount] is exact; when it is
 * false with a page still to come, the base lies further down and every loaded row is the branch's.
 *
 * [ownCount] of 0 with [baseLoaded] false marks nothing: the checkout is not on a branch git can
 * place, or the branch does not start inside this walk.
 */
internal data class GitBranchScope(
    val ownCount: Int,
    val baseLoaded: Boolean,
) {
    companion object {
        /** Nothing marked: every row draws in its lane's own colour. */
        val none = GitBranchScope(ownCount = 0, baseLoaded = false)
    }
}

/**
 * Where [base] — the commit this branch started from — falls in the loaded [commits], newest first.
 *
 * While a page is still to come, every loaded row sits above the base, so they are all this
 * branch's own and a later page may bring the base itself. Once the gateway says there is nothing
 * more and the base is still absent, it is not on this walk at all (a rebase moved the branch off
 * it): marking nothing is the only honest answer.
 */
internal fun gitBranchScope(
    commits: List<ChatGitCommit>,
    base: String?,
    hasMore: Boolean,
): GitBranchScope {
    if (base == null || commits.isEmpty()) return GitBranchScope.none
    val baseIndex = commits.indexOfFirst { it.sha == base }
    return when {
        baseIndex >= 0 -> GitBranchScope(ownCount = baseIndex, baseLoaded = true)
        hasMore -> GitBranchScope(ownCount = commits.size, baseLoaded = false)
        else -> GitBranchScope.none
    }
}

/**
 * How far down a band of the list the lane colour has receded, between its two edges: 0 is the
 * lane's own colour, 1 the muted grey. Each edge matches the band it meets, so the line is whole.
 */
internal data class BandFade(
    val top: Float,
    val bottom: Float,
) {
    companion object {
        val none = BandFade(0f, 0f)
    }
}

/** The blend the band's node sits in, and the one a fadeless band draws in. */
internal val BandFade.mid: Float get() = (top + bottom) / 2f

/** A band that is one colour top to bottom needs no gradient. */
internal val BandFade.isFlat: Boolean get() = top == bottom

/**
 * Where each band of the list draws the lane between its own colour and the muted grey, one band
 * per entry in [marks] and one mark per band. The colour has to change where this branch's own
 * work stops, and a step there would cut the line in two; taking the midpoint of the two bands a
 * junction joins instead puts that change on both sides of it, so the line fades from one colour
 * into the other over the rows around the branch point.
 */
internal fun bandFades(marks: List<Float>): List<BandFade> =
    marks.mapIndexed { index, mark ->
        val above = marks.getOrNull(index - 1) ?: mark
        val below = marks.getOrNull(index + 1) ?: mark
        BandFade(top = (above + mark) / 2f, bottom = (mark + below) / 2f)
    }

/**
 * The blend for every band the history list draws, in the order it draws them: each commit,
 * preceded by the day header that starts its day when one does. A header takes the mark of the
 * commit under it, so the fade runs straight through it. [days] is one day key per commit.
 */
internal fun <T> historyBandFades(
    days: List<T>,
    scope: GitBranchScope,
): List<BandFade> {
    val marks = mutableListOf<Float>()
    days.indices.forEach { index ->
        val mark = if (scope.baseLoaded && index >= scope.ownCount) 1f else 0f
        if (index == 0 || days[index - 1] != days[index]) marks += mark
        marks += mark
    }
    return bandFades(marks)
}
