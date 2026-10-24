package com.tamimarafat.ferngeist.feature.chat.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.filter
import java.security.MessageDigest
import java.util.Locale

/**
 * Initials on a color picked from the email, so one author keeps one color everywhere. The
 * author's picture covers them once it loads; a miss or no network leaves the initials.
 */
@Composable
internal fun AuthorAvatar(
    name: String,
    email: String,
    size: Dp = 24.dp,
) {
    val colors = MaterialTheme.colorScheme
    val swatches =
        listOf(
            colors.primaryContainer to colors.onPrimaryContainer,
            colors.secondaryContainer to colors.onSecondaryContainer,
            colors.tertiaryContainer to colors.onTertiaryContainer,
        )
    val (container, content) = swatches[Math.floorMod(email.lowercase().hashCode(), swatches.size)]
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clearAndSetSemantics {},
    ) {
        Surface(shape = CircleShape, color = container, contentColor = content, modifier = Modifier.size(size)) {}
        Text(
            text = initialsOf(name, email),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
        )
        val pixels = with(LocalDensity.current) { size.roundToPx() }
        avatarUrl(email, pixels)?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        }
    }
}

internal val gitHubNoReply = Regex("^(\\d+)\\+[^@]+@users\\.noreply\\.github\\.com$")

/**
 * GitHub's own avatar for its noreply addresses, Gravatar for the rest. Gravatar's `d=404` makes
 * an address without a picture fail, so the initials stay rather than a generated pattern.
 */
internal fun avatarUrl(
    email: String,
    pixels: Int,
): String? {
    val normalized = email.trim().lowercase()
    if (normalized.isEmpty()) return null
    val gitHubId = gitHubNoReply.find(normalized)?.groupValues?.get(1)
    if (gitHubId != null) return "https://avatars.githubusercontent.com/u/$gitHubId?s=$pixels"
    val hash =
        MessageDigest
            .getInstance("SHA-256")
            .digest(normalized.toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
    return "https://gravatar.com/avatar/$hash?s=$pixels&d=404"
}

internal fun initialsOf(
    name: String,
    email: String,
): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val letters =
        when {
            words.size >= 2 -> "${words.first().first()}${words.last().first()}"
            words.size == 1 -> words.first().first().toString()
            else -> email.firstOrNull()?.toString() ?: "?"
        }
    return letters.uppercase()
}
