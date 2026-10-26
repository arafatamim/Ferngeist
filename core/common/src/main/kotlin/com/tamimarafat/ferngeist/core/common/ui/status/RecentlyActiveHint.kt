package com.tamimarafat.ferngeist.core.common.ui.status

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tamimarafat.ferngeist.core.common.R

/** Text, rather than the running dot, keeps this weaker activity signal distinct. */
@Composable
fun RecentlyActiveHint(
    updatedAt: Long?,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val owner = LocalLifecycleOwner.current
    var recent by remember(updatedAt, owner) { mutableStateOf(false) }
    LaunchedEffect(updatedAt, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                recentActivity(updatedAt, System::currentTimeMillis).collect { recent = it }
            } finally {
                // An expired hint must not survive a stopped screen's cancelled timer.
                recent = false
            }
        }
    }
    if (recent) {
        Text(
            text = stringResource(R.string.common_recently_active),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}
