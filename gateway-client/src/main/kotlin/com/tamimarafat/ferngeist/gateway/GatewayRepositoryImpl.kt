package com.tamimarafat.ferngeist.gateway

import com.agentclientprotocol.model.ToolCallContent
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private val PROTOCOL_VERIFIED_NANOS = TimeUnit.MINUTES.toNanos(5)

@Suppress("TooManyFunctions") // one override per gateway endpoint
class GatewayRepositoryImpl
    @Inject
    constructor(
        httpClient: HttpClient,
        private val json: Json,
        private val gatewayClock: GatewayClock = GatewayClock(),
    ) : GatewayRepository,
        GatewayPairingRepository,
        GatewayAuthRepository,
        GatewayRuntimeRepository,
        GatewaySessionRepository,
        GatewayPushRepository,
        GatewayWorkspaceRepository,
        GatewayCustomAgentRepository,
        GatewayWorktreeRepository {
        /** Every JSON call, along with its signed send and retry recovery, in one pipeline. */
        private val http = GatewayHttpPipeline(httpClient, json, gatewayClock)

        override suspend fun fetchStatus(
            scheme: String,
            host: String,
        ): GatewayStatus {
            val response =
                http.getJson<GatewayStatus>(
                    scheme = scheme,
                    host = host,
                    credential = null,
                    "v1",
                    "status",
                )
            // The status endpoint is the unauthenticated handshake every flow goes
            // through (pairing, opening a gateway, reconnect); gate on protocol
            // compatibility here so mismatches surface clearly instead of as 404/422s.
            response.requireSupportedProtocol()
            // The handshake is the first place the app sees the gateway's clock; seed
            // the offset up front so the first signed request is already correct.
            parseGatewayServerTimeEpochSeconds(response.serverTime)?.let { serverSeconds ->
                gatewayClock.recordServerTime(host, serverSeconds)
            }
            return response
        }

        /** Hosts whose protocol check passed, with the [System.nanoTime] it stays good until. */
        private val protocolVerifiedUntil = ConcurrentHashMap<String, Long>()

        // A gateway upgrade inside the window surfaces as the gateway's own error on connect
        // instead of the clear mismatch message; the window bounds how long that can last.
        override suspend fun verifyProtocol(
            scheme: String,
            host: String,
        ) {
            val key = "$scheme://$host"
            val now = System.nanoTime()
            protocolVerifiedUntil[key]?.let { until -> if (until - now > 0) return }
            fetchStatus(scheme, host)
            protocolVerifiedUntil[key] = now + PROTOCOL_VERIFIED_NANOS
        }

        override suspend fun startPairing(
            scheme: String,
            host: String,
        ): GatewayPairStartResponse =
            http.postJson(
                scheme = scheme,
                host = host,
                credential = null,
                "v1",
                "pair",
                "start",
            )

        override suspend fun fetchAgents(
            scheme: String,
            host: String,
            gatewayCredential: String,
        ): List<GatewayAgent> {
            val response =
                http.getJson<GatewayAgentsResponse>(
                    scheme,
                    host,
                    gatewayCredential,
                    "v1",
                    "agents",
                )
            return response.agents
        }

        override suspend fun connectAgent(
            scheme: String,
            host: String,
            gatewayCredential: String,
            agentId: String,
        ): GatewayConnectResponse =
            http.postJson(
                scheme = scheme,
                host = host,
                credential = gatewayCredential,
                "v1",
                "agents",
                agentId,
                "connect",
            )

        override suspend fun createCustomAgent(
            scheme: String,
            host: String,
            gatewayCredential: String,
            displayName: String,
            command: String,
            args: List<String>,
            hint: String,
        ): GatewayAgent =
            http.postJson(
                scheme = scheme,
                host = host,
                credential = gatewayCredential,
                "v1",
                "agents",
                "custom",
                body = buildCustomAgentRequestBody(displayName, command, args, hint),
            )

        override suspend fun deleteCustomAgent(
            scheme: String,
            host: String,
            gatewayCredential: String,
            agentId: String,
        ) {
            http.deleteJsonUnit(
                scheme = scheme,
                host = host,
                credential = gatewayCredential,
                "v1",
                "agents",
                "custom",
                agentId,
            )
        }

        override suspend fun stopAgent(
            scheme: String,
            host: String,
            gatewayCredential: String,
            agentId: String,
        ) {
            http.postJsonUnit(
                scheme = scheme,
                host = host,
                credential = gatewayCredential,
                "v1",
                "agents",
                agentId,
                "stop",
            )
        }

        override suspend fun createWorktree(
            scheme: String,
            host: String,
            gatewayCredential: String,
            repo: String,
            base: String?,
            branch: String?,
        ): GatewayWorktree =
            http.postJson(
                scheme = scheme,
                host = host,
                credential = gatewayCredential,
                "v1",
                "worktrees",
                body = buildCreateWorktreeRequestBody(repo, base, branch),
            )

        override suspend fun listWorktrees(
            scheme: String,
            host: String,
            gatewayCredential: String,
        ): List<GatewayWorktree> =
            http.getJson(
                scheme = scheme,
                host = host,
                credential = gatewayCredential,
                "v1",
                "worktrees",
            )

        override suspend fun listUnmanagedWorktrees(
            scheme: String,
            host: String,
            gatewayCredential: String,
            repo: String,
        ): List<GatewayUnmanagedWorktree> =
            http.getJson(
                scheme = scheme,
                host = host,
                credential = gatewayCredential,
                "v1",
                "worktrees",
                "unmanaged",
                query = mapOf("repo" to repo),
            )

        override suspend fun adoptWorktree(
            scheme: String,
            host: String,
            gatewayCredential: String,
            path: String,
            repo: String?,
        ): GatewayWorktree =
            http.postJson(
                scheme = scheme,
                host = host,
                credential = gatewayCredential,
                "v1",
                "worktrees",
                "adopt",
                body =
                    requestBodyJson.encodeToString(
                        GatewayAdoptWorktreeRequest(path = path, repo = repo?.trim()?.ifBlank { null }),
                    ),
            )

        override suspend fun fetchRepoRefs(
            scheme: String,
            host: String,
            gatewayCredential: String,
            repo: String,
        ): GatewayGitRefs =
            http.getJson(
                scheme = scheme,
                host = host,
                credential = gatewayCredential,
                "v1",
                "worktrees",
                "refs",
                query = mapOf("repo" to repo),
            )

        override suspend fun deleteWorktree(
            scheme: String,
            host: String,
            gatewayCredential: String,
            worktreeId: String,
            force: Boolean,
        ) {
            // The response's `branchDeleted` is deliberately dropped: the branch surviving
            // removal is the normal case, and the next list call shows what is left.
            http.deleteJsonUnit(
                scheme = scheme,
                host = host,
                credential = gatewayCredential,
                "v1",
                "worktrees",
                worktreeId,
                query = if (force) mapOf("force" to "true") else emptyMap(),
            )
        }

        override suspend fun resumeSession(
            scheme: String,
            host: String,
            gatewayCredential: String,
            sessionId: String,
        ): GatewaySessionResumeResponse =
            http.postJson(
                scheme = scheme,
                host = host,
                credential = gatewayCredential,
                "v1",
                "sessions",
                sessionId,
                "resume",
            )

        override suspend fun listGatewaySessions(
            scheme: String,
            host: String,
            gatewayCredential: String,
        ): List<GatewaySessionSummary> {
            val response =
                http.getJson<GatewaySessionListResponse>(
                    scheme = scheme,
                    host = host,
                    credential = gatewayCredential,
                    "v1",
                    "sessions",
                )
            // A session this client is deleting still reads "disconnected" until the DELETE lands;
            // resuming it would attach to an agent that is about to be stopped.
            return response.sessions.filterNot { it.sessionId in closingSessionIds }
        }

        /** Sessions with a DELETE in flight from this client. */
        private val closingSessionIds = ConcurrentHashMap.newKeySet<String>()

        override suspend fun closeSession(
            scheme: String,
            host: String,
            gatewayCredential: String,
            sessionId: String,
        ) {
            closingSessionIds += sessionId
            try {
                http.deleteJsonUnit(
                    scheme = scheme,
                    host = host,
                    credential = gatewayCredential,
                    "v1",
                    "sessions",
                    sessionId,
                )
            } finally {
                closingSessionIds -= sessionId
            }
        }

        override suspend fun getPushConfig(
            scheme: String,
            host: String,
            gatewayCredential: String,
        ): GatewayPushConfig =
            http.getJson(
                scheme = scheme,
                host = host,
                credential = gatewayCredential,
                "v1",
                "devices",
                "push-config",
            )

        override suspend fun registerPushSubscription(
            scheme: String,
            host: String,
            gatewayCredential: String,
            subscription: GatewayPushSubscription,
        ) {
            http.postJsonUnit(
                scheme = scheme,
                host = host,
                credential = gatewayCredential,
                "v1",
                "devices",
                "push-token",
                body = json.encodeToString(GatewayPushSubscriptionRequest(subscription)),
            )
        }

        override suspend fun restartRuntime(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            envVars: Map<String, String>,
        ): GatewayConnectResponse =
            http.postJson(
                scheme = scheme,
                host = host,
                credential = gatewayCredential,
                "v1",
                "runtimes",
                runtimeId,
                "restart",
                body = json.encodeToString(GatewayRestartRequest(env = envVars)),
            )

        override suspend fun fetchRuntimeLogs(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
        ): List<GatewayLogEntry> {
            val response =
                http.getJson<GatewayRuntimeLogsResponse>(
                    scheme = scheme,
                    host = host,
                    credential = gatewayCredential,
                    "v1",
                    "runtimes",
                    runtimeId,
                    "logs",
                )
            return response.logs
        }

        override suspend fun fetchGitStatus(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            acpSessionId: String?,
            compare: String?,
        ): GatewayGitStatus =
            http.getJson(
                scheme = scheme,
                host = host,
                credential = gatewayCredential,
                segments = arrayOf("v1", "runtimes", runtimeId, "git", "status"),
                query = compareQuery(compare) + acpSessionQuery(acpSessionId),
            )

        override suspend fun fetchGitDiff(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            path: String?,
            acpSessionId: String?,
            commit: String?,
            oldPath: String?,
            compare: String?,
        ): List<ToolCallContent.Diff> {
            val segments = arrayOf("v1", "runtimes", runtimeId, "git", "diff")
            val pathQuery = path?.takeIf { it.isNotBlank() }?.let { mapOf("path" to it) } ?: emptyMap()
            val commitQuery =
                listOfNotNull(commit?.let { "commit" to it }, oldPath?.let { "oldPath" to it }).toMap()
            val query = pathQuery + commitQuery + compareQuery(compare) + acpSessionQuery(acpSessionId)
            return if (pathQuery.isEmpty()) {
                // Whole-tree: gateway returns a JSON array of ToolCallContentDiff objects.
                http.getJson(
                    scheme = scheme,
                    host = host,
                    credential = gatewayCredential,
                    segments = segments,
                    query = query,
                )
            } else {
                // Single file: gateway returns one ToolCallContentDiff object.
                val single =
                    http.getJson<ToolCallContent.Diff>(
                        scheme = scheme,
                        host = host,
                        credential = gatewayCredential,
                        segments = segments,
                        query = query,
                    )
                listOf(single)
            }
        }

        override suspend fun fetchGitLog(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            head: String?,
            skip: Int,
            limit: Int,
            acpSessionId: String?,
            compare: String?,
            beyond: Int,
        ): GatewayGitLog =
            http.getJson(
                scheme = scheme,
                host = host,
                credential = gatewayCredential,
                segments = arrayOf("v1", "runtimes", runtimeId, "git", "log"),
                query =
                    listOfNotNull(
                        head?.let { "head" to it },
                        "skip" to skip.toString(),
                        "limit" to limit.toString(),
                    ).toMap() + compareQuery(compare) + beyondQuery(beyond) + acpSessionQuery(acpSessionId),
            )

        override suspend fun fetchGitRefs(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            acpSessionId: String?,
        ): GatewayGitRefs =
            http.getJson(
                scheme = scheme,
                host = host,
                credential = gatewayCredential,
                segments = arrayOf("v1", "runtimes", runtimeId, "git", "refs"),
                query = acpSessionQuery(acpSessionId),
            )

        override suspend fun fetchGitCommit(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            sha: String,
            acpSessionId: String?,
        ): GatewayGitCommitFiles =
            http.getJson(
                scheme = scheme,
                host = host,
                credential = gatewayCredential,
                segments = arrayOf("v1", "runtimes", runtimeId, "git", "commits", sha),
                query = acpSessionQuery(acpSessionId),
            )

        override suspend fun fetchFile(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            path: String,
            acpSessionId: String?,
        ): GatewayFileContents =
            http.getJson(
                scheme = scheme,
                host = host,
                credential = gatewayCredential,
                segments = arrayOf("v1", "runtimes", runtimeId, "files"),
                query = mapOf("path" to path) + acpSessionQuery(acpSessionId),
            )

        override suspend fun fetchFileList(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            acpSessionId: String?,
        ): GatewayFileList =
            http.getJson(
                scheme = scheme,
                host = host,
                credential = gatewayCredential,
                segments = arrayOf("v1", "runtimes", runtimeId, "files", "list"),
                query = acpSessionQuery(acpSessionId),
            )

        override suspend fun completePairing(
            scheme: String,
            host: String,
            challengeId: String,
            code: String,
            deviceName: String,
        ): GatewayPairingResult {
            val proofKey = GatewayProofAuth.generateProofKey()
            val response =
                http.postJson<GatewayPairCompleteResponse>(
                    scheme = scheme,
                    host = host,
                    credential = null,
                    "v1",
                    "pair",
                    "complete",
                    body =
                        json.encodeToString(
                            GatewayPairCompleteRequest(
                                challengeId = challengeId,
                                code = code,
                                deviceName = deviceName,
                                proofPublicKey = proofKey.publicKey,
                            ),
                        ),
                )
            return GatewayPairingResult(
                deviceId = response.deviceId,
                deviceName = response.deviceName,
                gatewayCredential = GatewayProofAuth.encodeStoredCredential(response.token, proofKey.privateKey),
                expiresAt = response.expiresAt,
                gatewayId = response.gatewayId,
            )
        }

        override suspend fun refreshCredential(
            scheme: String,
            host: String,
            gatewayCredential: String,
        ): GatewayPairingResult {
            val endpoint =
                http.buildGatewayEndpoint(
                    scheme,
                    host,
                    segments = arrayOf("v1", "auth", "refresh"),
                )
            val response =
                try {
                    http.postJson<GatewayPairCompleteResponse>(
                        scheme = scheme,
                        host = host,
                        credential = gatewayCredential,
                        "v1",
                        "auth",
                        "refresh",
                    )
                } catch (error: GatewayRequestException) {
                    // A stale proof (phone clock skew, replayed nonce) is also a 401 but says
                    // nothing about the credential; deleting the pairing over it would force a re-pair.
                    if (error.statusCode == 401 && !error.isTransientProofRejection()) {
                        // The gateway refused to refresh the credential: it has
                        // expired and is past the grace window (or legacy bearer
                        // credentials are disabled). The stored credential is
                        // dead — surface a typed error so callers can clear it
                        // and prompt re-pairing instead of failing opaquely later.
                        throw GatewayCredentialExpiredException(
                            endpoint = endpoint,
                            gatewayResponse = error.message,
                            cause = error,
                        )
                    }
                    throw error
                }
            return GatewayPairingResult(
                deviceId = response.deviceId,
                deviceName = response.deviceName,
                gatewayCredential = GatewayProofAuth.rotateStoredCredential(gatewayCredential, response.token),
                expiresAt = response.expiresAt,
                gatewayId = response.gatewayId,
            )
        }
    }

