package com.tamimarafat.ferngeist.gateway

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GatewayStatus(
    val name: String,
    val version: String,
    @SerialName("protocolVersion")
    val protocolVersion: String? = null,
    // RFC 3339 UTC clock of the gateway, whenever it answered. The client learns its
    // device-clock offset from it (see GatewayClock); absent on older gateways.
    val serverTime: String? = null,
    val remote: GatewayRemoteStatus,
)

@Serializable
data class GatewayRemoteStatus(
    val configured: Boolean,
    val mode: String? = null,
    val scope: String? = null,
    val healthy: Boolean = true,
    val warning: String? = null,
)

@Serializable
data class GatewayPairCompleteRequest(
    @SerialName("challengeId") val challengeId: String,
    val code: String,
    @SerialName("deviceName") val deviceName: String,
    @SerialName("proofPublicKey") val proofPublicKey: String? = null,
)

@Serializable
data class GatewayPairCompleteResponse(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("deviceName") val deviceName: String,
    val token: String,
    @SerialName("expiresAt") val expiresAt: String,
    // Stable, gateway-owned identifier for this gateway. Push payloads reference it as
    // their `serverId`; the client maps it back to the local GatewaySource.id to deep-link.
    // Nullable for back-compat with gateways that predate the field.
    @SerialName("gatewayId") val gatewayId: String? = null,
)

data class GatewayPairingResult(
    val deviceId: String,
    val deviceName: String,
    val gatewayCredential: String,
    val expiresAt: String,
    val gatewayId: String? = null,
)

@Serializable
data class GatewayAgentsResponse(
    val agents: List<GatewayAgent>,
)

@Serializable
data class GatewayAgent(
    val id: String,
    val displayName: String,
    val detected: Boolean,
    val manifestValid: Boolean,
    val security: GatewayAgentSecurity,
    val validationError: String? = null,
    val hint: String? = null,
    val running: Boolean = false,
    val runtimeId: String? = null,
    val runtimeStatus: String? = null,
    // "embedded" | "registry" | "custom"; null on gateways that predate custom agents.
    val source: String? = null,
    // Registry logo URL under `registry.icon`; absent for embedded/custom agents and on
    // gateways that predate icon exposure.
    val registry: GatewayAgentRegistry? = null,
)

@Serializable
data class GatewayAgentRegistry(
    val icon: String? = null,
)

@Serializable
data class GatewayAgentSecurity(
    val allowsRemoteStart: Boolean,
)

/**
 * Response of `POST /v1/agents/{id}/connect` and `POST /v1/runtimes/{id}/restart`
 * (protocol v2): the gateway launches a fresh agent process and binds it to a new
 * session in one call, so a restart hands back a different [runtimeId]. Auth uses
 * the device credential; [attachToken] authorises the ACP WebSocket attach.
 */
@Serializable
data class GatewayConnectResponse(
    val runtimeId: String,
    @SerialName("sessionId")
    val sessionId: String,
    @SerialName("attachToken")
    val attachToken: String,
    @SerialName("websocketUrl")
    val webSocketUrl: String,
)

@Serializable
data class GatewayRestartRequest(
    val env: Map<String, String>,
)

@Serializable
data class GatewayRuntimeLogsResponse(
    val runtimeId: String,
    val logs: List<GatewayLogEntry>,
)

@Serializable
data class GatewayLogEntry(
    val timestamp: String,
    val stream: String,
    val message: String,
)

@Serializable
data class GatewayPairStartResponse(
    @SerialName("challengeId") val challengeId: String,
    @SerialName("expiresAt") val expiresAt: String,
)

/** Body of `POST /v1/agents/custom`. */
@Serializable
data class GatewayCustomAgentRequest(
    val displayName: String,
    val command: String,
    val args: List<String> = emptyList(),
    val hint: String = "",
)

@Serializable
data class GatewaySessionResumeResponse(
    @SerialName("attachToken") val attachToken: String,
)

/** What the client needs to subscribe for this gateway's pushes. */
@Serializable
data class GatewayPushConfig(
    // Base64url, uncompressed P-256 VAPID key the Web Push subscription must be created with.
    @SerialName("vapidPublicKey") val vapidPublicKey: String,
)

/** A Web Push subscription, in the browser `PushSubscription.toJSON()` shape the gateway takes. */
@Serializable
data class GatewayPushSubscription(
    val endpoint: String,
    val keys: Keys,
) {
    @Serializable
    data class Keys(
        val p256dh: String,
        val auth: String,
    )
}

@Serializable
data class GatewayPushSubscriptionRequest(
    val subscription: GatewayPushSubscription,
    // Always emit platform: it is the gateway's routing key for the delivery provider.
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val platform: String = "webpush",
)

@Serializable
data class GatewaySessionListResponse(
    val sessions: List<GatewaySessionSummary>,
)

