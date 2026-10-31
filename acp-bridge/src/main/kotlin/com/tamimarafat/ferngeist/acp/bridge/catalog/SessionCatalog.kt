package com.tamimarafat.ferngeist.acp.bridge.catalog

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AgentCapabilities
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthMethodInfo
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticateResult
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticationRequiredException
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnection
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AuthEnvVarInfo
import com.tamimarafat.ferngeist.acp.bridge.connection.LaunchRepositories
import com.tamimarafat.ferngeist.acp.bridge.connection.buildEnvPayload
import com.tamimarafat.ferngeist.acp.bridge.connection.buildGatewayLaunchContext
import com.tamimarafat.ferngeist.acp.bridge.connection.formatAcpErrorMessage
import com.tamimarafat.ferngeist.acp.bridge.connection.loadPersistedEnvValues
import com.tamimarafat.ferngeist.acp.bridge.connection.persistEnvValues
import com.tamimarafat.ferngeist.acp.bridge.connection.toConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.facade.OpenStepTimer
import com.tamimarafat.ferngeist.acp.bridge.facade.mapDiagnostics
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.acp.bridge.hub.GatewayEndpoint
import com.tamimarafat.ferngeist.acp.bridge.hub.chatIdFor
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.ChatCatalog
import com.tamimarafat.ferngeist.core.model.store.AuthEnvValueStore
import com.tamimarafat.ferngeist.gateway.GatewayConnectResponse
import com.tamimarafat.ferngeist.gateway.GatewayCredentialExpiredException
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Bounds the lease-reconciliation list call so a slow gateway cannot stall a refresh. */
private const val RUNNING_SESSIONS_TIMEOUT_MS = 10_000L

/**
 * How long a finished listing still answers a [SessionCatalog.listSessions] that asks for reuse:
 * the server list's launch lists before it navigates, and the session screen it opens must not
 * run the whole launch again for the same rows.
 */
private const val REUSE_LISTING_MS = 15_000L
private const val NANOS_PER_MS = 1_000_000L

/**
 * Session listing for one server, and the listing transport that carries it.
 *
 * Owns the transient browser (listing) transport per server, plus the transport that
 * raised an outstanding listing auth challenge, and the snapshot writes a
 * listing produces. Sessions are listed over a warm chat transport when one holds the
 * gateway slot, else over the browser transport (connect → list → hang up, so an idle
 * socket never steals the gateway's single-attach slot).
 *
 * Also the feature-facing entry point for the session list: close, delete, the gateway's
 * running sessions, capability/diagnostic snapshots and the auth helpers. It reaches the hub
 * only for what the hub owns — pooling, presence, tracked-chat teardown — so a feature
 * never touches a transport.
 *
 * Carries more functions than detekt's TooManyFunctions budget: one entry point per
 * session-list operation, each a single transport leg. Suppressed rather than merged.
 */
