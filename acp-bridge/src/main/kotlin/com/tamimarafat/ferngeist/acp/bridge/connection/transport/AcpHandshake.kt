package com.tamimarafat.ferngeist.acp.bridge.connection.transport

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.model.AuthMethod
import com.agentclientprotocol.model.AuthMethodId
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ElicitationCapabilities
import com.agentclientprotocol.model.ElicitationFormCapabilities
import com.agentclientprotocol.model.ElicitationUrlCapabilities
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.Implementation
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthMethodInfo
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticateResult
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpDiagnosticsStore
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpInitializeResult
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpManagerEvent
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpTransportListener
import com.tamimarafat.ferngeist.acp.bridge.connection.AgentInfo
import com.tamimarafat.ferngeist.acp.bridge.connection.AuthEnvVarInfo
import com.tamimarafat.ferngeist.acp.bridge.connection.RpcDirection
import com.tamimarafat.ferngeist.acp.bridge.connection.formatAcpErrorMessage
import com.tamimarafat.ferngeist.acp.bridge.connection.withSteeringMeta
import io.ktor.client.request.url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Runs ACP initialization and authentication against the currently owned socket client. */
internal class AcpHandshake(
    private val currentClient: () -> Client?,
    private val diagnosticsStore: AcpDiagnosticsStore,
    private val scope: CoroutineScope,
    private val listener: AcpTransportListener,
    private val onAuthenticated: (String) -> Unit,
) {
    @OptIn(UnstableApi::class)
    suspend fun initialize(): AcpInitializeResult? {
        val client = currentClient() ?: return null
        return runCatching {
            diagnosticsStore.appendRpcEntry(RpcDirection.OUTBOUND_REQUEST, "initialize")
            val info =
                withTimeout(30_000L) {
                    client.initialize(
                        clientInfo =
                            ClientInfo(
                                capabilities =
                                    ClientCapabilities(
                                        fs =
                                            FileSystemCapability(
                                                readTextFile = false,
                                                writeTextFile = false,
                                            ),
                                        // Form mode renders a schema-driven sheet; URL mode shows
                                        // the target host and opens it in the system browser
                                        // on consent. Both must be explicit per spec: an
                                        // omitted mode reads as unsupported to the agent.
                                        elicitation =
                                            ElicitationCapabilities(
                                                form = ElicitationFormCapabilities(),
                                                url = ElicitationUrlCapabilities(),
                                            ),
                                    ),
                                implementation = Implementation(name = "Ferngeist", version = "1.0.0"),
                            ),
                    )
                }

            val mapped =
                AgentInfo(
                    name = info.implementation?.name?.takeIf { it.isNotBlank() } ?: "Agent",
                    version = info.implementation?.version?.takeIf { it.isNotBlank() } ?: "unknown",
                )
            val authMethods = info.authMethods.map(::mapAuthMethod)
            val result =
                AcpInitializeResult.Ready(
                    agentInfo = mapped,
                    agentCapabilities = info.capabilities.withSteeringMeta(info._meta),
                    authMethods = authMethods,
                )

            diagnosticsStore.recordInitialization(mapped)
            scope.launch { listener.emitManagerEvent(AcpManagerEvent.Initialized(result)) }
            result
        }.getOrElse { error ->
            if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            diagnosticsStore.appendError("initialize", formatAcpErrorMessage(error, "Initialization failed"))
            scope.launch { listener.emitManagerEvent(AcpManagerEvent.Error(error)) }
            null
        }
    }

    suspend fun authenticate(methodId: String): AcpAuthenticateResult {
        val client =
            currentClient()
                ?: return AcpAuthenticateResult.Failure(
                    "Authentication is unavailable because the server connection is closed.",
                )
        return runCatching {
            diagnosticsStore.appendRpcEntry(RpcDirection.OUTBOUND_REQUEST, "authenticate")
            client.authenticate(AuthMethodId(methodId))
            onAuthenticated(methodId)
            scope.launch { listener.emitManagerEvent(AcpManagerEvent.Authenticated(methodId)) }
            AcpAuthenticateResult.Success
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            val message = formatAcpErrorMessage(error, "Authentication failed")
            diagnosticsStore.appendError("authenticate", message)
            scope.launch { listener.emitManagerEvent(AcpManagerEvent.Error(error)) }
            AcpAuthenticateResult.Failure(message)
        }
    }
}

@OptIn(UnstableApi::class)
private fun mapAuthMethod(method: AuthMethod): AcpAuthMethodInfo =
    when (method) {
        is AuthMethod.AgentAuth ->
            AcpAuthMethodInfo(
                id = method.id.toString(),
                name = method.name,
                description = method.description,
                type = "agent",
            )

        is AuthMethod.EnvVarAuth ->
            AcpAuthMethodInfo(
                id = method.id.toString(),
                name = method.name,
                description = method.description,
                type = "env",
                envVars =
                    method.vars.map { variable ->
                        AuthEnvVarInfo(
                            name = variable.name,
                            label = variable.label,
                            secret = variable.secret,
                            optional = variable.optional,
                        )
                    },
                link = method.link,
            )

        is AuthMethod.TerminalAuth ->
            AcpAuthMethodInfo(
                id = method.id.toString(),
                name = method.name,
                description = method.description,
                type = "terminal",
                args = method.args.orEmpty(),
                env = method.env.orEmpty(),
            )

        is AuthMethod.UnknownAuthMethod ->
            AcpAuthMethodInfo(
                id = method.id.toString(),
                name = method.name,
                description = method.description,
                type = method.type,
            )
    }
