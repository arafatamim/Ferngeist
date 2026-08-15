package com.tamimarafat.ferngeist.gateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLDecoder

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
        return buildPairingPayload(params)
    }

    private fun parseJson(raw: String): GatewayPairingPayload? {
        val payload =
            runCatching { json.decodeFromString<GatewayPairingPayloadDto>(raw) }.getOrNull()
                ?: return null
        return buildPairingPayload(
            host = payload.host.trim(),
            code = payload.code.trim(),
            challengeId = payload.challengeId?.trim().orEmpty(),
            scheme = payload.scheme?.takeUnless { it.isBlank() } ?: "http",
        )
    }

    private fun buildPairingPayload(params: Map<String, String>): GatewayPairingPayload? {
        val host = params["host"].orEmpty()
        val code = params["code"].orEmpty()
        val challengeId = params["challengeId"].orEmpty()
        return buildPairingPayload(host, code, challengeId, params["scheme"])
    }

    private fun buildPairingPayload(
        host: String,
        code: String,
        challengeId: String,
        scheme: String?,
    ): GatewayPairingPayload? {
        if (host.isBlank() || code.isBlank() || challengeId.isBlank()) return null
        return GatewayPairingPayload(
            scheme = scheme?.takeUnless { it.isNullOrBlank() } ?: "http",
            host = host,
            code = code,
            challengeId = challengeId,
        )
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        return query
            .split('&')
            .mapNotNull { segment ->
                val separatorIndex = segment.indexOf('=')
                if (separatorIndex <= 0) return@mapNotNull null
                val key = decodeQueryComponent(segment.substring(0, separatorIndex))
                val value = decodeQueryComponent(segment.substring(separatorIndex + 1))
                if (key.isBlank()) return@mapNotNull null
                key to value
            }.toMap()
    }

    private fun decodeQueryComponent(value: String): String = URLDecoder.decode(value, "UTF-8").trim()
}

@Serializable
private data class GatewayPairingPayloadDto(
    val scheme: String? = null,
    val host: String,
    val code: String,
    val challengeId: String? = null,
)
