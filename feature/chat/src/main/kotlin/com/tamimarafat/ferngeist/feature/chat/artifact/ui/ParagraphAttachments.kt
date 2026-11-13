package com.tamimarafat.ferngeist.feature.chat.artifact.ui

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.adamglin.compose.markdown.core.model.InlineNode
import com.tamimarafat.ferngeist.core.model.ChatResourceLink
import com.tamimarafat.ferngeist.core.model.artifact.ChatArtifactStat
import com.tamimarafat.ferngeist.feature.chat.artifact.reference.ArtifactPresentation
import com.tamimarafat.ferngeist.feature.chat.artifact.reference.label
import com.tamimarafat.ferngeist.feature.chat.artifact.reference.paragraphArtifacts
import com.tamimarafat.ferngeist.feature.chat.artifact.reference.presentation
import com.tamimarafat.ferngeist.feature.chat.ui.localWorkspaceFiles
import com.tamimarafat.ferngeist.feature.chat.ui.localWorkspaceRoot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// A design call: big enough for an icon and two lines of name, small enough for a row of three.
private val cardSize = 128.dp

/** One card: the host [path], its [label], and the size an agent reported until the host's stat answers. */
private data class CardSpec(
    val path: String,
    val label: String,
    val reportedSize: Long? = null,
)

/**
 * The files [inlines] reference, as square cards below their paragraph once it has [ended], so a
 * half-written path never gets a card; nothing where the chat has no [ArtifactHost].
 */
@Composable
internal fun ParagraphAttachments(
    inlines: List<InlineNode>,
    ended: Boolean,
) {
    val host = localArtifactHost.current ?: return
    val cards = rememberParagraphCards(inlines)
    val shown = ended && cards.isNotEmpty()
    // Cards already there when the paragraph composes (history, scrolling back) never animate.
    val startsShown = remember { shown }
    // Not composed until shown: the column's spacing would otherwise open a gap early.
    if (shown) CardRow(cards, host, animate = !startsShown)
}

/**
 * The host paths a message links explicitly have their own cards; paragraph mentions keep theirs.
 * [animate] fades each card in as it arrives.
 */
@Composable
internal fun ResourceLinkCards(
    links: List<ChatResourceLink>,
    animate: Boolean,
) {
    val host = localArtifactHost.current ?: return
    val cards = remember(links) { links.map { CardSpec(it.path, it.name, it.size) } }
    CardRow(cards, host, animate)
}

/**
 * The CARD files [inlines] reference, one card per candidate: an ambiguous name shows each, labelled
 * with its path, rather than picking one host file. References are owned by their paragraph.
 * Workspace candidates stay relative to the cwd; explicit resource links name absolute host paths.
 */
@Composable
private fun rememberParagraphCards(inlines: List<InlineNode>): List<CardSpec> {
    val cwd = localWorkspaceRoot.current
    val workspace = localWorkspaceFiles.current
    val turn = localTurnPaths.current
    return remember(inlines, cwd, workspace, turn) {
        paragraphArtifacts(inlines, cwd, turn, workspace)
            .filter { it.presentation == ArtifactPresentation.CARD }
            .flatMap { artifact ->
                val ambiguous = artifact.candidates.size > 1
                artifact.candidates.map { CardSpec(it, if (ambiguous) it else artifact.label) }
            }
    }
}

/** [cards] as a wrapping row in order, described by one host stat call. */
@Composable
private fun CardRow(
    cards: List<CardSpec>,
    host: ArtifactHost,
    animate: Boolean,
) {
    val unique = remember(cards) { cards.distinctBy { it.path } }
    val paths = remember(unique) { unique.map { it.path } }
    val stats by produceState(emptyMap<String, ChatArtifactStat>(), paths, host) {
        // Size and early unavailability are hints: a host that cannot answer leaves plain cards.
        value =
            runCatching { paths.zip(host.stat(paths)).toMap() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(emptyMap())
    }
    // Wraps at the width it is given, never claims it: a user bubble stays as narrow as its content.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        unique.forEach { card ->
            key(card.path) {
                // Fade only: a live bubble's height spring already carries the size change.
                val entrance = remember { MutableTransitionState(!animate).apply { targetState = true } }
                AnimatedVisibility(entrance, enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec())) {
                    Card(card.path, card.label, host, stats[card.path], card.reportedSize)
                }
            }
        }
    }
}

@Composable
private fun Card(
    path: String,
    label: String,
    host: ArtifactHost,
    stat: ChatArtifactStat?,
    reportedSize: Long?,
) {
    var loading by remember(path) { mutableStateOf(false) }
    var failed by remember(path) { mutableStateOf(false) }
    // A file the host already reports unreadable shows so before a tap; a later successful open clears it.
    LaunchedEffect(stat) { if (stat?.error != null) failed = true }
    val context = LocalContext.current
    // Leaving composition cancels a fetch in flight, and the cache drops its partial bytes.
    val scope = rememberCoroutineScope()
    AttachmentCard(
        path = path,
        label = label,
        size = (stat?.size ?: reportedSize)?.let { Formatter.formatShortFileSize(context, it) },
        loading = loading,
        failed = failed,
        onOpen = {
            if (!loading) {
                scope.launch {
                    loading = true
                    failed = !succeeds { host.open(path) }
                    loading = false
                }
            }
        },
        onDownload = { host.download(path, label.substringAfterLast('/').substringAfterLast('\\')) },
        modifier = Modifier.widthIn(max = cardSize).fillMaxWidth().aspectRatio(1f),
    )
}
