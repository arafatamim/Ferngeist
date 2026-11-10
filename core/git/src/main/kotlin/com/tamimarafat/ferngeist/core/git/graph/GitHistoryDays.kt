package com.tamimarafat.ferngeist.core.git.graph

/**
 * How many commits each day header covers, keyed by the index of that day's first commit in [days]
 * (one day per commit, in row order). A history whose rows are not grouped by day gets a header per
 * run, and so a count per run.
 */
fun <T> dayGroupSizes(days: List<T>): Map<Int, Int> {
    val sizes = mutableMapOf<Int, Int>()
    var start = 0
    days.forEachIndexed { index, day ->
        if (index == days.lastIndex || days[index + 1] != day) {
            sizes[start] = index - start + 1
            start = index + 1
        }
    }
    return sizes
}