/** Request bodies must omit null/false fields regardless of the injected shared [Json] config. */
private val requestBodyJson =
    Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

/**
 * Serializes the custom agent body. Uses the same `encodeDefaults = false` instance
 * as the other request bodies, so empty args and a blank hint are omitted rather
 * than sent as explicit empties.
 */
internal fun buildCustomAgentRequestBody(
    displayName: String,
    command: String,
    args: List<String>,
    hint: String,
): String =
    requestBodyJson.encodeToString(
        GatewayCustomAgentRequest(
            displayName = displayName,
            command = command,
            args = args,
            hint = hint,
        ),
    )

/**
 * Serializes the worktree create body. A blank [base] or [branch] is sent as absent so
 * the gateway applies its own default (`HEAD` / `ferngeist/<id>`) rather than rejecting
 * an empty commit-ish or branch name.
 */
internal fun buildCreateWorktreeRequestBody(
    repo: String,
    base: String?,
    branch: String?,
): String =
    requestBodyJson.encodeToString(
        GatewayCreateWorktreeRequest(
            repo = repo,
            base = base?.trim()?.ifBlank { null },
            branch = branch?.trim()?.ifBlank { null },
        ),
    )

/**
 * Thrown when a gateway refresh fails because the stored credential has expired
 * and is no longer recoverable (past the gateway's grace window, or legacy
 * bearer credentials disabled). The credential is dead; the caller should clear
 * it and prompt the user to re-pair.
 */
