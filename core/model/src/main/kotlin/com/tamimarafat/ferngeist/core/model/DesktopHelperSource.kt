package com.tamimarafat.ferngeist.core.model

import java.util.UUID

/**
 * A paired desktop helper daemon. Helper-backed launch targets reference this
 * record instead of storing pairing credentials inside each target row.
 */
data class DesktopHelperSource(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val scheme: String = "http",
    val host: String,
    val helperCredential: String,
    val helperCredentialExpiresAt: Long? = null,
    val helperRemoteMode: String? = null,
)