@Suppress("TooManyFunctions")
class SessionCatalog(
    private val hub: ChatConnectionHub,
    private val repositories: LaunchRepositories,
    private val sessionRepository: ChatCatalog,
    private val authEnvValueStore: AuthEnvValueStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Outcome of a session-list listing attempt. */
    sealed interface ListSessionsResult {
        /**
         * Listing succeeded through a warm chat transport ([viaWarmChat]) or the
         * catalog's own browser transport; the listing snapshot has been replaced.
         */
        data class Listed(
            val sessions: List<SessionSummary>,
            val agentCapabilities: AgentCapabilities?,
            val viaWarmChat: Boolean,
        ) : ListSessionsResult

        /**
         * The transport advertised no session-list capability; nothing was
         * listed or written. Screens keep the old list and show the
         * "not supported" empty state.
         */
        data class Unsupported(
            val agentCapabilities: AgentCapabilities?,
        ) : ListSessionsResult

        /** The transport raised an auth challenge; screens keep the auth UX. */
        data class AuthRequired(
            val agentName: String,
            val authMethods: List<CatalogAuthMethod>,
            val challengeMessage: String?,
            val gatewayRuntimeId: String?,
            val viaWarmChat: Boolean,
        ) : ListSessionsResult

        /** Listing failed for a non-auth reason (connect, launch). */
        data class Failed(
            val message: String,
        ) : ListSessionsResult
    }

    /** Outcome of [authenticateListing]. */
    sealed interface ListingAuthentication {
        data object Succeeded : ListingAuthentication

        data class Failed(
            val message: String,
        ) : ListingAuthentication
    }

    /**
     * Reusable browser (listing) transport per server, plus the transport that
     * surfaced an outstanding listing auth challenge. Kept disconnected after
     * each list; torn down by [releaseListing].
     */
    private data class ListingSession(
        val browserTransport: AcpConnection,
        var authTransport: AcpConnection? = null,
    )

    private val listingSessions = LinkedHashMap<String, ListingSession>()

    /** The newest whole-directory listing per server, with the [System.nanoTime] it finished at. */
    private val recentListings = ConcurrentHashMap<String, Pair<ListSessionsResult, Long>>()

    /**
     * Network availability, so the session list can re-run a listing that failed
     * while the device was offline.
     */
    val networkAvailable: Flow<Boolean> = hub.networkAvailable

    /** Servers holding at least one connected chat transport. */
    val warmServers: StateFlow<Set<String>> get() = hub.warmServers

    /** Cold flow of connected session ids tracked on [serverId]. */
    fun connectedSessionIds(serverId: String): Flow<Set<String>> = hub.connectedSessionIds(serverId)

    /** True when the tracked chat for this server/session pair is still responding. */
    fun isStreaming(
        serverId: String,
        sessionId: String,
    ): Boolean = hub.isStreaming(serverId, sessionId)

    /**
     * Lists sessions for [serverId], replacing its listing snapshot on
     * success. Borrows a warm chat transport when one holds the gateway slot;
     * otherwise runs a transient browser transport (connect → list → hang up).
     * Auth challenges surface as [ListSessionsResult.AuthRequired] with the
     * raising transport retained for [authenticateListing] routing.
     */
    @OptIn(UnstableApi::class)
    suspend fun listSessions(
        serverId: String,
        cwd: String?,
        reuseRecent: Boolean = false,
    ): ListSessionsResult {
        if (reuseRecent && cwd == null) recentListing(serverId)?.let { return it }
        val target =
            repositories.launchableTargetRepository.getTarget(serverId)
                ?: return ListSessionsResult.Failed(
                    // Not necessarily "removed": a stored credential this device can no longer
                    // decrypt surfaces here as a missing target too, so name only what is known.
                    "This server is no longer available to the app. Add it again from the server list.",
                )
        val listing = listingSession(serverId)
        val warm = hub.warmManagerFor(serverId)
        val timer = OpenStepTimer()
        val result =
            try {
                if (warm != null) {
                    listViaWarmListing(listing, warm, serverId, cwd)
                } else {
                    listViaBrowserListing(listing, target, serverId, cwd, timer)
                }
            } finally {
                listing.browserTransport.recordTiming("list", timer.report(sessionId = serverId, label = "list"))
            }
        if (cwd == null) rememberListing(serverId, result)
        return result
    }

    /**
     * Records a whole-directory listing the caller just took over a verification connection it
     * already initialized, so the launch that opened it is also the listing. Best-effort: a failure
     * or an auth challenge leaves the session screen to list (and surface it) itself.
     */
    @Suppress("TooGenericExceptionCaught")
    @OptIn(UnstableApi::class)
    suspend fun adoptListing(
        serverId: String,
        transport: AcpConnection,
    ) {
        val caps = transport.agentCapabilities.value
        val result =
            if (caps != null && caps.sessionCapabilities.list == null) {
                ListSessionsResult.Unsupported(caps)
            } else {
                try {
                    val sessions = transport.listSessions(cwd = null)
                    sessionRepository.recordListing(serverId, sessions)
                    ListSessionsResult.Listed(sessions = sessions, agentCapabilities = caps, viaWarmChat = false)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    return
                }
            }
        rememberListing(serverId, result)
    }

    private fun rememberListing(
        serverId: String,
        result: ListSessionsResult,
    ) {
        if (result is ListSessionsResult.Listed || result is ListSessionsResult.Unsupported) {
            recentListings[serverId] = result to System.nanoTime()
        }
    }

    private fun recentListing(serverId: String): ListSessionsResult? {
        val (result, finishedAt) = recentListings[serverId] ?: return null
        return result.takeIf { System.nanoTime() - finishedAt < REUSE_LISTING_MS * NANOS_PER_MS }
    }

    /**
     * Routes an auth-method selection to the transport that raised the
     * outstanding challenge for [serverId] (a warm chat transport when the
     * listing borrowed one, else the catalog's browser transport).
     */
    suspend fun authenticateListing(
        serverId: String,
        methodId: String,
    ): ListingAuthentication {
        val transport =
            listingSessions[serverId]?.authTransport
                ?: hub.warmManagerFor(serverId)
                ?: return ListingAuthentication.Failed(
                    "Authentication transport is no longer available. Please try again.",
                )
        return when (val result = transport.authenticate(methodId)) {
            is AcpAuthenticateResult.Failure -> ListingAuthentication.Failed(result.message)

            is AcpAuthenticateResult.Success -> {
                listingSessions[serverId]?.let { it.authTransport = null }
                ListingAuthentication.Succeeded
            }
        }
    }

    /**
     * Drops the retained browser transport for [serverId] and clears any
     * outstanding auth routing. Call from the listing screen's onCleared.
     * Never tears down a warm chat transport (the chat owns it).
     */
    fun releaseListing(serverId: String) {
        val listing = listingSessions.remove(serverId) ?: return
        // Only the browser transport is catalog-owned here — a warm chat transport
        // recorded as authTransport belongs to its chat entry.
        hub.releaseBrowserTransport(listing.browserTransport)
    }

    /**
     * Reconnects the retained listing transport for [serverId] to a manual
     * server's own transport config, after the user restarted it with the env
     * vars ACP asked for. Returns false when the connection attempt failed.
     */
    suspend fun reconnectListingToManual(
        serverId: String,
        server: LaunchableTarget.Manual,
    ): Boolean = reconnectListing(serverId) { server.toConnectionConfig() }

    /**
     * Reconnects the retained listing transport for [serverId] to the new runtime
     * `POST /v1/runtimes/{id}/restart` handed back. Returns false when the attempt failed.
     */
    suspend fun reconnectListingToGateway(
        serverId: String,
        gatewaySource: GatewaySource,
        restart: GatewayConnectResponse,
        authMethodId: String,
        displayName: String,
    ): Boolean =
        reconnectListing(serverId) {
            AcpConnectionConfig.fromGatewayHandoff(
                gatewaySource = gatewaySource,
                handoff = restart,
                authMethodId = authMethodId,
                displayName = displayName,
            )
        }

    /**
     * Runs the ACP initialize handshake on the retained listing transport.
     * Returns false when there is no transport or the handshake failed.
     */
    suspend fun initializeListing(serverId: String): Boolean {
        val transport = retainedListingTransport(serverId) ?: return false
        return withContext(ioDispatcher) {
            transport.initialize()
        } != null
    }

    /**
     * Closes [sessionId] on [serverId]: hub-tracked chats go through the
     * tracked close (REST DELETE + transport teardown); cold snapshot-known
     * sessions close the gateway session directly. Clears the recorded
     * gateway session id in both cases.
     *
     * Returns true when a tracked chat was closed or a known gateway session
     * was deleted, false when nothing was left to close (untracked session the
     * catalog does not know, an already-cleared gateway session id, or a
     * non-gateway target, which has no remote leg) so the caller can surface
     * that instead of failing silently.
     */
    suspend fun closeSession(
        serverId: String,
        sessionId: String,
    ): Boolean {
        val endpoint = gatewayEndpoint(serverId) ?: return false
        return hub.closeSession(serverId, sessionId, endpoint)
    }

    /**
     * Delete: ask the agent to forget the session via ACP
     * `session/delete` (only when it advertises `sessionCapabilities.delete`),
     * then run the [closeSession] path so a live gateway process stops, then
     * suppress the chat in the catalog. Every failed transport leg propagates so
     * a rejected deletion leaves the chat visible. Gateway DELETE propagates too, so
     * a leaked process slot stays visible. Returns false only when nothing in
     * the app knew the session.
     */
    suspend fun deleteSession(
        serverId: String,
        sessionId: String,
    ): Boolean {
        val known =
            hub.isTracked(serverId, sessionId) ||
                sessionRepository.getSession(serverId, sessionId) != null
        if (!known) return false
        recentListings.remove(serverId)
        deleteOnAgent(serverId, sessionId)
        val endpoint = gatewayEndpoint(serverId)
        when {
            endpoint != null -> hub.closeSession(serverId, sessionId, endpoint)
            hub.isTracked(serverId, sessionId) -> hub.close(chatIdFor(serverId, sessionId), endpoint = null)
        }
        sessionRepository.deleteSession(serverId, sessionId)
        return known
    }

    /**
     * ACP session ids a gateway process still runs for [serverId]'s agent, so the session list
     * can offer Close for chats this phone holds no connection for. Null for a direct agent or
     * when the gateway cannot be asked; empty from a gateway that predates `acpSessionIds`.
     */
    suspend fun runningSessionIds(serverId: String): Set<String>? {
        val target = repositories.launchableTargetRepository.getTarget(serverId) as? LaunchableTarget.GatewayAgent
        val endpoint = gatewayEndpoint(serverId)
        if (target == null || endpoint == null) return null
        return try {
            withTimeout(RUNNING_SESSIONS_TIMEOUT_MS) {
                repositories.gatewayRepository
                    .listGatewaySessions(endpoint.scheme, endpoint.host, endpoint.credential)
                    .filter { it.agentId == target.binding.agentId }
                    .flatMap { it.acpSessionIds }
                    .toSet()
            }
        } catch (_: TimeoutCancellationException) {
            // A slow gateway must not cancel the refresh this runs inside.
            null
        } catch (_: GatewayRequestException) {
            null
        } catch (_: GatewayCredentialExpiredException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Loads the env values this device stored for any of [authMethods].
     * The store write/read helpers live in the bridge; features only see them here.
     */
    suspend fun persistedEnvValues(
        targetId: String,
        authMethods: List<CatalogAuthMethod>,
    ): Map<String, String> = loadPersistedEnvValues(authEnvValueStore, targetId, authMethods.map { it.toAcpMethod() })

    /** Persists [values] for [method]'s declared env var names. */
    suspend fun persistEnvValues(
        targetId: String,
        method: CatalogAuthMethod,
        values: Map<String, String>,
    ) {
        persistEnvValues(authEnvValueStore, targetId, method.toAcpMethod(), values)
    }

    /**
     * Builds the env var payload for a gateway restart, keeping only values the
     * auth method declares (or non-optional blanks so the runtime reports them).
     */
    fun envPayload(
        method: CatalogAuthMethod,
        values: Map<String, String>,
    ): Map<String, String> = buildEnvPayload(method.toAcpMethod(), values)

    /**
     * Agent capabilities observed by the last listing for [serverId], falling
     * back to the capabilities of a chat transport tracked for it.
     */
    fun agentCapabilities(serverId: String): AgentCapabilities? =
        listingSessions[serverId]
            ?.browserTransport
            ?.agentCapabilities
            ?.value
            ?: hub.trackedManagerFor(serverId)?.agentCapabilities?.value

    /** Live diagnostics of the listing transport for [serverId]; null while it holds no transport. */
    fun listingDiagnostics(serverId: String): ChatConnectionDiagnostics? =
        listingSessions[serverId]
            ?.browserTransport
            ?.diagnostics
            ?.value
            ?.let(::mapDiagnostics)

    /**
     * Lists through [warm], a hub-tracked chat transport owning the gateway
     * slot. Never disconnects it — the chat owns it.
     */
    @Suppress("TooGenericExceptionCaught")
    @OptIn(UnstableApi::class)
    private suspend fun listViaWarmListing(
        listing: ListingSession,
        warm: AcpConnection,
        serverId: String,
        cwd: String?,
    ): ListSessionsResult {
        val caps = warm.agentCapabilities.value
        if (caps != null && caps.sessionCapabilities.list == null) {
            return ListSessionsResult.Unsupported(caps)
        }
        return try {
            val sessions = warm.listSessions(cwd = cwd)
            sessionRepository.recordListing(serverId, sessions)
            ListSessionsResult.Listed(sessions = sessions, agentCapabilities = caps, viaWarmChat = true)
        } catch (error: AcpAuthenticationRequiredException) {
            listing.authTransport = warm
            authRequiredResult(warm, viaWarmChat = true, error = error)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            ListSessionsResult.Failed(formatAcpErrorMessage(error, "Failed to load sessions"))
        }
    }

    /**
     * Lists over the catalog-owned browser transport, connecting on demand and
     * hanging up afterwards. On auth the socket stays up for the follow-up
     * [authenticateListing] call.
     */
    @OptIn(UnstableApi::class)
    private suspend fun listViaBrowserListing(
        listing: ListingSession,
        target: LaunchableTarget,
        serverId: String,
        cwd: String?,
        timer: OpenStepTimer,
    ): ListSessionsResult {
        val transport = listing.browserTransport
        val capabilities = transport.agentCapabilities.value
        if (capabilities != null && capabilities.sessionCapabilities.list == null) {
            return ListSessionsResult.Unsupported(capabilities)
        }
        val connected =
            if (transport.isConnected) {
                true
            } else {
                val config =
                    buildListingConfig(target).getOrElse { error ->
                        return ListSessionsResult.Failed(
                            formatAcpErrorMessage(error, "Failed to launch ${target.name}"),
                        )
                    }
                try {
                    withContext(ioDispatcher) {
                        transport.connectAndInitializeWithoutReconnect(config)
                    } != null
                } catch (error: CancellationException) {
                    // Cancellation can land after the socket opened but before the
                    // connect+initialize returned, stranding a connected transport;
                    // release it before rethrowing.
                    hangUpListing(listing)
                    throw error
                }
            }
        timer.mark("connect")
        if (!connected) return failedAttach(listing, target)
        val result = listConnected(listing, serverId, cwd, timer, capabilities)
        if (result !is ListSessionsResult.AuthRequired) {
            // Idle browser socket's reconnect would steal the gateway's
            // single-attach slot from a chat opened next.
            hangUpListing(listing)
        }
        return result
    }

    /** Lists over the connected browser transport of [listing]; failures come back as results. */
    @Suppress("TooGenericExceptionCaught")
    @OptIn(UnstableApi::class)
    private suspend fun listConnected(
        listing: ListingSession,
        serverId: String,
        cwd: String?,
        timer: OpenStepTimer,
        connectedCapabilities: AgentCapabilities?,
    ): ListSessionsResult {
        val transport = listing.browserTransport
        return try {
            val sessions = transport.listSessions(cwd = cwd)
            sessionRepository.recordListing(serverId, sessions)
            timer.mark("list")
            ListSessionsResult.Listed(
                sessions = sessions,
                agentCapabilities = transport.agentCapabilities.value ?: connectedCapabilities,
                viaWarmChat = false,
            )
        } catch (error: AcpAuthenticationRequiredException) {
            // Keep the browser transport connected: the follow-up
            // authenticateListing runs on this same socket.
            listing.authTransport = transport
            authRequiredResult(transport, viaWarmChat = false, error = error)
        } catch (error: Throwable) {
            if (error is CancellationException) {
                // A cancelled listing still releases the browser transport: an
                // idle connected socket holds the gateway's single-attach slot.
                hangUpListing(listing)
                throw error
            }
            ListSessionsResult.Failed(formatAcpErrorMessage(error, "Failed to load sessions"))
        }
    }

    /** A failed attach still leaves the session the gateway just launched, so it is hung up first. */
    private suspend fun failedAttach(
        listing: ListingSession,
        target: LaunchableTarget,
    ): ListSessionsResult.Failed {
        hangUpListing(listing)
        return ListSessionsResult.Failed(
            listing.browserTransport.diagnostics.value.recentErrors
                .lastOrNull { it.source == "connect" || it.source == "connection" }
                ?.message
                ?: "Failed to connect to ${target.name}. Check your connection and try again.",
        )
    }

    /** Disconnects the browser transport of [listing] (kept for reuse, not closed). */
    private suspend fun hangUpListing(listing: ListingSession) {
        // NonCancellable: called from CancellationException catches, where a
        // plain IO hop would re-cancel before disconnect() runs.
        withContext(NonCancellable + ioDispatcher) { hub.hangUpBrowserTransport(listing.browserTransport) }
    }

    /**
     * Disconnects the retained listing/auth transport for [serverId], then
     * connects it to the config [configFor] builds. Part of the auth recovery
     * flows (manual env-var reconnect, gateway env-var restart), which
     * reconnect the raising transport with a new config.
     */
    private suspend fun reconnectListing(
        serverId: String,
        configFor: () -> AcpConnectionConfig,
    ): Boolean {
        val transport = retainedListingTransport(serverId) ?: return false
        withContext(ioDispatcher) { transport.disconnect() }
        return withContext(ioDispatcher) { transport.connect(configFor()) }
    }

    /**
     * Sends `session/delete` over any usable transport for [serverId]: the
     * warm chat transport when one is connected, else the browser transport,
     * connected on demand and hung up afterwards. Unsupported calls and rejections
     * propagate before the catalog changes. Cancellation always propagates.
     */
    @OptIn(UnstableApi::class)
    private suspend fun deleteOnAgent(
        serverId: String,
        sessionId: String,
    ) {
        val warm = hub.warmManagerFor(serverId)
        if (warm != null) {
            checkNotNull(
                warm.agentCapabilities.value
                    ?.sessionCapabilities
                    ?.delete,
            ) { "Session deletion is unsupported." }
            check(warm.deleteSession(sessionId)) { "Failed to delete session." }
            return
        }
        val target =
            repositories.launchableTargetRepository.getTarget(serverId)
                ?: error("This agent is no longer available.")
        val listing = listingSession(serverId)
        val transport = listing.browserTransport
        try {
            if (!transport.isConnected) {
                val config = buildListingConfig(target).getOrThrow()
                checkNotNull(withContext(ioDispatcher) { transport.connectAndInitializeWithoutReconnect(config) }) {
                    "Failed to connect to delete session."
                }
            }
            checkNotNull(
                transport.agentCapabilities.value
                    ?.sessionCapabilities
                    ?.delete,
            ) {
                "Session deletion is unsupported."
            }
            check(transport.deleteSession(sessionId)) { "Failed to delete session." }
        } finally {
            hangUpListing(listing)
        }
    }

    private fun authRequiredResult(
        transport: AcpConnection,
        viaWarmChat: Boolean,
        error: AcpAuthenticationRequiredException,
    ): ListSessionsResult.AuthRequired =
        ListSessionsResult.AuthRequired(
            agentName = error.challenge.agentInfo.name,
            authMethods = error.challenge.authMethods.map { it.toCatalogMethod() },
            challengeMessage = error.challenge.message,
            gatewayRuntimeId = (transport.currentConnectionConfig() as? AcpConnectionConfig.Gateway)?.runtimeId,
            viaWarmChat = viaWarmChat,
        )

    private fun listingSession(serverId: String): ListingSession =
        listingSessions.getOrPut(serverId) {
            ListingSession(browserTransport = hub.createBrowserManager(scope))
        }

    /** The transport that raised an outstanding auth challenge, else the browser transport. */
    private fun retainedListingTransport(serverId: String): AcpConnection? =
        listingSessions[serverId]?.authTransport
            ?: listingSessions[serverId]?.browserTransport

    /** Gateway REST endpoint for [serverId], or null for a target with no gateway leg. */
    private suspend fun gatewayEndpoint(serverId: String): GatewayEndpoint? =
        (repositories.launchableTargetRepository.getTarget(serverId) as? LaunchableTarget.GatewayAgent)
            ?.let { target ->
                GatewayEndpoint(
                    scheme = target.gatewaySource.scheme,
                    host = target.gatewaySource.host,
                    credential = target.gatewaySource.gatewayCredential,
                )
            }

    /**
     * Builds the config for a listing or delete leg on [target].
     *
     * A failure keeps its cause. The launch context already carries the actionable
     * reason ("Gateway credential expired. Please pair this gateway again."), and
     * folding it into `null` replaced that with a locally authored
     * "Failed to launch X" — the user was never told to re-pair.
     */
    private suspend fun buildListingConfig(target: LaunchableTarget): Result<AcpConnectionConfig> =
        when (target) {
            is LaunchableTarget.GatewayAgent ->
                buildGatewayLaunchContext(
                    runtimeLauncher = repositories.runtimeLauncher,
                    server = target,
                ).map { it.config }

            is LaunchableTarget.Manual -> Result.success(target.toConnectionConfig())
        }
}

/** An auth method the agent offers for the session list, in the shape the screen renders. */
data class CatalogAuthMethod(
    val id: String,
    val name: String,
    val description: String?,
    val type: String,
    val envVars: List<CatalogAuthEnvVar> = emptyList(),
    val link: String? = null,
    val args: List<String> = emptyList(),
    /** Default values the agent asks for; folded into the gateway restart payload. */
    val env: Map<String, String> = emptyMap(),
)

/** One environment variable an auth method declares. */
data class CatalogAuthEnvVar(
    val name: String,
    val label: String?,
    val secret: Boolean,
    val optional: Boolean,
)

private fun AcpAuthMethodInfo.toCatalogMethod(): CatalogAuthMethod =
    CatalogAuthMethod(
        id = id,
        name = name,
        description = description,
        type = type,
        envVars = envVars.map { it.toCatalogEnvVar() },
        link = link,
        args = args,
        env = env,
    )

private fun CatalogAuthMethod.toAcpMethod(): AcpAuthMethodInfo =
    AcpAuthMethodInfo(
        id = id,
        name = name,
        description = description,
        type = type,
        envVars = envVars.map { it.toAcpEnvVar() },
        link = link,
        args = args,
        env = env,
    )

private fun AuthEnvVarInfo.toCatalogEnvVar(): CatalogAuthEnvVar =
    CatalogAuthEnvVar(name = name, label = label, secret = secret, optional = optional)

private fun CatalogAuthEnvVar.toAcpEnvVar(): AuthEnvVarInfo =
    AuthEnvVarInfo(name = name, label = label, secret = secret, optional = optional)
