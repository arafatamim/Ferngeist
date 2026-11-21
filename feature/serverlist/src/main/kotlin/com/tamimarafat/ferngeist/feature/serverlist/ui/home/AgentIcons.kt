package com.tamimarafat.ferngeist.feature.serverlist.ui.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/*
 * Robot head on Material's 24dp icon grid: 2dp stroke, 2dp padding, square keyline (5..19),
 * 4dp outer corner radius. Eyes are 3dp dots so they survive at 18dp.
 */
private const val HEAD = "M9 5H15A4 4 0 0 1 19 9V15A4 4 0 0 1 15 19H9A4 4 0 0 1 5 15V9A4 4 0 0 1 9 5Z"
private const val HEAD_INNER = "M9 7H15A2 2 0 0 1 17 9V15A2 2 0 0 1 15 17H9A2 2 0 0 1 7 15V9A2 2 0 0 1 9 7Z"
private const val EYES = "M8 12a1.5 1.5 0 1 1 3 0a1.5 1.5 0 1 1 -3 0Z M13 12a1.5 1.5 0 1 1 3 0a1.5 1.5 0 1 1 -3 0Z"

// The ears sit 1dp clear of the head.
private const val EARS =
    "M3 9A1 1 0 0 1 4 10V14A1 1 0 0 1 2 14V10A1 1 0 0 1 3 9Z " +
        "M21 9A1 1 0 0 1 22 10V14A1 1 0 0 1 20 14V10A1 1 0 0 1 21 9Z"

/** Filled: solid head with the eyes cut out, for the selected state. */
val agentFilledIcon: ImageVector by lazy { robotIcon("AgentFilled", HEAD + EYES) }

/** Outlined: 2dp head outline with solid eyes, for the unselected state. */
val agentOutlinedIcon: ImageVector by lazy { robotIcon("AgentOutlined", HEAD + HEAD_INNER + EYES) }

private fun robotIcon(
    name: String,
    head: String,
): ImageVector =
    ImageVector
        .Builder(name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        // Even-odd turns each nested shape into a hole, then a fill again: one path draws both styles.
        .addPath(
            addPathNodes(head + EARS),
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd,
        ).build()
