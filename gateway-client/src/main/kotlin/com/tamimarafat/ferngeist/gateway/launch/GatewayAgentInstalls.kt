package com.tamimarafat.ferngeist.gateway.launch

import com.tamimarafat.ferngeist.core.model.AgentInstallProgress
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import com.tamimarafat.ferngeist.gateway.parseGatewayErrorBody
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Duration.Companion.seconds

private const val INSTALLING = "agent_installing"
private const val DEFAULT_RETRY_SECONDS = 2L

/** Each caller owns its polling coroutine. The gateway owns and shares the actual installation. */
internal class GatewayAgentInstalls {
    private class Poll(
        val targetId: String,
    )

    private val active = MutableStateFlow<Map<Poll, AgentInstallProgress>>(emptyMap())

    val progress =
        active
            .map { polls -> polls.entries.associate { it.key.targetId to it.value } }
            .distinctUntilChanged()

    suspend fun <T> poll(
        targetId: String,
        request: suspend () -> T,
    ): T {
        val poll = Poll(targetId)
        try {
            while (true) {
                val failure =
                    try {
                        return request()
                    } catch (error: GatewayRequestException) {
                        error.takeIf { it.code == INSTALLING } ?: throw error
                    }

                val body = parseGatewayErrorBody(failure.responseBody)
                val progress =
                    AgentInstallProgress(
                        bytesReceived = body?.bytesReceived?.coerceAtLeast(0) ?: 0,
                        bytesTotal = body?.bytesTotal?.takeIf { it > 0 },
                    )
                active.update { it + (poll to progress) }
                val seconds =
                    failure.retryAfter
                        ?.trim()
                        ?.toLongOrNull()
                        ?.takeIf { it >= 0 } ?: DEFAULT_RETRY_SECONDS
                delay(seconds.seconds)
            }
        } finally {
            active.update { it - poll }
        }
    }
}
