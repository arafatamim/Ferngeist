package com.tamimarafat.ferngeist.feature.serverlist.helper

import com.tamimarafat.ferngeist.core.model.DesktopHelperSource
import com.tamimarafat.ferngeist.core.model.repository.DesktopHelperSourceRepository
import java.time.Instant

private const val HELPER_REFRESH_WINDOW_MS = 24L * 60L * 60L * 1000L

suspend fun refreshHelperSourceIfNeeded(
    helperSource: DesktopHelperSource,
    helperRepository: DesktopHelperRepository,
    helperSourceRepository: DesktopHelperSourceRepository,
    nowMillis: Long = System.currentTimeMillis(),
): DesktopHelperSource {
    val expiresAt = helperSource.helperCredentialExpiresAt ?: return helperSource
    if (expiresAt - nowMillis > HELPER_REFRESH_WINDOW_MS) {
        return helperSource
    }
    val refreshed = helperRepository.refreshCredential(
        scheme = helperSource.scheme,
        host = helperSource.host,
        helperCredential = helperSource.helperCredential,
    )
    val updated = helperSource.copy(
        helperCredential = refreshed.helperCredential,
        helperCredentialExpiresAt = refreshed.expiresAt.toEpochMillisOrNull(),
    )
    helperSourceRepository.updateHelper(updated)
    return updated
}

private fun String.toEpochMillisOrNull(): Long? {
    return runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
}
