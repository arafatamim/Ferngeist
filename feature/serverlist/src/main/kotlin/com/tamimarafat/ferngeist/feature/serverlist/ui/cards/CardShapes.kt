package com.tamimarafat.ferngeist.feature.serverlist.ui.cards

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Every home card's corner, sized against the card: the design rounds a sixth of a card's shorter
 * side (32 on a 196 hero, 28 on a 168 tile); at the 128-136 minimum heights that rounds to 24.
 */
internal val cardCornerRadius = 24.dp

/**
 * How far the leading card sits from the sheet's top and side edges at rest: the pager's top inset
 * and the list's horizontal inset. Both must stay equal to this for the corners to stay concentric.
 */
private val cardGutter = 16.dp

/**
 * The sheet's rounded top corners. Concentric nesting: the outer corner is the inner one plus the gap,
 * so the leading card's corner shares its centre. The card's roundness is fixed by its own size, so
 * the sheet is the one that grows to fit it.
 */
internal val sheetCornerRadius = cardCornerRadius + cardGutter

/**
 * The facing corners of a pair. Siblings across a gap do not nest, so nothing derives this: it is the
 * design's fixed pinch, a third of [cardCornerRadius] so it reads as a seam rather than a rounding error.
 */
private val tilePinchRadius = 8.dp

/** The overflow row's inset around its leading icon chip: one value on all three edges, so the chip nests. */
internal val overflowIconInset = 10.dp

/** The overflow row's icon chip: concentric with the row's corner, so the card's radius less the inset. */
internal val overflowIconShape = RoundedCornerShape(cardCornerRadius - overflowIconInset)

internal fun sheetShape(): RoundedCornerShape =
    RoundedCornerShape(topStart = sheetCornerRadius, topEnd = sheetCornerRadius)

/**
 * Shape for tile [index] of a two-up row of [count]: the inner bottom corners pinch toward each
 * other, marking the pair as one row split in two.
 */
internal fun tileShape(
    index: Int,
    count: Int,
): RoundedCornerShape =
    when {
        count == 1 -> RoundedCornerShape(cardCornerRadius)
        index == 0 -> RoundedCornerShape(cardCornerRadius, cardCornerRadius, tilePinchRadius, cardCornerRadius)
        else -> RoundedCornerShape(cardCornerRadius, cardCornerRadius, cardCornerRadius, tilePinchRadius)
    }
