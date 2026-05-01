package com.tamimarafat.ferngeist.onboarding

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val REGISTRY_URL = "https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json"
const val DEFAULT_AGENT_PORT = 6000

@Singleton
class AgentRegistryRepository
    @Inject
    constructor(
        private val httpClient: HttpClient,
        private val json: Json,
    ) {
        suspend fun fetchAgents(): List<RegistryAgent> {
            val payload = httpClient.get(REGISTRY_URL).bodyAsText()
            return json
                .decodeFromString<AgentRegistryResponse>(payload)
                .agents
                .sortedBy { it.name.lowercase() }
        }
    }

@Serializable
data class AgentRegistryResponse(
    val version: String,
    val agents: List<RegistryAgent> = emptyList(),
)

@Serializable
data class RegistryAgent(
    val id: String,
    val name: String,
    val version: String? = null,
    val description: String? = null,
    val repository: String? = null,
    val distribution: AgentDistribution = AgentDistribution(),
)

@Serializable
data class AgentDistribution(
    val npx: PackageDistribution? = null,
    val uvx: PackageDistribution? = null,
    val binary: Map<String, BinaryDistribution> = emptyMap(),
)

@Serializable
data class PackageDistribution(
    val `package`: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
)

@Serializable
data class BinaryDistribution(
    val archive: String,
    val cmd: String,
    val args: List<String> = emptyList(),
)

enum class PcPlatform(
    val label: String,
) {
    Windows("Windows"),
    MacOS("macOS"),
    Linux("Linux"),
}

data class AgentLaunchInstructions(
    val command: String?,
    val environment: Map<String, String>,
    val notes: List<String>,
)

object AgentLaunchCommandBuilder {
    fun build(
        agent: RegistryAgent,
        platform: PcPlatform,
        port: Int,
    ): AgentLaunchInstructions {
        val distribution =
            selectDistribution(agent, platform) ?: return AgentLaunchInstructions(
                command = null,
                environment = emptyMap(),
                notes = listOf("This registry entry does not include a runnable ACP command."),
            )

        // The ACP registry currently provides local process launch instructions, not WebSocket
        // transport metadata. Ferngeist connects over WebSocket, so onboarding wraps the agent
        // process with stdio-to-ws when generating a command from the registry.
        val wrappedCommand = "npx -y stdio-to-ws \"${escapeForDoubleQuotes(distribution.command)}\" --port $port"
        return AgentLaunchInstructions(
            command = wrappedCommand,
            environment = distribution.environment,
            notes = distribution.notes,
        )
    }

    private fun selectDistribution(
        agent: RegistryAgent,
        platform: PcPlatform,
    ): DistributionSelection? {
        agent.distribution.npx?.let { npx ->
            return DistributionSelection(
                command =
                    buildCommand(
                        launcher = "npx",
                        packageName = npx.`package`,
                        args = npx.args,
                        assumeYes = true,
                    ),
                environment = npx.env,
                notes = listOf("Make sure Node.js is installed on your computer."),
            )
        }

        agent.distribution.uvx?.let { uvx ->
            return DistributionSelection(
                command =
                    buildCommand(
                        launcher = "uvx",
                        packageName = uvx.`package`,
                        args = uvx.args,
                        assumeYes = false,
                    ),
                environment = uvx.env,
                notes = listOf("Install uv first if it is not already available on your computer."),
            )
        }

        val binary =
            agent.distribution.binary[platform.binaryKey]
                ?: agent.distribution.binary.entries
                    .firstOrNull()
                    ?.value
        if (binary != null) {
            return DistributionSelection(
                command = joinCommandParts(listOf(binary.cmd) + binary.args),
                environment = emptyMap(),
                notes =
                    listOf(
                        "Download and extract the agent first: ${binary.archive}",
                        "Run the generated command from the extracted folder on your computer.",
                    ),
            )
        }

        return null
    }

    private fun buildCommand(
        launcher: String,
        packageName: String,
        args: List<String>,
        assumeYes: Boolean,
    ): String {
        val parts =
            buildList {
                add(launcher)
                if (assumeYes) {
                    add("-y")
                }
                add(packageName)
                addAll(args)
            }
        return joinCommandParts(parts)
    }

    private fun joinCommandParts(parts: List<String>): String =
        parts.joinToString(" ") { part ->
            if (part.any(Char::isWhitespace) || '"' in part) {
                "\"${escapeForDoubleQuotes(part)}\""
            } else {
                part
            }
        }

    private fun escapeForDoubleQuotes(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}

val PcPlatform.binaryKey: String
    get() =
        when (this) {
            PcPlatform.Windows -> "windows-x86_64"
            PcPlatform.MacOS -> "darwin-aarch64"
            PcPlatform.Linux -> "linux-x86_64"
        }

private data class DistributionSelection(
    val command: String,
    val environment: Map<String, String>,
    val notes: List<String>,
)
