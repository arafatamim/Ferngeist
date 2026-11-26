package com.tamimarafat.ferngeist.feature.chat.ui.composer.model

import com.tamimarafat.ferngeist.core.model.ChatConfigChoice

private val nonAlphanumeric = Regex("[^\\p{L}\\p{N}]+")

/** Where fuzzy-only matches start ranking, below every exact tier. */
private const val FUZZY_RANK = 3

/** Lowercase letters and digits only, so "GPT-4.1", "gpt 4.1" and "gpt41" all compare equal. */
private fun String.searchKey(): String = lowercase().replace(nonAlphanumeric, "")

/** A field's [key] plus where each of its words starts in it, so fuzzy hits can anchor to a word. */
private class SearchField(
    text: String,
) {
    private val words = text.lowercase().split(nonAlphanumeric).filter { it.isNotEmpty() }
    val key = words.joinToString("")
    private val wordStarts = words.runningFold(0) { at, word -> at + word.length }.dropLast(1)

    fun hasWordStartingWith(prefix: String): Boolean = wordStarts.any { key.startsWith(prefix, it) }

    /**
     * Letters skipped taking [word]'s characters in order from one of this field's word starts, or
     * null when no word start leads into it. Anchoring at a word keeps "eekf" off "DeepSeek Flash".
     */
    fun fuzzyGaps(word: String): Int? = wordStarts.mapNotNull { start -> gapsFrom(start, word) }.minOrNull()

    // shortcut: greedy leftmost, so a later and tighter run can be missed; fine for model names.
    private fun gapsFrom(
        start: Int,
        word: String,
    ): Int? {
        if (key.getOrNull(start) != word.first()) return null
        val end = word.drop(1).fold(start) { at, c -> key.indexOf(c, at + 1).takeIf { it >= 0 } ?: return null }
        return end - start + 1 - word.length
    }
}

/**
 * How well [choice] answers [query], lower is better, or null when it doesn't. Every query word must
 * turn up in the label, value, description or [groupLabel] ignoring case and punctuation, or else
 * fuzzily (its letters in order, from a word start) in the label or value: "dsv4f" finds
 * DeepSeek V4.1 Flash. Exact hits rank 0 when the label starts with the query, 1 when one of its
 * words does, 2 otherwise; any fuzzy word drops the choice below them, tighter runs first.
 */
internal fun modelMatchRank(
    choice: ChatConfigChoice,
    groupLabel: String?,
    query: String,
): Int? {
    val words = query.split(nonAlphanumeric).map { it.lowercase() }.filter { it.isNotEmpty() }
    // Each field is keyed on its own so a word can't match across the seam of two fields.
    val exactFields = listOfNotNull(choice.label, choice.value, choice.description, groupLabel).map { it.searchKey() }
    val label = SearchField(choice.label)
    val fuzzyFields = listOf(label, SearchField(choice.value))
    val wordCosts =
        words.map { word ->
            if (exactFields.any { it.contains(word) }) 0 else fuzzyFields.mapNotNull { it.fuzzyGaps(word) }.minOrNull()
        }
    if (wordCosts.any { it == null }) return null
    return when {
        wordCosts.any { it != 0 } -> FUZZY_RANK + wordCosts.sumOf { it ?: 0 }
        label.key.startsWith(words.joinToString("")) -> 0
        words.isNotEmpty() && label.hasWordStartingWith(words.first()) -> 1
        else -> 2
    }
}
