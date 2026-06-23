package com.tamimarafat.ferngeist.acp.bridge.paseo

import com.tamimarafat.ferngeist.core.model.PaseoSource
import com.tamimarafat.ferngeist.core.model.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A provider advertised by a Paseo daemon, with its selectable models/modes. */
internal data class PaseoProvider(
    val id: String,
    val name: String,
    val available: Boolean,
    val models: List<PaseoOption>,
    val modes: List<PaseoOption>,
    val currentModelId: String?,
    val currentModeId: String?,
)

internal data class PaseoOption(
    val id: String,
    val label: String,
)

/** A resolved Paseo connection target. */
internal sealed interface PaseoConnection {
    data class Direct(
        val scheme: String,
        val host: String,
        val password: String,
    ) : PaseoConnection

    data class Relay(
        val serverId: String,
        val daemonPublicKeyB64: String,
        val relayEndpoint: String,
        val useTls: Boolean,
    ) : PaseoConnection
}

internal fun PaseoSource.toPaseoConnection(): PaseoConnection =
    if (isRelay) {
        PaseoConnection.Relay(
            serverId = serverId,
            daemonPublicKeyB64 = daemonPublicKeyB64,
            relayEndpoint = relayEndpoint,
            useTls = relayUseTls,
        )
    } else {
        PaseoConnection.Direct(scheme = scheme, host = host, password = password)
    }

/**
 * Manages a single active Paseo daemon connection over the local network and the
 * agents (sessions) running on it. Mirrors the role of `AcpConnectionManager` for
 * the Paseo transport, terminating at [PaseoSessionPort] / `SessionPort`.
 *
 * A singleton per app process; `connect` switches the active source (like the ACP
 * manager's single active connection). The `clientId` is derived from the source id
 * so reconnecting to the same source resumes the daemon-side session.
 */
