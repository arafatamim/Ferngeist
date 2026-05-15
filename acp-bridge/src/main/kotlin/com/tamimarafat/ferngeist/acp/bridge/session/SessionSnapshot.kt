package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.core.model.ChatMessage

enum class SessionLoadState {
    IDLE,
    HYDRATING,
    READY,
    FAILED,
}

data class SessionUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val cachedReadTokens: Int? = null,
    val contextWindowTokens: Int? = null,
    val costAmount: Double? = null,
    val costCurrency: String? = null,
)

data class SessionSnapshot(
    val sessionId: String,
    val loadState: SessionLoadState = SessionLoadState.IDLE,
    val messages: List<ChatMessage> = emptyList(),
    val isStreaming: Boolean = false,
    val usage: SessionUsage? = null,
    val availableCommands: List<CommandInfo> = emptyList(),
    val commandsAdvertised: Boolean = false,
    val configOptions: List<SessionConfigOption> = emptyList(),
    val error: String? = null,
)
