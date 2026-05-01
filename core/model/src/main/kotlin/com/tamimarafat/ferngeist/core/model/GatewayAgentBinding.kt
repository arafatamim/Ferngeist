package com.tamimarafat.ferngeist.core.model

import java.util.UUID

data class GatewayAgentBinding(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val gatewaySourceId: String,
    val agentId: String,
    val preferredAuthMethodId: String? = null,
)

sealed interface LaunchableTarget {
    val id: String
    val name: String
    val preferredAuthMethodId: String?

    data class Manual(
        val server: ServerConfig,
    ) : LaunchableTarget {
        override val id: String = server.id
        override val name: String = server.name
        override val preferredAuthMethodId: String? = server.preferredAuthMethodId
    }

    data class GatewayAgent(
        val binding: GatewayAgentBinding,
        val gatewaySource: GatewaySource,
    ) : LaunchableTarget {
        override val id: String = binding.id
        override val name: String = binding.name
        override val preferredAuthMethodId: String? = binding.preferredAuthMethodId
    }
}
