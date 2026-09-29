package com.tamimarafat.ferngeist.core.model

/**
 * Where the ACP registry publishes per-agent logos.
 *
 * Every registry entry carries an `icon` pointing at `<base>/<agent-id>.svg`, and all
 * 41 published agents follow that convention. There is no PNG fallback.
 */
const val REGISTRY_ICON_BASE = "https://cdn.agentclientprotocol.com/registry/v1/latest"

/** The registry's logo URL for a registry [agentId], or null when the id cannot name one. */
fun registryIconUrl(agentId: String): String? =
    agentId
        .takeIf { it.isNotBlank() && it.none { char -> char == '/' || char == '\\' } }
        ?.let { "$REGISTRY_ICON_BASE/$it.svg" }

/**
 * The logo URL for this target, or null when it cannot have one.
 *
 * Custom and embedded agents — including every agent this app adds itself — have no
 * registry entry and therefore no logo. Their id is still a plausible-looking string,
 * so it is deliberately NOT run through [registryIconUrl]: that would fabricate a URL
 * that 404s at paint time.
 *
 * Provenance lives on the gateway, not here: a binding stores only `agentId`, with no
 * record of whether the gateway sourced it from the registry. The url is therefore
 * forwarded from the gateway's own `registry.icon` field (see the gateway PRD, R2)
 * rather than reconstructed from the id.
 */
val LaunchableTarget.iconUrl: String?
    get() =
        when (this) {
            is LaunchableTarget.GatewayAgent -> binding.icon
            is LaunchableTarget.Manual -> null
        }
