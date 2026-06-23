package com.tamimarafat.ferngeist.core.model

import java.util.UUID

/**
 * A paired Paseo daemon. Two connection modes are supported:
 *
 * - [MODE_DIRECT] — a direct local-network WebSocket (`ws(s)://host/ws`) with an
 *   optional bearer password.
 * - [MODE_RELAY] — Paseo's hosted relay with end-to-end NaCl encryption. This is how
 *   the standard Paseo pairing QR/link works: the offer carries a [serverId], the
 *   daemon's Curve25519 public key ([daemonPublicKeyB64]) and a [relayEndpoint]; there
 *   is no password (identity/confidentiality come from the E2EE handshake).
 *
 * @property mode [MODE_DIRECT] or [MODE_RELAY].
 * @property scheme direct: `ws`/`wss`.
 * @property host direct: host and port, e.g. `127.0.0.1:6767`.
 * @property password direct: optional bearer password.
 * @property serverId relay: daemon server id from the pairing offer.
 * @property daemonPublicKeyB64 relay: daemon Curve25519 public key (standard base64).
 * @property relayEndpoint relay: relay host:port, e.g. `relay.paseo.sh:443`.
 * @property relayUseTls relay: whether the relay endpoint uses TLS.
 */
data class PaseoSource(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mode: String = MODE_DIRECT,
    val scheme: String = "ws",
    val host: String = "",
    val password: String = "",
    val serverId: String = "",
    val daemonPublicKeyB64: String = "",
    val relayEndpoint: String = "",
    val relayUseTls: Boolean = true,
) {
    val isRelay: Boolean get() = mode == MODE_RELAY

    companion object {
        const val MODE_DIRECT = "direct"
        const val MODE_RELAY = "relay"
    }
}
