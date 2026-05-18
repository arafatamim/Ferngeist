package com.tamimarafat.ferngeist.feature.serverlist.ui

import android.content.res.Resources
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.gateway.GatewayAgent

internal fun addAgentRiskLines(
    res: Resources,
    agent: GatewayAgent,
    gatewayHost: String,
): List<String> {
    val normalizedHost = gatewayHost.trim().ifBlank { res.getString(R.string.serverlist_risk_gateway_host_fallback) }
    return listOf(
        res.getString(R.string.serverlist_risk_add_agent_line1, agent.displayName, agent.id),
        res.getString(R.string.serverlist_risk_add_agent_line2, normalizedHost),
        res.getString(R.string.serverlist_risk_add_agent_line3),
        res.getString(R.string.serverlist_risk_add_agent_line4),
    )
}

internal fun launchRiskLines(
    res: Resources,
    serverName: String,
    agentId: String,
    gatewayHost: String,
): List<String> {
    val normalizedHost = gatewayHost.trim().ifBlank { res.getString(R.string.serverlist_risk_gateway_host_fallback) }
    return listOf(
        res.getString(R.string.serverlist_risk_launch_agent_line1, serverName, agentId),
        res.getString(R.string.serverlist_risk_launch_agent_line2, normalizedHost),
        res.getString(R.string.serverlist_risk_launch_agent_line3),
        res.getString(R.string.serverlist_risk_launch_agent_line4),
    )
}
