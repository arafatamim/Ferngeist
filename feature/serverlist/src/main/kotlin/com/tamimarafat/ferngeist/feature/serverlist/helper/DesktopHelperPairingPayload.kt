package com.tamimarafat.ferngeist.feature.serverlist.helper

import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parsed pairing payload shared by QR scans and manual paste flows. The helper
 * can encode either a custom URI or JSON payload containing host details plus
 * the pairing code shown on the desktop.
 */
data class DesktopHelperPairingPayload(
    val scheme: String,
    val host: String,
    val code: String,
    val challengeId: String? = null,
)

object DesktopHelperPairingPayloadParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): DesktopHelperPairingPayload? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        return parseUri(trimmed) ?: parseJson(trimmed)
    }

    private fun parseUri(raw: String): DesktopHelperPairingPayload? {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        if (uri.scheme != "ferngeist-helper") return null
        if (uri.host != "pair") return null
        val host = uri.getQueryParameter("host")?.trim().orEmpty()
        val code = uri.getQueryParameter("code")?.trim().orEmpty()
        if (host.isBlank() || code.isBlank()) return null
        return DesktopHelperPairingPayload(
            scheme = uri.getQueryParameter("scheme")?.trim().takeUnless { it.isNullOrBlank() } ?: "http",
            host = host,
            code = code,
            challengeId = uri.getQueryParameter("challengeId")?.trim().takeUnless { it.isNullOrBlank() },
        )
    }

    private fun parseJson(raw: String): DesktopHelperPairingPayload? {
        val payload = runCatching { json.decodeFromString<DesktopHelperPairingPayloadDto>(raw) }.getOrNull()
            ?: return null
        if (payload.host.isBlank() || payload.code.isBlank()) return null
        return DesktopHelperPairingPayload(
            scheme = payload.scheme?.takeUnless { it.isBlank() } ?: "http",
            host = payload.host.trim(),
            code = payload.code.trim(),
            challengeId = payload.challengeId?.trim().takeUnless { it.isNullOrBlank() },
        )
    }
}

@Serializable
private data class DesktopHelperPairingPayloadDto(
    val scheme: String? = null,
    val host: String,
    val code: String,
    val challengeId: String? = null,
)