@Serializable
data class GatewaySessionSummary(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("runtimeId") val runtimeId: String,
    @SerialName("agentId") val agentId: String,
    val status: String,
    @SerialName("createdAt") val createdAt: String? = null,
) {
    /**
     * Whether the gateway will still resume this session.
     *
     * Mirrors the gateway's own `RuntimeSession.Resume` check: only an `active` or
     * `disconnected` session still owns a live agent process. Anything else
     * (`failed`, `closing`) has a dead runtime behind it, so attaching to its
     * `runtimeId` fails on connect.
     */
    val isResumable: Boolean
        get() = status == STATUS_ACTIVE || status == STATUS_DISCONNECTED

    private companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_DISCONNECTED = "disconnected"
    }
}

@Serializable
data class GatewayChangedFile(
    val path: String,
    val status: String,
    val added: Int = 0,
    val removed: Int = 0,
    val binary: Boolean = false,
    /**
     * True when the entry is a directory rather than a file: a submodule
     * (gitlink) or a collapsed untracked directory. The gateway computes this
     * from `git status --porcelain=v2` (submodule `S` worktree status / trailing
     * slash) because the client cannot distinguish a submodule from a file
     * from the path alone. Directory entries are not diffable — tapping one
     * must not call the diff endpoint (which 422s on a gitlink).
     */
    val isDir: Boolean = false,
    /** The pre-rename path, set for renames and copies in a commit's file list. */
    val oldPath: String? = null,
    /** True when the change is in the index; an older gateway omits it, so every file reads as unstaged. */
    val staged: Boolean = false,
)

@Serializable
data class GatewayGitStatus(
    val branch: String = "",
    val ahead: Int = 0,
    val behind: Int = 0,
    /**
     * The changed files. Nullable because the gateway marshals a clean tree's nil
     * Go slice as `null`, which a non-nullable list refuses to decode — and a failed
     * decode reads as "no git status at all" in the chat. The mapper empties it.
     */
    val changed: List<GatewayChangedFile>? = null,
    /** The `compare=` the gateway honoured, verbatim; absent when none was requested or the gateway ignored it. */
    val compare: String? = null,
)

/** `GET /git/refs`: the compare picker's entries. [default] is `""` when the gateway cannot name one. */
@Serializable
data class GatewayGitRefs(
    val default: String = "",
    val refs: List<GatewayGitRef> = emptyList(),
)

/**
 * One page of `GET /git/log`. [head] is `""` for an empty repository; [remoteUrl] is `""` without
 * `origin`. [baseCommit] is `""` when the workspace has no base to read against.
 */
@Serializable
data class GatewayGitLog(
    val head: String = "",
    val hasMore: Boolean = false,
    val remoteUrl: String = "",
    /** The whole walk's size; null when the gateway predates the field. */
    val total: Int? = null,
    val commits: List<GatewayGitCommit> = emptyList(),
    /** The commit the workspace is read against, so the app can mark the branch's own commits. */
    @SerialName("baseCommit") val baseCommit: String = "",
)

/** A commit of `GET /git/log`; times are Unix seconds. */
@Serializable
data class GatewayGitCommit(
    val sha: String,
    val parents: List<String> = emptyList(),
    val subject: String = "",
    val body: String = "",
    val authorName: String = "",
    val authorEmail: String = "",
    val authorTime: Long = 0,
    /** 0 from a gateway that predates it. */
    val commitTime: Long = 0,
    val pushed: Boolean = false,
    val refs: List<GatewayGitRef> = emptyList(),
)

/** [kind] is `branch`, `remote`, `tag`, or `head` (detached). */
@Serializable
data class GatewayGitRef(
    val name: String,
    val kind: String,
    val isHead: Boolean = false,
)

/** `GET /git/commits/{sha}`: what the commit changed, against its first parent. */
@Serializable
data class GatewayGitCommitFiles(
    val sha: String,
    val changed: List<GatewayChangedFile> = emptyList(),
)

/**
 * `GET /files`: ACP's `TextResourceContents` or `BlobResourceContents` in one shape, [text] set for
 * a text file and [blob] for a binary one. [truncated] (past 1 MiB) is a gateway extension.
 */
@Serializable
data class GatewayFileContents(
    val text: String? = null,
    val blob: String? = null,
    val truncated: Boolean = false,
)

/** `GET /files/list`: the project's files, relative to its directory, cut at the gateway's cap when [truncated]. */
@Serializable
data class GatewayFileList(
    val files: List<String> = emptyList(),
    val truncated: Boolean = false,
)

/** Body of `POST /v1/worktrees`. Null [base]/[branch] let the gateway apply its own defaults. */
@Serializable
data class GatewayCreateWorktreeRequest(
    val repo: String,
    val base: String? = null,
    val branch: String? = null,
)

/** Body of `POST /v1/paths/missing`. */
@Serializable
data class GatewayPathsRequest(
    val paths: List<String>,
)

/** `POST /v1/paths/missing`: the requested paths that are gone from disk. */
@Serializable
data class GatewayMissingPaths(
    val missing: List<String> = emptyList(),
)

