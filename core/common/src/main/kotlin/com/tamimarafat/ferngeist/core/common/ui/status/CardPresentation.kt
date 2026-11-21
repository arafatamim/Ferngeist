package com.tamimarafat.ferngeist.core.common.ui.status

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.tamimarafat.ferngeist.core.common.ui.AgentLogo
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.aggregateTurnActivity
import com.tamimarafat.ferngeist.core.model.iconUrl

/** The layout and status shared by agent, project and session cards. */
enum class CardVariant { DIRECT, HERO, TILE }

data class CardPresentation(
    val activity: TurnActivity,
    val working: Int,
    val unreadFinished: Int,
    val variant: CardVariant,
    val tone: CardTone,
)

/** Working, unread, attention and error chats promote a card; direct agents remain rows. */
fun cardPresentation(
    chats: Collection<TurnActivity>,
    live: Boolean,
    failed: Boolean = false,
    direct: Boolean = false,
): CardPresentation {
    val working = chats.count { it.status == TurnStatus.WORKING }
    val unread = chats.count { it.status == TurnStatus.FINISHED }
    val activity = chats.aggregateTurnActivity()
    val variant =
        when {
            direct -> CardVariant.DIRECT
            working > 0 ||
                unread > 0 ||
                activity.status == TurnStatus.NEEDS_ATTENTION ||
                activity.status == TurnStatus.ERROR -> CardVariant.HERO
            else -> CardVariant.TILE
        }
    return CardPresentation(
        activity = activity,
        working = working,
        unreadFinished = unread,
        variant = variant,
        tone = cardTone(activity.status, variant == CardVariant.HERO, live, failed),
    )
}

/** The tones every home card can take; a card's tone and its on-colour always pair. */
enum class CardTone { PRIMARY, ATTENTION, CONNECTED, FAILED, DEFAULT }

/**
 * The tone a card takes. What its turn asks for comes first: a wait or a failure is the thing to
 * act on, so it tints the card whether or not the link that would carry the answer is up. Only
 * then does the connection speak, and a live hero outranks a plain live tile.
 */
fun cardTone(
    status: TurnStatus,
    hero: Boolean,
    live: Boolean,
    failed: Boolean,
): CardTone =
    when {
        status == TurnStatus.ERROR -> CardTone.FAILED
        status == TurnStatus.NEEDS_ATTENTION -> CardTone.ATTENTION
        hero && live -> CardTone.PRIMARY
        live -> CardTone.CONNECTED
        failed -> CardTone.FAILED
        else -> CardTone.DEFAULT
    }

// Opaque roles pair each card tone with its on-colour; primary stays with the hero.
val CardTone.containerColor: Color
    @Composable
    get() =
        when (this) {
            CardTone.PRIMARY -> MaterialTheme.colorScheme.primaryContainer
            CardTone.ATTENTION -> MaterialTheme.colorScheme.tertiaryContainer
            CardTone.CONNECTED -> MaterialTheme.colorScheme.secondaryContainer
            CardTone.FAILED -> MaterialTheme.colorScheme.errorContainer
            CardTone.DEFAULT -> MaterialTheme.colorScheme.surfaceContainer
        }

/**
 * A card's ink for the turn's wave line: the card's own content colour, softened, because the wave is
 * an indicator beside the label rather than a second line of text. Full ink measured around 12:1.
 */
val waveLineColor: Color
    @Composable
    get() = LocalContentColor.current.copy(alpha = WAVE_LINE_ALPHA)

private const val WAVE_LINE_ALPHA = 0.45f

val CardTone.contentColor: Color
    @Composable
    get() =
        when (this) {
            CardTone.PRIMARY -> MaterialTheme.colorScheme.onPrimaryContainer
            CardTone.ATTENTION -> MaterialTheme.colorScheme.onTertiaryContainer
            CardTone.CONNECTED -> MaterialTheme.colorScheme.onSecondaryContainer
            CardTone.FAILED -> MaterialTheme.colorScheme.onErrorContainer
            CardTone.DEFAULT -> MaterialTheme.colorScheme.onSurface
        }

