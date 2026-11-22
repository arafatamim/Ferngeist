package com.tamimarafat.ferngeist.core.common.ui.typography

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.tamimarafat.ferngeist.core.common.R
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

private const val RESUME_WIDEST = 88
private const val RESUME_NARROWEST = 56
private const val RESUME_WIDTH_STEP = 8
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
