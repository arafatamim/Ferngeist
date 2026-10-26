package com.tamimarafat.ferngeist.core.common.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.R

/** A quiet, shared signal that an agent has a turn in progress. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.tertiary,
) {
    val description = stringResource(R.string.common_in_progress)
    val rotation = rememberActivityRotation(periodMillis = 12_000)
    Box(
        modifier
            .size(20.dp)
            .graphicsLayer { rotationZ = rotation.value }
            .background(color, MaterialShapes.Sunny.toShape())
            .semantics { contentDescription = description },
    )
}
