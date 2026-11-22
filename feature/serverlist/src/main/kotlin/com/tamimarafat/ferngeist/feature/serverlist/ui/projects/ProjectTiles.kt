package com.tamimarafat.ferngeist.feature.serverlist.ui.projects

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectSummary
import com.tamimarafat.ferngeist.core.common.ui.ProjectSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.ProjectTitleSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.chat.StatusWaveLine
import com.tamimarafat.ferngeist.core.common.ui.chat.rememberStatusLineState
import com.tamimarafat.ferngeist.core.common.ui.status.AgentMark
import com.tamimarafat.ferngeist.core.common.ui.status.CardPresentation
import com.tamimarafat.ferngeist.core.common.ui.status.CardTone
import com.tamimarafat.ferngeist.core.common.ui.status.containerColor
import com.tamimarafat.ferngeist.core.common.ui.status.contentColor
import com.tamimarafat.ferngeist.core.common.ui.status.turnStatusLabel
import com.tamimarafat.ferngeist.core.common.ui.status.waveLineColor
import com.tamimarafat.ferngeist.core.common.ui.time.compactRelativeTime
import com.tamimarafat.ferngeist.core.common.ui.time.rememberPresentationTime
import com.tamimarafat.ferngeist.core.common.ui.typography.connectedContentEditorialStyle
import com.tamimarafat.ferngeist.core.common.ui.typography.heroContentEditorialStyle
import com.tamimarafat.ferngeist.core.common.ui.typography.restingContentEditorialStyle
import com.tamimarafat.ferngeist.core.common.ui.typography.restingTitleAutoSize
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.projectName
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.HeroBody
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.PressSurface
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.StatusLabelLine
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.cardCornerRadius
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.headline
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.overflowIconInset
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.overflowIconShape
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.sharedCardBounds

/** Supporting lines sit a step below their headline, whatever tone the card carries. */
private const val SUPPORTING_ALPHA = 0.75f

