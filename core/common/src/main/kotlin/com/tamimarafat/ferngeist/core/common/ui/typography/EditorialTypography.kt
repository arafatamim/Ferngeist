package com.tamimarafat.ferngeist.core.common.ui.typography

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tamimarafat.ferngeist.core.common.R
import com.tamimarafat.ferngeist.core.model.TurnStatus
import kotlin.math.roundToInt

/** An invitation to start writing, with more room than a card title. */
val newChatEditorialStyle =
    editorialStyle(
        size = 46.sp,
        lineHeight = 56.sp,
        weight = FontWeight.ExtraBold,
        width = 94f,
    )

/** The name of the project, agent, or conversation already promoted to a full-width card. */
val featuredContentEditorialStyle =
    editorialStyle(
        size = 30.sp,
        lineHeight = 38.sp,
        weight = FontWeight.SemiBold,
        width = 88f,
    )

/** An urgent hero title: the headline step in the brand face, blackened so the card that needs you leads. */
private val urgentHeroEditorialStyle =
    editorialStyle(
        size = 24.sp,
        lineHeight = 32.sp,
        weight = FontWeight(800),
        width = 90f,
    )

/** The promoted urgent hero keeps its larger step and takes the same weight. */
private val urgentFeaturedEditorialStyle =
    editorialStyle(
        size = 30.sp,
        lineHeight = 38.sp,
        weight = FontWeight(800),
        width = 88f,
    )

/** A hero card's title: the featured step when promoted, blackened when its turn is an error or a wait. */
@Composable
fun heroContentEditorialStyle(
    featured: Boolean,
    urgent: Boolean,
): TextStyle =
    when {
        urgent && featured -> urgentFeaturedEditorialStyle
        urgent -> urgentHeroEditorialStyle
        featured -> featuredContentEditorialStyle
        else -> MaterialTheme.typography.headlineSmallEmphasized
    }

private val urgentTitleWeight = FontWeight(800)
private val connectedTitleWeight = FontWeight(650)
private val plainTitleWeight = FontWeight(500)

/**
 * The weight a plain-face card title takes from its turn: blackest when it needs you or failed,
 * blacker while the session is live, otherwise the title role's own weight.
 */
fun cardTitleWeight(
    status: TurnStatus,
    live: Boolean,
): FontWeight =
    when {
        status == TurnStatus.ERROR || status == TurnStatus.NEEDS_ATTENTION -> urgentTitleWeight
        live -> connectedTitleWeight
        else -> plainTitleWeight
    }

/** A resting card's name: narrow, light, and a touch off the upright grid, so a small tile still leads. */
val restingContentEditorialStyle = restingTitleStyle(weight = FontWeight(400), width = 100f)

/** A connected card's name: a shade blacker and a step wider, so the live card leads its row. */
val connectedContentEditorialStyle = restingTitleStyle(weight = FontWeight(650), width = 110f)

private fun restingTitleStyle(
    weight: FontWeight,
    width: Float,
): TextStyle =
    editorialStyle(
        size = 18.sp,
        weight = weight,
        width = width,
        // Em leading, so the tighter ratio survives the title's auto-size shrink.
        lineHeight = 1.2.em,
    ).copy(
        letterSpacing = (-0.01).em,
        textGeometricTransform = TextGeometricTransform(skewX = RESTING_SLANT_SKEW),
    )

// Anybody's file carries wdth and wght only, so the one-degree slant is a text shear: tan(1°).
private const val RESTING_SLANT_SKEW = -0.0175f

/** The auto-size every resting card title shares: grows to the cap, wraps before it shrinks. */
val restingTitleAutoSize =
    TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 24.sp, stepSize = 0.5.sp)

private const val RESUME_WIDEST = 88
private const val RESUME_NARROWEST = 60
private const val RESUME_WIDTH_STEP = 4
private const val RESUME_WEIGHT = 600

/**
 * Condensing at a fixed weight darkens the line: ink density rises from 31% to 43% across the
 * range. Shedding this much weight by the narrowest step takes back half of that, which keeps
 * the colour close without letting a long title fade to a light weight.
 */
private const val RESUME_WEIGHT_SHED = 150

/**
 * The session to resume, widest first: one headline-sized line that condenses instead of wrapping,
 * so it stays below the featured card and never takes a second row. See [WidthFittedText].
 */
val resumeTitleEditorialStyles: List<TextStyle> =
    (RESUME_WIDEST downTo RESUME_NARROWEST step RESUME_WIDTH_STEP).map { width ->
        val condensed = (RESUME_WIDEST - width).toFloat() / (RESUME_WIDEST - RESUME_NARROWEST)
        editorialStyle(
            size = 24.sp,
            lineHeight = 32.sp,
            weight = FontWeight(RESUME_WEIGHT - (RESUME_WEIGHT_SHED * condensed).roundToInt()),
            width = width.toFloat(),
        )
    }

/** The brand face at a custom width, because Epilogue has no width axis. */
private fun editorialStyle(
    size: TextUnit,
    lineHeight: TextUnit,
    weight: FontWeight,
    width: Float,
): TextStyle =
    TextStyle(
        fontFamily =
            FontFamily(
                variableFont(R.font.anybody_wdth_wght, weight, width = width),
            ),
        fontWeight = weight,
        fontSize = size,
        lineHeight = lineHeight,
        lineBreak = LineBreak.Heading,
    )
