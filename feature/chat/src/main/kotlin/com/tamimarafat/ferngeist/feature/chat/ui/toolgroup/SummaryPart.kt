package com.tamimarafat.ferngeist.feature.chat.ui.toolgroup

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.feature.chat.ui.ToolVerb
import com.tamimarafat.ferngeist.feature.chat.ui.joinPhrases
import com.tamimarafat.ferngeist.feature.chat.ui.toolgroup.RailRowFrame
import com.tamimarafat.ferngeist.feature.chat.ui.toolgroup.ThoughtRowContent
import com.tamimarafat.ferngeist.feature.chat.ui.toolgroup.ToolRowContent
import com.tamimarafat.ferngeist.feature.chat.ui.toolgroup.description

/** One resolved summary part: the phrase (or twice-string) for a verb and its count. */
internal data class SummaryPart(
    @PluralsRes val phrase: Int,
    @StringRes val twice: Int?,
    val count: Int,
    @StringRes val label: Int? = null,
)

/** Picks the past-tense or present-continuous phrase per verb. Pure, so JVM tests can cover it. */
internal fun summaryParts(
    verbs: List<ToolVerb>,
    running: Boolean,
): List<SummaryPart> =
    toolVerbCounts(verbs).map { (verb, count) ->
        if (running && verb.runningLabel != null) {
            SummaryPart(verb.phrase, null, count, verb.runningLabel)
        } else if (running && verb.runningPhrase != null) {
            SummaryPart(verb.runningPhrase, verb.runningTwice, count)
        } else {
            SummaryPart(verb.phrase, verb.twice, count)
        }
    }

/** Items counted by verb, in the order each verb first appears, with FAILED always last. */
internal fun toolVerbCounts(verbs: List<ToolVerb>): List<Pair<ToolVerb, Int>> =
    verbs
        .groupingBy { it }
        .eachCount()
        .toList()
        .sortedBy { it.first == ToolVerb.FAILED }

/** A failed call did not do its verb, so it counts as FAILED instead. */
internal fun ToolCallDisplay.summaryVerb(): ToolVerb =
    if (status == ToolCallStatus.FAILED) {
        ToolVerb.FAILED
    } else {
        when (kind) {
            ToolKind.EXECUTE -> ToolVerb.EXECUTE
            ToolKind.READ -> ToolVerb.READ
            ToolKind.EDIT -> ToolVerb.EDIT
            ToolKind.DELETE -> ToolVerb.DELETE
            ToolKind.MOVE -> ToolVerb.MOVE
            ToolKind.SEARCH -> ToolVerb.SEARCH
            ToolKind.FETCH -> ToolVerb.FETCH
            // A THINK call renders no reasoning, so counting it as a thought would describe
            // nothing visible.
            ToolKind.THINK, ToolKind.SWITCH_MODE, ToolKind.OTHER, null -> ToolVerb.OTHER
        }
    }

/** Joins the summary's phrases and capitalises the first, as a sentence. */
internal fun joinSummary(
    phrases: List<String>,
    separator: String,
): String = joinPhrases(phrases, separator).replaceFirstChar { it.titlecase() }
