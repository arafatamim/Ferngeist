package com.tamimarafat.ferngeist.feature.serverlist.ui.cards

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Filtering reflows the list: fade what leaves, glide what stays. A [Modifier] extension, because
 * `animateItem` needs the item's scope and this must stay chainable.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Modifier.homeItemMotion(scope: LazyItemScope): Modifier {
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    return with(scope) {
        this@homeItemMotion.animateItem(
            fadeInSpec = effects,
            placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            fadeOutSpec = effects,
        )
    }
}