/** A step off the card's own tone: dark enough to see, light enough to stay in the background. */
private const val INACTIVE_VEIL_ALPHA = 0.12f

/**
 * An agent's identity mark, in one of two states.
 *
 * The shape says three things at once. A connected or active agent is a filled primary square
 * with its logo in the on-colour; an agent that is neither is a circle in the card's own
 * content colour; and a turn that is doing something borrows its own shape — a turning cookie
 * while it works, its four-sided cousin while it waits on the user or has failed.
 *
 * The dimmed fill is a veil of `LocalContentColor` rather than a fixed surface role, because
 * the rows that carry these marks sit on container colours: the light palette's
 * `surfaceContainerHighest` is the same colour as its `primaryContainer`, so a chip drawn in
 * that role vanished on exactly the cards it was meant to sit on. A veil of the card's own
 * content colour is a shade off whatever tone the card happens to be — darker on a light
 * card, lighter on a dark one — so it always separates.
 *
 * The mark carries identity and connection, never turn detail: it says whether this agent is
 * live, not what its turn is doing. The surface around it says the rest.
 *
 * @param active the agent is connected, or its work is in flight.
 * @param status the agent's turn, which decides the shape while it is working, waiting or
 *   failed. [TurnStatus.UNKNOWN] leaves the connection's own shape in place.
 * @param size the mark's edge; the logo fills half of it unless [glyphSize] says otherwise. The
 *   pinched cookie draws past it, around a logo of the same size, so the growth is air.
 * @param glyphSize the logo's edge, for slots too small for the default half of [size] to read.
 * @param border an optional outline, used by the Projects hero so overlapping marks keep
 *   their edges. It rings the shape from outside, so the fill and the logo's air match a mark
 *   without one.
 * @param reserveOverhang lays the mark out at the extent it draws rather than at [size]. The
 *   pinched cookie draws past [size]; a lone mark lets that overhang spill into its padding so
 *   a turn changing state never moves its row, while a layout that packs marks edge to edge
 *   needs the real extent to space them.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgentMark(
    iconUrl: String?,
    size: Dp,
    active: Boolean,
    status: TurnStatus = TurnStatus.UNKNOWN,
    modifier: Modifier = Modifier,
    fallback: ImageVector = Icons.Default.SmartToy,
    glyphSize: Dp = size / 2,
    contentDescription: String? = null,
    border: BorderStroke? = null,
    reserveOverhang: Boolean = false,
    fallbackName: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val content = LocalContentColor.current
    val fill = if (active) colors.primary else content.copy(alpha = INACTIVE_VEIL_ALPHA)
    val outline = rememberMorphedOutline(markOutline(active, status))
    val drawn = size * outline.growth
    // Only the busy shape turns; the logo stays upright inside it so it stays readable.
    val rotation = rememberActivityRotation(periodMillis = WORKING_PERIOD_MILLIS, active = status == TurnStatus.WORKING)
    Box(
        modifier = modifier.size(if (reserveOverhang) drawn else size),
        contentAlignment = Alignment.Center,
    ) {
        // Required rather than matched, so a grown shape keeps its true size centred past the
        // box, and its ring keeps its true width instead of scaling with it.
        // The ring is a doubled stroke under the fill: the fill hides its inner half, leaving an
        // even band outside the outline, where an inset border would eat into the shape. That
        // only works while the fill is opaque — a veiled fill would let the inner band show.
        Box(
            Modifier
                .requiredSize(drawn)
                .graphicsLayer { rotationZ = rotation.value }
                .drawBehind {
                    val shape = outline.shape.createOutline(this.size, layoutDirection, this)
                    border?.let { drawOutline(shape, it.brush, style = Stroke(it.width.toPx() * 2)) }
                    drawOutline(shape, fill)
                },
        )
        AgentLogo(
            iconUrl = iconUrl,
            fallback = fallback,
            size = glyphSize,
            tint = if (active) colors.onPrimary else content,
            contentDescription = contentDescription,
            fallbackName = fallbackName,
        )
    }
}

/** The same mark for a launchable target, whose logo comes from its binding. */
@Composable
fun AgentMark(
    target: LaunchableTarget,
    size: Dp,
    active: Boolean,
    status: TurnStatus = TurnStatus.UNKNOWN,
    modifier: Modifier = Modifier,
    fallback: ImageVector = Icons.Default.SmartToy,
    glyphSize: Dp = size / 2,
    contentDescription: String? = null,
    border: BorderStroke? = null,
    reserveOverhang: Boolean = false,
) = AgentMark(
    iconUrl = target.iconUrl,
    size = size,
    active = active,
    status = status,
    modifier = modifier,
    fallback = fallback,
    glyphSize = glyphSize,
    contentDescription = contentDescription,
    border = border,
    reserveOverhang = reserveOverhang,
    fallbackName = if (target is LaunchableTarget.GatewayAgent) target.name else null,
)

