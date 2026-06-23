package com.tamimarafat.ferngeist.acp.bridge.paseo

import android.util.Base64

/** Result of decoding a scanned/typed Paseo pairing input. */
sealed interface PaseoPairingInput {
    /** A direct local-network connection. */
    data class Direct(
        val scheme: String,
        val host: String,
        val password: String,
    ) : PaseoPairingInput

    /**
     * A relay [ConnectionOfferV2] — the standard Paseo pairing link/QR. Carries the
     * daemon's public key and relay endpoint; the connection is end-to-end encrypted.
     */
    data class Relay(
        val serverId: String,
        val daemonPublicKeyB64: String,
        val relayEndpoint: String,
        val relayUseTls: Boolean,
    ) : PaseoPairingInput

    data object Invalid : PaseoPairingInput
}

/**
 * Decodes Paseo pairing inputs:
 * - a relay `#offer=<base64url>` link or raw `ConnectionOfferV2` JSON → [PaseoPairingInput.Relay]
 * - `host:port` / `scheme://host:port` → [PaseoPairingInput.Direct]
 * - a direct-TCP host-connection JSON (`{type:"directTcp", endpoint, useTls, password?}`)
 */
object PaseoPairing {
    private const val OFFER_FRAGMENT_PREFIX = "#offer="

    fun decode(raw: String): PaseoPairingInput {
        val input = raw.trim()
        if (input.isEmpty()) return PaseoPairingInput.Invalid

        offerPayload(input)?.let { return decodeOfferPayload(it) }
        if (input.startsWith("{")) return decodeJson(input)
        // A bare offer payload (just the base64url, no "#offer=" / URL) — e.g. some QR encodings.
        if (looksLikeOfferPayload(input)) {
            val relay = decodeOfferPayload(input)
            if (relay is PaseoPairingInput.Relay) return relay
        }
        return decodePlainEndpoint(input)
    }

    private fun offerPayload(input: String): String? {
        val idx = input.indexOf(OFFER_FRAGMENT_PREFIX)
        if (idx < 0) return null
        // The offer is in the URL fragment, so it runs to the end of the string.
        return input.substring(idx + OFFER_FRAGMENT_PREFIX.length).substringBefore('&').takeIf { it.isNotBlank() }
    }

    /** A long base64url-ish token with no host punctuation — likely a raw offer payload. */
    private fun looksLikeOfferPayload(input: String): Boolean =
        input.length > 40 &&
            input.none { it == '.' || it == ':' || it == '/' || it == ' ' } &&
            input.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '=' || it == '+' || it == '/' }

    private fun decodeOfferPayload(payload: String): PaseoPairingInput {
        val json =
            runCatching {
                // Normalise base64url -> base64 and restore padding (Android Base64 needs it).
                var normalized = payload.trim().replace('-', '+').replace('_', '/')
                when (normalized.length % 4) {
                    2 -> normalized += "=="
                    3 -> normalized += "="
                }
                val bytes = Base64.decode(normalized, Base64.DEFAULT)
                PaseoJson.parseToJsonElement(String(bytes, Charsets.UTF_8)).asObjectOrNull()
            }.getOrNull() ?: return PaseoPairingInput.Invalid
        return offerToRelay(json)
    }

    private fun decodeJson(input: String): PaseoPairingInput {
        val obj = runCatching { PaseoJson.parseToJsonElement(input).asObjectOrNull() }.getOrNull()
            ?: return PaseoPairingInput.Invalid
        // A bare ConnectionOfferV2 JSON (carries a relay block).
        if (obj.obj("relay") != null && obj.str("daemonPublicKeyB64") != null) {
            return offerToRelay(obj)
        }
        // A direct-TCP host connection.
        val endpoint = obj.firstString("endpoint", "host", "address") ?: return PaseoPairingInput.Invalid
        val useTls = obj.boolOrNull("useTls") ?: false
        return PaseoPairingInput.Direct(
            scheme = if (useTls) "wss" else "ws",
            host = endpoint.removePrefix("ws://").removePrefix("wss://"),
            password = obj.str("password") ?: "",
        )
    }

    private fun offerToRelay(json: kotlinx.serialization.json.JsonObject): PaseoPairingInput {
        val serverId = json.str("serverId") ?: return PaseoPairingInput.Invalid
        val daemonKey = json.str("daemonPublicKeyB64") ?: return PaseoPairingInput.Invalid
        val relay = json.obj("relay") ?: return PaseoPairingInput.Invalid
        val endpoint = relay.firstString("endpoint", "host") ?: return PaseoPairingInput.Invalid
        return PaseoPairingInput.Relay(
            serverId = serverId,
            daemonPublicKeyB64 = daemonKey,
            relayEndpoint = endpoint,
            relayUseTls = relay.boolOrNull("useTls") ?: true,
        )
    }

    private fun decodePlainEndpoint(input: String): PaseoPairingInput {
        val (scheme, rest) =
            when {
                input.startsWith("wss://") -> "wss" to input.removePrefix("wss://")
                input.startsWith("ws://") -> "ws" to input.removePrefix("ws://")
                input.startsWith("https://") -> "wss" to input.removePrefix("https://")
                input.startsWith("http://") -> "ws" to input.removePrefix("http://")
                else -> "ws" to input
            }
        val host = rest.substringBefore('/').trim()
        if (host.isEmpty()) return PaseoPairingInput.Invalid
        return PaseoPairingInput.Direct(scheme = scheme, host = host, password = "")
    }
}
