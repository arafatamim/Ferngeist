package com.tamimarafat.ferngeist.core.model

import java.util.UUID

/**
 * A launchable target backed by a paired desktop helper and one helper-managed
 * agent ID.
 */
data class HelperAgentBinding(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val helperSourceId: String,
    val agentId: String,
    val workingDirectory: String = "/",
    val preferredAuthMethodId: String? = null,
)

sealed interface LaunchableTarget {
    val id: String
    val name: String
    val workingDirectory: String
    val preferredAuthMethodId: String?

    data class Manual(
        val server: ServerConfig,
    ) : LaunchableTarget {
        override val id: String = server.id
        override val name: String = server.name
        override val workingDirectory: String = server.workingDirectory
        override val preferredAuthMethodId: String? = server.preferredAuthMethodId
    }

    data class HelperAgent(
        val binding: HelperAgentBinding,
        val helperSource: DesktopHelperSource,
    ) : LaunchableTarget {
        override val id: String = binding.id
        override val name: String = binding.name
        override val workingDirectory: String = binding.workingDirectory
        override val preferredAuthMethodId: String? = binding.preferredAuthMethodId
    }
}
