package com.tamimarafat.ferngeist.gateway.projects

import com.tamimarafat.ferngeist.gateway.GatewayClock
import com.tamimarafat.ferngeist.gateway.GatewayHttpPipeline
import io.ktor.client.HttpClient
import io.ktor.utils.io.readLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import javax.inject.Inject

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
        ): Flow<GatewayProjectLine> =
            flow {
                http.getStream(scheme, host, gatewayCredential, agentIds) { channel ->
                    while (true) {
                        val line = channel.readLine() ?: break
                        if (line.isNotBlank()) emit(json.decodeFromString<GatewayProjectLine>(line))
                    }
                }
            }
    }