internal class PaseoConnectionManager(
    private val scope: CoroutineScope,
    private val appVersion: String,
) : PaseoSessionActions {
    @Volatile
    private var transport: PaseoTransportClient? = null

    @Volatile
    private var inboundJob: kotlinx.coroutines.Job? = null

    private val ports = ConcurrentHashMap<String, PaseoSessionPort>()

    @Volatile
    var attentionSink: (PaseoAttention) -> Unit = {}

    @Volatile
    private var stateMirrorJob: kotlinx.coroutines.Job? = null

    private val _connectionState =
        kotlinx.coroutines.flow.MutableStateFlow<PaseoConnectionState>(PaseoConnectionState.Disconnected)

    /** Stable connection-state flow that mirrors the active transport across reconnects. */
    val connectionState: StateFlow<PaseoConnectionState> = _connectionState

    @Volatile
    var activeServerUrl: String? = null
        private set

    val isConnected: Boolean
        get() = transport?.isConnected == true

    /** Connects via [connection] (resuming the prior session keyed by [sourceId]). */
    suspend fun connect(
        sourceId: String,
        connection: PaseoConnection,
    ): Boolean {
        val existing = transport
        if (existing != null && existing.isConnected && activeSourceId == sourceId) {
            return true
        }
        teardown()
        val client =
            PaseoTransportClient(
                scope = scope,
                clientId = "ferngeist-$sourceId",
                appVersion = appVersion,
            )
        transport = client
        activeSourceId = sourceId
        activeServerUrl =
            when (connection) {
                is PaseoConnection.Direct -> "${connection.scheme}://${connection.host}"
                is PaseoConnection.Relay -> connection.relayEndpoint
            }
        inboundJob =
            scope.launch {
                client.inbound.collect { routeInbound(it) }
            }
        stateMirrorJob =
            scope.launch {
                client.state.collect { _connectionState.value = it }
            }
        val ok = client.connect(connection)
        if (!ok) teardown()
        return ok
    }

    @Volatile
    private var activeSourceId: String? = null

    private fun teardown() {
        inboundJob?.cancel()
        inboundJob = null
        stateMirrorJob?.cancel()
        stateMirrorJob = null
        transport?.disconnect()
        transport = null
        ports.clear()
        activeSourceId = null
        activeServerUrl = null
        _connectionState.value = PaseoConnectionState.Disconnected
    }

    fun disconnect() = teardown()

    fun getSession(agentId: String): PaseoSessionPort? = ports[agentId]

    // ---- Inbound routing ----

    private suspend fun routeInbound(message: JsonObject) {
        when (message.typeTag()) {
            "agent_stream" -> {
                val payload = message.obj("payload") ?: return
                val agentId = payload.str("agentId") ?: return
                val event = payload.obj("event") ?: return
                ports[agentId]?.ingestStreamEvent(event)
            }

            "agent_permission_request" -> {
                val payload = message.obj("payload") ?: return
                val agentId = payload.str("agentId") ?: return
                payload.obj("request")?.let { ports[agentId]?.handlePermissionRequest(it) }
            }

            "agent_permission_resolved" -> {
                val payload = message.obj("payload") ?: return
                val agentId = payload.str("agentId") ?: return
                ports[agentId]?.handlePermissionResolved(payload.str("requestId"))
            }

            else -> Unit
        }
    }

    // ---- Discovery ----

    /** Lists providers (with models/modes) advertised by the daemon for [cwd]. */
    suspend fun discoverProviders(cwd: String?): List<PaseoProvider> {
        val client = transport ?: return emptyList()
        val fields =
            buildJsonObject {
                if (!cwd.isNullOrBlank()) put("cwd", cwd)
            }
        val response =
            runCatching {
                client.request("get_providers_snapshot_request", newRequestId(), fields)
            }.getOrNull() ?: return emptyList()
        val payload = response.obj("payload") ?: return emptyList()
        val entries =
            payload.arr("providers")
                ?: payload.arr("entries")
                ?: payload.arr("snapshot")
                ?: return emptyList()
        return entries.mapNotNull { el ->
            val o = el.asObjectOrNull() ?: return@mapNotNull null
            val id = o.firstString("provider", "id", "providerId") ?: return@mapNotNull null
            PaseoProvider(
                id = id,
                name = o.firstString("label", "name", "displayName") ?: id,
                available = o.boolOrNull("available") ?: o.boolOrNull("enabled") ?: true,
                models = parseOptions(o, "models"),
                modes = parseOptions(o, "modes").ifEmpty { parseOptions(o, "availableModes") },
                currentModelId = o.firstString("currentModelId", "model", "selectedModelId"),
                currentModeId = o.firstString("currentModeId", "defaultModeId", "modeId", "selectedModeId"),
            )
        }
    }

    private fun parseOptions(
        provider: JsonObject,
        key: String,
    ): List<PaseoOption> =
        provider.arr(key)?.mapNotNull { el ->
            val o = el.asObjectOrNull() ?: return@mapNotNull null
            val id = o.firstString("id", "value", "modelId", "modeId") ?: return@mapNotNull null
            PaseoOption(id = id, label = o.firstString("name", "label", "displayName") ?: id)
        } ?: emptyList()

    /** Lists agents (sessions) on the daemon, optionally filtered to [provider] / [cwd]. */
    suspend fun listAgents(
        provider: String?,
        cwd: String?,
    ): List<SessionSummary> {
        val client = transport ?: return emptyList()
        // The daemon lists persisted agents via fetch_agent_history; each entry wraps an agent.
        val response =
            runCatching {
                client.request("fetch_agent_history_request", newRequestId(), buildJsonObject {})
            }.getOrNull() ?: return emptyList()
        val payload = response.obj("payload") ?: return emptyList()
        val entries = payload.arr("entries") ?: payload.arr("agents") ?: return emptyList()
        return entries.mapNotNull { el ->
            val entry = el.asObjectOrNull() ?: return@mapNotNull null
            val agent = entry.obj("agent") ?: entry
            val id = agent.firstString("id", "agentId") ?: return@mapNotNull null
            val agentProvider = agent.firstString("provider", "providerId")
            if (provider != null && agentProvider != null && agentProvider != provider) return@mapNotNull null
            val agentCwd = agent.firstString("cwd", "workingDirectory", "directory")
            if (!cwd.isNullOrBlank() && agentCwd != null && agentCwd != cwd) return@mapNotNull null
            SessionSummary(
                id = id,
                title = agentCwd?.let { deriveTitle(it) },
                cwd = agentCwd,
                updatedAt =
                    parseTimestampMillis(
                        agent.firstString("updatedAt", "lastUserMessageAt", "createdAt"),
                    ),
            )
        }
    }

    /** Parses a Paseo timestamp (epoch-millis number or ISO-8601 string) to epoch millis. */
    private fun parseTimestampMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        value.toLongOrNull()?.let { return it }
        return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
    }

    /** A readable session title from a working directory (its last path segment). */
    private fun deriveTitle(cwd: String): String? =
        cwd.trimEnd('/', '\\').substringAfterLast('\\').substringAfterLast('/').ifBlank { null }

    // ---- Session lifecycle ----

    /** Creates a new agent (session) for [provider] in [cwd], returning a ready port. */
    suspend fun createSession(
        provider: String,
        cwd: String,
        model: String?,
    ): PaseoSessionPort? {
        val client = transport ?: return null
        val fields =
            buildJsonObject {
                putJsonObject("config") {
                    put("provider", provider)
                    put("cwd", cwd)
                    if (!model.isNullOrBlank()) put("model", model)
                }
            }
        val response =
            runCatching {
                client.request("create_agent_request", newRequestId(), fields)
            }.getOrNull() ?: return null
        val agentId = extractAgentId(response) ?: return null
        val port = registerPort(agentId)
        port.markReady()
        return port
    }

    /**
     * Re-attaches to an existing agent on the daemon and hydrates it from the
     * timeline tail. Returns null if the agent is unknown (e.g. daemon restarted).
     */
    suspend fun loadSession(agentId: String): PaseoSessionPort? {
        val client = transport ?: return null
        // Confirm the agent still exists on the daemon.
        val fetch =
            runCatching {
                client.request(
                    "fetch_agent_request",
                    newRequestId(),
                    buildJsonObject { put("agentId", agentId) },
                )
            }.getOrNull() ?: return null
        val payload = fetch.obj("payload")
        val exists = payload?.obj("agent") != null || payload?.str("agentId") != null
        if (!exists || payload?.str("error") != null) return null

        val port = registerPort(agentId)
        port.beginHydration()
        runCatching { hydrateTimeline(client, agentId, port) }
            .onFailure { port.failHydration(it.message) }
        port.completeHydration()
        port.emitEvent(com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent.SessionLoadComplete)
        port.markReady()
        return port
    }

    private suspend fun hydrateTimeline(
        client: PaseoTransportClient,
        agentId: String,
        port: PaseoSessionPort,
    ) {
        val response =
            client.request(
                "fetch_agent_timeline_request",
                newRequestId(),
                buildJsonObject {
                    put("agentId", agentId)
                    put("direction", "tail")
                    put("limit", TIMELINE_PAGE_LIMIT)
                    put("projection", "projected")
                },
            )
        val payload = response.obj("payload") ?: return
        val entries = payload.arr("entries") ?: return
        // Replay oldest-first; entries may carry a `seq` for ordering.
        val items =
            entries.mapNotNull { it.asObjectOrNull() }
                .sortedBy { it.longOrNull("seq") ?: 0L }
        for (entry in items) {
            val item = entry.obj("item") ?: entry
            port.ingestTimelineItem(item)
        }
    }

    private fun registerPort(agentId: String): PaseoSessionPort {
        return ports.getOrPut(agentId) {
            PaseoSessionPort(
                sessionId = agentId,
                actions = this,
                onAttention = { attentionSink(it) },
            )
        }
    }

    private fun extractAgentId(response: JsonObject): String? {
        val payload = response.obj("payload") ?: response
        return payload.firstString("agentId", "id")
            ?: payload.obj("agent")?.firstString("id", "agentId")
    }

    // ---- PaseoSessionActions (fire-and-forget to avoid blocking the UI) ----

    override suspend fun sendMessage(
        agentId: String,
        text: String,
        images: List<Pair<String, String>>,
    ) {
        val client = transport ?: throw PaseoRpcException("Not connected")
        val inner =
            buildJsonObject {
                put("type", "send_agent_message_request")
                put("agentId", agentId)
                put("text", text)
                put("messageId", UUID.randomUUID().toString())
                if (images.isNotEmpty()) {
                    putJsonArray("images") {
                        images.forEach { (base64, mime) ->
                            add(
                                buildJsonObject {
                                    put("data", base64)
                                    put("mimeType", mime)
                                },
                            )
                        }
                    }
                }
                put("requestId", newRequestId())
            }
        client.sendSession(inner)
    }

    override suspend fun cancel(agentId: String) {
        val client = transport ?: return
        client.sendSession(
            buildJsonObject {
                put("type", "cancel_agent_request")
                put("agentId", agentId)
                put("requestId", newRequestId())
            },
        )
    }

    override suspend fun respondPermission(
        agentId: String,
        requestId: String,
        allow: Boolean,
        selectedActionId: String?,
    ) {
        val client = transport ?: return
        client.sendSession(
            buildJsonObject {
                put("type", "agent_permission_response")
                put("agentId", agentId)
                put("requestId", requestId)
                putJsonObject("response") {
                    put("behavior", if (allow) "allow" else "deny")
                    if (selectedActionId != null) put("selectedActionId", selectedActionId)
                }
            },
        )
    }

    override suspend fun setModel(
        agentId: String,
        modelId: String?,
    ) {
        val client = transport ?: return
        client.sendSession(
            buildJsonObject {
                put("type", "set_agent_model_request")
                put("agentId", agentId)
                put("modelId", JsonPrimitive(modelId))
                put("requestId", newRequestId())
            },
        )
    }

    override suspend fun setMode(
        agentId: String,
        modeId: String,
    ) {
        val client = transport ?: return
        client.sendSession(
            buildJsonObject {
                put("type", "set_agent_mode_request")
                put("agentId", agentId)
                put("modeId", modeId)
                put("requestId", newRequestId())
            },
        )
    }

    private fun newRequestId(): String = "req-${UUID.randomUUID()}"

    companion object {
        private const val TIMELINE_PAGE_LIMIT = 200
    }
}
