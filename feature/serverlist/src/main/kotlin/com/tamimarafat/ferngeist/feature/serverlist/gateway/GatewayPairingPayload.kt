package com.tamimarafat.ferngeist.feature.serverlist.gateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLDecoder

/**
 * Parsed pairing payload shared by QR scans and manual paste flows. The gateway
 * now emits this payload from `ferngeist pair`, so Android expects it to carry
 * host details plus the one-time pairing challenge metadata.
 */
data class GatewayPairingPayload(
    val scheme: String,
    val host: String,
    val code: String,
    val challengeId: String,
)

object GatewayPairingPayloadParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): GatewayPairingPayload? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        return parseUri(trimmed) ?: parseJson(trimmed)
    }

    private fun parseUri(raw: String): GatewayPairingPayload? {
        val normalized = raw.removePrefix("ferngeist-gateway://")
        if (!normalized.startsWith("pair")) return null
        val query = raw.substringAfter('?', "")
        if (query.isBlank()) return null
        val params = parseQueryParams(query)
        val host = params["host"].orEmpty()
        val code = params["code"].orEmpty()
        val challengeId = params["challengeId"].orEmpty()
        if (host.isBlank() || code.isBlank() || challengeId.isBlank()) return null
        return GatewayPairingPayload(
            scheme = params["scheme"].takeUnless { it.isNullOrBlank() } ?: "http",
            host = host,
            code = code,
            challengeId = challengeId,
        )
    }

    private fun parseJson(raw: String): GatewayPairingPayload? {
        val payload = runCatching { json.decodeFromString<GatewayPairingPayloadDto>(raw) }.getOrNull()
            ?: return null
        val challengeId = payload.challengeId?.trim().orEmpty()
        if (payload.host.isBlank() || payload.code.isBlank() || challengeId.isBlank()) return null
        return GatewayPairingPayload(
            scheme = payload.scheme?.takeUnless { it.isBlank() } ?: "http",
            host = payload.host.trim(),
            code = payload.code.trim(),
            challengeId = challengeId,
        )
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        return query.split('&')
            .mapNotNull { segment ->
                val separatorIndex = segment.indexOf('=')
                if (separatorIndex <= 0) return@mapNotNull null
                val key = decodeQueryComponent(segment.substring(0, separatorIndex))
                val value = decodeQueryComponent(segment.substring(separatorIndex + 1))
                if (key.isBlank()) return@mapNotNull null
                key to value
            }
            .toMap()
    }

    private fun decodeQueryComponent(value: String): String {
        return URLDecoder.decode(value, "UTF-8").trim()
    }
}

@Serializable
private data class GatewayPairingPayloadDto(
    val scheme: String? = null,
    val host: String,
    val code: String,
    val challengeId: String? = null,
)