class GatewayCredentialExpiredException(
    val endpoint: String,
    val gatewayResponse: String? = null,
    cause: Throwable? = null,
) : IllegalStateException(
        "The gateway credential has expired and cannot be refreshed. " +
            "Re-pair this gateway to continue. ($endpoint)",
        cause,
    )

private fun GatewayRequestException.isTransientProofRejection(): Boolean =
    parseGatewayErrorBody(responseBody.orEmpty().trim()).isTransientProofRejection(responseBody)

private fun acpSessionQuery(acpSessionId: String?): Map<String, String> =
    acpSessionId?.takeIf { it.isNotBlank() }?.let { mapOf("acpSessionId" to it) } ?: emptyMap()

private fun compareQuery(compare: String?): Map<String, String> =
    compare?.takeIf { it.isNotBlank() }?.let { mapOf("compare" to it) } ?: emptyMap()

private fun beyondQuery(beyond: Int): Map<String, String> =
    if (beyond > 0) mapOf("beyond" to beyond.toString()) else emptyMap()

/** Thrown for any non-success gateway API response; carries the HTTP status code. */
class GatewayRequestException(
    val statusCode: Int,
    override val message: String,
    /** The raw response body, so callers can read the gateway's `{"error": "..."}` envelope. */
    val responseBody: String? = null,
    /** Machine-readable auth rejection code (`proof_expired`, ...), when the gateway sent one. */
    val code: String? = null,
) : IllegalStateException(message)