/** How long the busy shape takes to turn once. Slow enough to read as alive, not as a spinner. */
private const val WORKING_PERIOD_MILLIS = 12_000

/** A mark's polygon, and how far past the mark's [AgentMark] `size` it draws. */
private data class MarkOutline(
    val polygon: RoundedPolygon,
    val growth: Float = 1f,
)

/**
 * The mark's outline: work owns a turning cookie, a waiting or failed turn owns the four-sided
 * one, and everything else falls back to the connection shapes.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun markOutline(
    active: Boolean,
    status: TurnStatus,
): MarkOutline =
    when (status) {
        TurnStatus.WORKING -> MarkOutline(MaterialShapes.Cookie9Sided)
        TurnStatus.NEEDS_ATTENTION, TurnStatus.ERROR -> MarkOutline(MaterialShapes.Cookie4Sided, PINCHED_GROWTH)
        else -> MarkOutline(if (active) MaterialShapes.Square else MaterialShapes.Circle)
    }

/**
 * The four-sided cookie pinches its edge midpoints in to 0.88 of a square's half-edge, so at the
 * same size its logo sat cramped. Grown by this, the pinch lands at 1.05 of the square's edge:
 * the logo gets more air in the cookie than in the square, not merely as much.
 */
private const val PINCHED_GROWTH = 1.2f

/** A morph frame: the outline to draw and the growth it has reached. */
private class MorphedOutline(
    val shape: Shape,
    val growth: Float,
)

/**
 * Each new target morphs from the last settled one on a spatial spring, its growth riding the
 * same progress so the size and the shape arrive together.
 * shortcut: a change mid-morph restarts from the last settled shape, not the half-way one;
 * upgrade if status flips faster than the spring settles.
 */
@Composable
private fun rememberMorphedOutline(target: MarkOutline): MorphedOutline {
    var settled by remember { mutableStateOf(target) }
    val morph = remember(target) { Morph(settled.polygon, target.polygon) }
    val progress = remember(target) { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(target) {
        progress.animateTo(1f, spec)
        settled = target
    }
    return MorphedOutline(
        shape = MorphShape(morph, progress.value),
        growth = lerp(settled.growth, target.growth, progress.value),
    )
}

private class MorphShape(
    private val morph: Morph,
    private val progress: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = morph.toPath(progress).asComposePath()
        // Fitted to its own bounds, so no polygon's native coordinate range matters.
        val bounds = path.getBounds()
        path.translate(Offset(-bounds.left, -bounds.top))
        path.transform(Matrix().apply { scale(size.width / bounds.width, size.height / bounds.height) })
        return Outline.Generic(path)
    }
}
