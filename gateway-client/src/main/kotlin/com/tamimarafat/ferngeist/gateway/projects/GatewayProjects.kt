package com.tamimarafat.ferngeist.gateway.projects

import com.tamimarafat.ferngeist.gateway.GatewayClock
import com.tamimarafat.ferngeist.gateway.GatewayHttpPipeline
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.utils.io.readLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

// Lines arrive as agents finish, each within the gateway's 10 s listing limit plus
// the agent's startup, so the client's 30 s idle cut would drop a slow agent's line.
private const val PROJECTS_SOCKET_TIMEOUT_MS = 60_000L

class GatewayProjects
    @Inject
    constructor(
        httpClient: HttpClient,
        private val json: Json,
        clock: GatewayClock = GatewayClock(),
    ) {
        private val http = GatewayHttpPipeline(httpClient, json, clock)

        fun fetchProjects(
            scheme: String,
            host: String,
            gatewayCredential: String,
            agentIds: List<String>,
            knownRepos: List<String> = emptyList(),
        ): Flow<GatewayProjectLine> =
            flow {
                val endpoint =
                    http.buildGatewayEndpoint(
                        scheme,
                        host,
                        "v1",
                        "projects",
                        query =
                            buildMap {
                                put("agents", agentIds.joinToString(","))
                                if (knownRepos.isNotEmpty()) put("repos", json.encodeToString(knownRepos))
                            },
                    )
                http.getOpen(
                    credential = gatewayCredential,
                    endpoint = endpoint,
                    configure = {
                        accept(ContentType.parse("application/x-ndjson"))
                        timeout {
                            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                            socketTimeoutMillis = PROJECTS_SOCKET_TIMEOUT_MS
                        }
                    },
                ) { response ->
                    val channel = response.bodyAsChannel()
                    while (true) {
                        val line = channel.readLine() ?: break
                        if (line.isNotBlank()) emit(json.decodeFromString<GatewayProjectLine>(line))
                    }
                }
            }
    }