internal fun gatewayRequestException(
    statusCode: Int,
    statusDescription: String,
    endpoint: String,
    responseBody: String,
): GatewayRequestException {
    val normalizedBody = responseBody.trim()
    val statusLine = "$statusCode ${statusDescription.ifBlank { "unknown" }}".trim()
    val message =
        when (statusCode) {
            404 -> {
                buildString {
                    append("Gateway request failed: ")
                    append(statusLine)
                    append(" at ")
                    append(endpoint)
                    append(". The host is reachable, but this port is not serving the Ferngeist Gateway API.")
                    if (normalizedBody.isNotBlank()) {
                        append(" Response: ")
                        append(normalizedBody)
                    }
                }
            }
            else -> {
                // Prefer the gateway's own error text: a failed connect carries the
                // agent's exit error there, and callers render this message verbatim.
                val detail =
                    gatewayErrorMessage(normalizedBody)
                        ?: normalizedBody.takeIf { it.isNotBlank() }
                buildString {
                    append("Gateway request failed: ")
                    append(statusLine)
                    append(" at ")
                    append(endpoint)
                    if (detail != null) {
                        append(". ")
                        append(detail)
                    }
                }
            }
        }
    return GatewayRequestException(
        statusCode = statusCode,
        message = message,
        responseBody = normalizedBody.takeIf { it.isNotBlank() },
        code = parseGatewayErrorBody(normalizedBody)?.code,
    )
}
