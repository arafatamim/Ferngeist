package com.tamimarafat.ferngeist.feature.serverlist.ui

import android.content.res.Resources
import com.tamimarafat.ferngeist.feature.serverlist.R

/**
 * The single fact a consent prompt has to state: where the agent runs and that the host may
 * pull the binary down. Title, subject and the acknowledgement cover which agent it is and
 * that the user trusts it, so the body never restates them.
 */
internal fun agentRiskLine(
    res: Resources,
    gatewayHost: String,
): String {
    val normalizedHost = gatewayHost.trim().ifBlank { res.getString(R.string.serverlist_risk_gateway_host_fallback) }
    return res.getString(R.string.serverlist_risk_runs_on, normalizedHost)
}
