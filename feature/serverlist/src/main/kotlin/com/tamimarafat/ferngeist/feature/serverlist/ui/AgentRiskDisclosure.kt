package com.tamimarafat.ferngeist.feature.serverlist.ui

import com.tamimarafat.ferngeist.gateway.GatewayAgent

internal fun addAgentRiskLines(agent: GatewayAgent, gatewayHost: String): List<String> {
    val normalizedHost = gatewayHost.trim().ifBlank { "your gateway host" }
    return listOf(
        "This action adds ${agent.displayName} (${agent.id}) from your gateway.",
        "Launching this agent runs code on $normalizedHost.",
        "If the binary is not installed, the gateway may download it from the ACP registry source.",
        "Only add agents and sources you trust."
    )
}

internal fun launchRiskLines(serverName: String, agentId: String, gatewayHost: String): List<String> {
    val normalizedHost = gatewayHost.trim().ifBlank { "your gateway host" }
    return listOf(
        "Launching $serverName starts agent '$agentId' via your gateway.",
        "This can execute binaries on $normalizedHost.",
        "If required, your gateway may download agent binaries from ACP registry sources.",
        "Continue only if you trust this agent and its source."
    )
}
