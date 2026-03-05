package com.tamimarafat.ferngeist.acp.bridge.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AcpConnectionConfig(
    val scheme: String = "ws",
    val host: String,
    val authToken: String? = null,
)

sealed interface AcpConnectionState {
    data object Disconnected : AcpConnectionState
    data object Connecting : AcpConnectionState
    data object Connected : AcpConnectionState
    data class Failed(val error: Throwable) : AcpConnectionState
}

object AuthHeaderBuilder {
    fun build(config: AcpConnectionConfig): Map<String, String> = buildMap {
        config.authToken?.takeIf { it.isNotBlank() }?.let {
            put("Authorization", "Bearer $it")
        }
    }
}
