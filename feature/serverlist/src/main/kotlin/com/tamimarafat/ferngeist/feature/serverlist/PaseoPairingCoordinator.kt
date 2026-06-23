package com.tamimarafat.ferngeist.feature.serverlist

import com.tamimarafat.ferngeist.acp.bridge.facade.PaseoBackend
import com.tamimarafat.ferngeist.acp.bridge.paseo.PaseoPairing
import com.tamimarafat.ferngeist.acp.bridge.paseo.PaseoPairingInput
import com.tamimarafat.ferngeist.core.model.PaseoAgentBinding
import com.tamimarafat.ferngeist.core.model.PaseoSource
import com.tamimarafat.ferngeist.core.model.repository.PaseoAgentBindingRepository
import com.tamimarafat.ferngeist.core.model.repository.PaseoSourceRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pairs the app with a Paseo daemon over the local network and persists it as a set of
 * launchable targets — the Paseo replacement for the gateway pairing flow.
 *
 * Accepts a typed `host:port`, a direct-TCP host-connection JSON, or a scanned QR. Relay
 * (`#offer=`) QRs are reported as unsupported because the local-network backend does not
 * implement the relay/E2EE transport.
 */
@Singleton
class PaseoPairingCoordinator
    @Inject
    constructor(
        private val sourceRepository: PaseoSourceRepository,
        private val bindingRepository: PaseoAgentBindingRepository,
        private val paseoBackend: PaseoBackend,
    ) {
        sealed interface Result {
            data class Paired(val sourceId: String, val bindingCount: Int) : Result

            data class Failed(val message: String) : Result
        }

        /**
         * @param name display name for the daemon (falls back to host).
         * @param input typed endpoint, direct-TCP JSON, or scanned QR content.
         * @param password bearer password; overrides any password embedded in [input].
         * @param defaultCwd working directory for created bindings.
         */
        suspend fun pair(
            name: String,
            input: String,
            password: String? = null,
            defaultCwd: String = "~",
        ): Result {
            val source =
                when (val decoded = PaseoPairing.decode(input)) {
                    is PaseoPairingInput.Direct ->
                        PaseoSource(
                            name = name.ifBlank { decoded.host },
                            mode = PaseoSource.MODE_DIRECT,
                            scheme = decoded.scheme,
                            host = decoded.host,
                            password = password?.takeIf { it.isNotBlank() } ?: decoded.password,
                        )

                    is PaseoPairingInput.Relay ->
                        PaseoSource(
                            name = name.ifBlank { "Paseo (${decoded.serverId})" },
                            mode = PaseoSource.MODE_RELAY,
                            serverId = decoded.serverId,
                            daemonPublicKeyB64 = decoded.daemonPublicKeyB64,
                            relayEndpoint = decoded.relayEndpoint,
                            relayUseTls = decoded.relayUseTls,
                        )

                    PaseoPairingInput.Invalid ->
                        return Result.Failed("Could not read a Paseo connection from that input.")
                }

            val providers =
                runCatching { paseoBackend.discoverProviders(source) }
                    .getOrElse { return Result.Failed(it.message ?: "Failed to reach the daemon.") }
            if (providers.isEmpty()) {
                return Result.Failed(
                    "Connected, but no providers were advertised. Check the password and that agents are installed.",
                )
            }

            sourceRepository.addSource(source)
            val usable = providers.filter { it.available }.ifEmpty { providers }
            usable.forEach { provider ->
                bindingRepository.addBinding(
                    PaseoAgentBinding(
                        name = provider.name,
                        paseoSourceId = source.id,
                        provider = provider.id,
                        cwd = defaultCwd,
                    ),
                )
            }
            return Result.Paired(sourceId = source.id, bindingCount = usable.size)
        }
    }
