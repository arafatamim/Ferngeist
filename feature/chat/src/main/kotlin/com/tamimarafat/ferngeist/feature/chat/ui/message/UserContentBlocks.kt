package com.tamimarafat.ferngeist.feature.chat.ui.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ContentBlock
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.feature.chat.markdown.documentMarkdownTypography
import com.tamimarafat.ferngeist.feature.chat.selection.QuoteSelectionContainer
import com.tamimarafat.ferngeist.feature.chat.ui.content.ContentBlockRenderer

/** Prompt text keeps its bubble style; every attachment shares the assistant and tool renderer. */
@Composable
internal fun UserContentBlocks(
    blocks: List<ContentBlock>,
    textColor: Color,
    maxLines: Int,
    onImageClick: ((ChatImageData) -> Unit)?,
) {
    val display = remember(blocks) { blocks.cappedUserContent() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        display.attachmentRuns().forEach { run ->
            val text = run.singleOrNull() as? ContentBlock.Text
            if (text != null) {
                PromptText(text, textColor, maxLines)
            } else {
                // Adjacent attachments share one row, as the transcript's artifact cards do: two
                // small cards belong beside each other, not stacked down the bubble.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    run.forEach { ContentBlockRenderer(it, onImageClick) }
                }
            }
        }
    }
}

@Composable
private fun PromptText(
    block: ContentBlock.Text,
    textColor: Color,
    maxLines: Int,
) {
    if (block.text.isBlank()) return
    QuoteSelectionContainer {
        Text(
            text = block.text,
            style = documentMarkdownTypography().bodyLarge,
            color = textColor,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** [this] split into prose and the runs of adjacent attachments between it, in block order. */
private fun List<ContentBlock>.attachmentRuns(): List<List<ContentBlock>> {
    val runs = mutableListOf<MutableList<ContentBlock>>()
    for (block in this) {
        val last = runs.lastOrNull()
        if (block is ContentBlock.Text || last == null || last.first() is ContentBlock.Text) {
            runs += mutableListOf(block)
        } else {
            last += block
        }
    }
    return runs
}

/** One display budget for all prose, retaining each attachment at its original position. */
internal fun List<ContentBlock>.cappedUserContent(): List<ContentBlock> {
    var remaining = MAX_DISPLAY_CHARS
    var truncated = false
    val capped =
        mapNotNull { block ->
            if (block !is ContentBlock.Text) return@mapNotNull block
            if (truncated) return@mapNotNull null
            val text = block.text.take(remaining)
            remaining -= text.length
            truncated = text.length < block.text.length
            ContentBlock.Text(text + if (truncated) "…" else "").takeIf { it.text.isNotEmpty() }
        }
    return capped.coalescedText()
}

/** Joins adjacent chunks after capping, so joining a huge replay never builds its full string. */
private fun List<ContentBlock>.coalescedText(): List<ContentBlock> =
    buildList {
        val text = StringBuilder()

        fun flushText() {
            if (text.isNotEmpty()) {
                add(ContentBlock.Text(text.toString()))
                text.clear()
            }
        }
        this@coalescedText.forEach { block ->
            if (block is ContentBlock.Text) {
                text.append(block.text)
            } else {
                flushText()
                add(block)
            }
        }
        flushText()
    }