/**
 * A git worktree, made by the gateway or by anything else. [path] is what a chat opened in it
 * uses as its cwd. [branch] is empty on a detached `HEAD`.
 *
 * [ahead], [behind] and [dirty] are only on the list response, and are null when git failed for
 * that entry.
 */
@Serializable
data class GatewayWorktree(
    val id: String,
    val repo: String,
    val path: String,
    val branch: String,
    @SerialName("baseCommit") val baseCommit: String,
    val createdAt: String,
    val ahead: Int? = null,
    val behind: Int? = null,
    val dirty: Boolean? = null,
)

/**
 * The gateway-to-client protocol version this client is built against. The gateway
 * reports its own protocol version in [GatewayStatus.protocolVersion]; a mismatch
 * means the two sides may not agree on the API surface (endpoints, payload shapes),
 * so the client surfaces a clear error instead of failing opaquely on a 404/422.
 */
object GatewayProtocol {
    /** The current protocol version (see gateway `internal/api/server.go`). */
    const val CURRENT = "v2"

    /**
     * All gateway protocol versions this client can talk to. The gateway is v2-only:
     * it no longer serves the removed start/connect-runtime endpoints, so a v1
     * gateway must fail the version check instead of failing opaquely later.
     */
    val SUPPORTED: Set<String> = setOf(CURRENT)
}

/** Thrown when a gateway's reported protocol version is incompatible with this client. */
class GatewayProtocolMismatchException(
    val gatewayVersion: String?,
    val supportedVersions: Set<String> = GatewayProtocol.SUPPORTED,
) : IllegalStateException(
        "This gateway uses protocol version \"$gatewayVersion\", but this app supports " +
            supportedVersions.joinToString(", ") { "\"$it\"" } +
            ". Update the gateway daemon to continue.",
    )

/**
 * Validates a gateway status against the supported protocol versions, throwing
 * [GatewayProtocolMismatchException] on mismatch. A gateway that omits the field
 * (older build) is treated as a mismatch rather than silently assumed compatible.
 */
fun GatewayStatus.requireSupportedProtocol() {
    if (protocolVersion !in GatewayProtocol.SUPPORTED) {
        throw GatewayProtocolMismatchException(protocolVersion)
    }
}

/** The gateway's error envelope: every non-2xx response body is `{"error": "..."}`. */
@Serializable
data class GatewayErrorResponse(
    val error: String? = null,
    // Machine-readable rejection code (see GatewayErrorCode). The gateway sets it only
    // on credential/proof/scope rejections, and omits it on older builds; every other
    // failure is code-less so it cannot be mistaken for an auth rejection.
    val code: String? = null,
    // RFC 3339 UTC clock of the gateway at the moment it composed the rejection, when
    // the gateway exposes one; the client corrects its device clock from it.
    val serverTime: String? = null,
)

/** Machine-readable auth-gate rejection codes the gateway returns in the envelope's `code`. */
internal object GatewayErrorCode {
    /** The signed timestamp fell outside the gateway's ±5-minute window. */
    const val PROOF_EXPIRED = "proof_expired"

    /** The nonce was already used; a fresh one generally redeems it. */
    const val PROOF_REPLAYED = "proof_replayed"
}

private val errorBodyJson = Json { ignoreUnknownKeys = true }

/**
 * The error envelope parsed out of a non-2xx body, or null when the body is not one
 * (plain strings, HTML error pages, empty bodies, ...). The gateway only fills `code`
 * and `serverTime` on auth rejections; all other `GatewayErrorResponse` fields stay null.
 */
internal fun parseGatewayErrorBody(responseBody: String?): GatewayErrorResponse? =
    responseBody
        ?.takeIf { it.isNotBlank() }
        ?.let { body ->
            runCatching { errorBodyJson.decodeFromString<GatewayErrorResponse>(body) }.getOrNull()
        }

/**
 * Whether this auth rejection is the ephemeral kind a re-signed retry can still fix: a
 * skewed clock (`proof_expired`) or a replayed nonce (`proof_replayed`), the opposite
 * of a dead credential that no re-signing helps. When the response carries no `code`
 * (gateway predates the field), falls back to the wording the envelope carried.
 */
internal fun GatewayErrorResponse?.isTransientProofRejection(fallbackBody: String?): Boolean =
    when (this?.code) {
        GatewayErrorCode.PROOF_EXPIRED, GatewayErrorCode.PROOF_REPLAYED -> true
        null -> fallbackBody.orEmpty().let { "proof expired" in it || "proof replayed" in it }
        else -> false
    }

/**
 * The gateway's own message from a non-2xx body, or null when it is not the envelope.
 *
 * Public rather than module-internal: the catalog/chat features read the gateway's
 * refused-delete body, and [GatewayRequestException] carries only the raw text.
 */
fun gatewayErrorMessage(responseBody: String?): String? =
    parseGatewayErrorBody(responseBody)?.error?.takeIf { it.isNotBlank() }
