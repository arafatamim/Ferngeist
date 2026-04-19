package com.tamimarafat.ferngeist.feature.serverlist.ui

import com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperAgent

internal fun addAgentRiskLines(agent: DesktopHelperAgent, companionHost: String): List<String> {
    val normalizedHost = companionHost.trim().ifBlank { "your desktop companion host" }
    return listOf(
        "This action adds ${agent.displayName} (${agent.id}) from your desktop companion.",
        "Launching this agent runs code on $normalizedHost.",
        "If the binary is not installed, the desktop companion may download it from the ACP registry source.",
        "Only add agents and sources you trust.",
    )
}

internal fun buildLaunchConsentKey(helperSourceId: String, agentId: String): String {
    return "$helperSourceId|$agentId"
}

internal fun launchRiskLines(serverName: String, agentId: String, companionHost: String): List<String> {
    val normalizedHost = companionHost.trim().ifBlank { "your desktop companion host" }
    return listOf(
        "Launching $serverName starts agent '$agentId' via your desktop companion.",
        "This can execute binaries on $normalizedHost.",
        "If required, your companion may download agent binaries from ACP registry sources.",
        "Continue only if you trust this agent and its source.",
    )
}
