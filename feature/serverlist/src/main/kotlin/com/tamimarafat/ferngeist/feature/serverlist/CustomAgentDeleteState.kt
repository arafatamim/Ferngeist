package com.tamimarafat.ferngeist.feature.serverlist

import com.tamimarafat.ferngeist.gateway.GatewayAgent
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import com.tamimarafat.ferngeist.gateway.gatewayErrorMessage

/** The gateway's refused-delete marker: HTTP 409 with its error body. */
private const val AGENT_RUNNING_MARKER = "agent has running runtimes"

/** HTTP 409 Conflict: the gateway refuses to delete an agent that still holds a runtime. */
private const val AGENT_RUNNING_STATUS = 409

/** What the catalog screen must be asking the user about, if anything. */
sealed interface CustomAgentDelete {
    val agent: GatewayAgent

    /** Awaiting confirmation before the delete is attempted. */
    data class Confirm(
        override val agent: GatewayAgent,
    ) : CustomAgentDelete

    /** Refused: the agent holds a live runtime, so the user must choose to stop it first. */
    data class Running(
        override val agent: GatewayAgent,
    ) : CustomAgentDelete
}

/** True when a refused delete means the agent still has a live runtime. */
internal fun isAgentRunningError(error: Throwable): Boolean =
    error is GatewayRequestException &&
        error.statusCode == AGENT_RUNNING_STATUS &&
        gatewayErrorMessage(error.responseBody)?.contains(AGENT_RUNNING_MARKER) == true