/** A promoted project: a full-width tile with its most important activity. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
internal fun ProjectHeroTile(
    project: ProjectSummary,
    presentation: CardPresentation,
    showGateway: Boolean,
    connectedAgents: List<ConnectedAgent>,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    featured: Boolean,
    modifier: Modifier = Modifier,
) {
    PressSurface(
        shape = RoundedCornerShape(cardCornerRadius),
        color = presentation.tone.containerColor,
        contentColor = presentation.tone.contentColor,
        onClick = onClick,
        sharedBoundsKey = ProjectSharedBoundsKey(project.gateway.id, project.repo),
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
        modifier = modifier,
    ) {
        HeroBody(
            statusRow = {
                // An unread finished turn is the promotion itself, so it needs no label of its own.
                val headline = presentation.activity.status.headline
                StatusLabelLine(
                    label =
                        when {
                            headline == TurnStatus.WORKING ->
                                pluralStringResource(
                                    R.plurals.home_projects_chats_working,
                                    presentation.working,
                                    presentation.working,
                                )
                            headline != null -> turnStatusLabel(headline)
                            else -> null
                        },
                    status = presentation.activity.status,
                )
            },
            nameRow = {
                LookaheadScope {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            ProjectName(
                                project = project,
                                style =
                                    heroContentEditorialStyle(
                                        featured = featured,
                                        urgent =
                                            presentation.activity.status == TurnStatus.ERROR ||
                                                presentation.activity.status == TurnStatus.NEEDS_ATTENTION,
                                    ),
                                sharedTransitionScope = sharedTransitionScope,
                                animatedContentScope = animatedContentScope,
                                maxLines = 2,
                            )
                            Text(
                                text = projectSupporting(project, showGateway),
                                style = MaterialTheme.typography.bodyMedium,
                                color = LocalContentColor.current.copy(alpha = SUPPORTING_ALPHA),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        ProjectAgentMarks(
                            agents = connectedAgents,
                            tone = presentation.tone,
                            lookaheadScope = this@LookaheadScope,
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 136.dp).padding(20.dp),
        )
    }
}

/** A resting project in the two-up grid: its recency stamp on top, name at the foot. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
internal fun ProjectTile(
    project: ProjectSummary,
    presentation: CardPresentation,
    showGateway: Boolean,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    modifier: Modifier = Modifier,
) {
    PressSurface(
        shape = shape,
        color = presentation.tone.containerColor,
        contentColor = presentation.tone.contentColor,
        onClick = onClick,
        sharedBoundsKey = ProjectSharedBoundsKey(project.gateway.id, project.repo),
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 128.dp).padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    StatusWaveLine(
                        rememberStatusLineState(presentation.activity.status),
                        color = waveLineColor,
                    )
                }
            }
            ProjectName(
                project = project,
                style =
                    if (presentation.tone == CardTone.CONNECTED) {
                        connectedContentEditorialStyle
                    } else {
                        restingContentEditorialStyle
                    },
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
                maxLines = 2,
                autoSize = restingTitleAutoSize,
            )
            Text(
                text = projectSupporting(project, showGateway),
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = SUPPORTING_ALPHA),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The overflow behind the collapsed view: the names it hides, then the reveal it toggles. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MoreProjectsRow(
    hidden: List<ProjectSummary>,
    expanded: Boolean,
    onToggle: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "moreProjects",
    )
    PressSurface(
        shape = RoundedCornerShape(cardCornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        onClick = onToggle,
        sharedBoundsKey = null,
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
        modifier = modifier,
    ) {
        Row(
            modifier =
                Modifier
                    .heightIn(min = 64.dp)
                    .padding(
                        start = overflowIconInset,
                        end = 16.dp,
                        top = overflowIconInset,
                        bottom = overflowIconInset,
                    ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, overflowIconShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text =
                        if (expanded) {
                            stringResource(R.string.serverlist_show_less)
                        } else {
                            pluralStringResource(R.plurals.home_projects_more_count, hidden.size, hidden.size)
                        },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!expanded) {
                    Text(
                        text = hidden.joinToString(", ") { projectName(it.repo) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(rotation),
            )
        }
    }
}

/** Project name that the project screen's title grows out of. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ProjectName(
    project: ProjectSummary,
    style: TextStyle,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    maxLines: Int = 1,
    autoSize: TextAutoSize? = null,
) {
    Text(
        text = projectName(project.repo),
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        autoSize = autoSize,
        modifier =
            Modifier.sharedCardBounds(
                ProjectTitleSharedBoundsKey(project.gateway.id, project.repo),
                sharedTransitionScope,
                animatedContentScope,
            ),
    )
}

/**
 * The project's agents as overlapped ink squares, their logos punched out in the card's
 * container: the marks take the card's own ink, so a needs-attention hero keeps the title's
 * contrast instead of carrying a second accent that can match the card.
 *
 * Placement is a [Layout] rather than a [Row] of offset marks: an offset moves pixels without
 * claiming their space, so a `Row` reports the sum of the full marks while drawing an overlapped
 * stack, and the difference trails off the end of the card. Here the row's width *is* the drawn
 * stack's width — each mark reserves the extent its fill draws, and the next starts
 * [markOverlap] short of its end — so it sits flush at the card's edge and every pair overlaps by
 * the same amount, whatever the agent count and however large a pinched mark has grown. The ring
 * lies outside that extent, but in the card's own colour it only shows where it crosses a
 * neighbour.
 *
 * A grown mark sits above its neighbours: under one, a straight edge would slice through its
 * lobes, while on top its own outline and ring cut them. The row keeps the plain mark's height,
 * so a mark growing never moves the card's rows; its overhang lands in the card's padding.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProjectAgentMarks(
    agents: List<ConnectedAgent>,
    tone: CardTone,
    lookaheadScope: LookaheadScope,
) {
    val cardColor = tone.containerColor
    val spring = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    Layout(
        content = {
            agents.forEach { agent ->
                key(agent.target.id) {
                    AgentMark(
                        target = agent.target,
                        size = markSize,
                        active = true,
                        status = agent.status,
                        tone = tone,
                        border = BorderStroke(markRingWidth, cardColor),
                        reserveOverhang = true,
                        modifier =
                            Modifier.animateBounds(
                                lookaheadScope,
                                boundsTransform = BoundsTransform { _, _ -> spring },
                            ),
                    )
                }
            }
        },
    ) { measurables, _ ->
        val marks = measurables.map { it.measure(Constraints()) }
        val last = marks.lastOrNull() ?: return@Layout layout(0, 0) {}
        val plain = markSize.roundToPx()
        val starts = stackStarts(marks.map { it.width }, markOverlap.roundToPx())
        layout(starts.last() + last.width, plain) {
            marks.forEachIndexed { index, mark ->
                val zIndex = if (mark.width > plain) 1f else 0f
                mark.placeRelative(starts[index], (plain - mark.height) / 2, zIndex)
            }
        }
    }
}

/** Where each mark of [widths] starts when every pair overlaps by [overlap]. */
internal fun stackStarts(
    widths: List<Int>,
    overlap: Int,
): List<Int> = widths.runningFold(0) { start, width -> start + (width - overlap).coerceAtLeast(0) }.dropLast(1)

// The agents hero badge's footprint, so both hero cards carry marks of one size.
private val markSize = 44.dp

// The prototype's stack ratio (8 on a 32) held at the larger mark.
private val markOverlap = 12.dp
private val markRingWidth = 2.dp

@Composable
private fun projectSupporting(
    project: ProjectSummary,
    showGateway: Boolean,
): String =
    listOfNotNull(
        project.gateway.name.takeIf { showGateway },
        project.updatedAt?.let { compactRelativeTime(it, rememberPresentationTime()) },
    ).joinToString(" · ")
