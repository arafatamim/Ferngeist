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
 * Agent IDs do not establish registry provenance, so constructing a registry URL
 * would fabricate one that can 404. The binding instead stores the gateway's
 * registry logo or bundled fallback.
 */
val LaunchableTarget.iconUrl: String?
    get() =
        when (this) {
            is LaunchableTarget.GatewayAgent -> binding.icon
            is LaunchableTarget.Manual -> null
        }
