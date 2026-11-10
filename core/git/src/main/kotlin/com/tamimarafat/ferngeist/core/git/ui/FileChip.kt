package com.tamimarafat.ferngeist.core.git.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A file's type icon beside its name: the one look for a file wherever a tool call or a turn's diff names one. */
@Composable
fun FileChip(
    path: String,
    modifier: Modifier = Modifier,
    label: String = path.fileName(),
    // Off where the chip already sits on a card: a pill inside a card reads as clutter.
    filled: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodySmall,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.MiddleEllipsis,
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(6.dp))
                .then(if (filled) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier)
                .padding(horizontal = if (filled) 6.dp else 0.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTypeIcon(path, modifier = Modifier.size(14.dp))
        Text(
            text = label,
            style = textStyle,
            maxLines = maxLines,
            // The extension is the part worth keeping where a single line has to ellipsize.
            overflow = overflow,
            softWrap = maxLines > 1,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
fun FileTypeIcon(
    path: String,
    modifier: Modifier = Modifier,
) = Icon(
    painter = painterResource(fileIconFor(path)),
    contentDescription = null,
    tint = Color.Unspecified,
    modifier = modifier,
)

fun String.fileName() = replace('\\', '/').trimEnd('/').substringAfterLast('/')
