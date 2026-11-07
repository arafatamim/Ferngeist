package com.tamimarafat.ferngeist.acp.bridge.catalog.project

import com.tamimarafat.ferngeist.acp.bridge.catalog.turns.TurnStatusCatalog
import com.tamimarafat.ferngeist.acp.bridge.connection.gateway.LaunchRepositories
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.isInside
import com.tamimarafat.ferngeist.core.model.launchConsentKey
import com.tamimarafat.ferngeist.core.model.repository.ChatCatalog
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.gateway.GatewayCredentialExpiredException
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjectLine
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjects
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurnStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** Triggers within this long of a gateway's last fetch are skipped, except a forced refresh. */
private const val FETCH_THROTTLE_MS = 5_000L
private const val HTTP_NOT_FOUND = 404
private const val MAX_KNOWN_REPOS_PER_REQUEST = 256

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProjectDiscoveryConsent

class ProjectCatalogGateway
    @Inject
    constructor(
        val projects: GatewayProjects,
        val turnStream: GatewayTurnStream,
    )

/**
 * Projects across paired gateways, drawn from the last `/v1/projects` response of each.
 *
 * Owns the per-gateway snapshots: hydrates them from disk, refreshes them when a streamed turn
 * ends and on the triggers the app wires (no timer), and projects them onto this phone's
 * bindings. A gateway without a snapshot, including one that answered 404, contributes no projects.
 *
 * As [ChatCatalog] it answers for every agent: from its gateway's snapshot when that lists the
 * agent, else from its per-agent listing snapshot.
 *
 * Turn status streams from each gateway while the app is visible; it stays in memory only.
 */
@Singleton
class ProjectCatalog
    @Inject
    constructor(
        gateway: ProjectCatalogGateway,
        repositories: LaunchRepositories,
        store: ProjectSnapshotStore,
        private val scope: CoroutineScope,
        hub: ChatConnectionHub,
        @param:ProjectDiscoveryConsent private val consent: Flow<@JvmSuppressWildcards Set<String>>,
    ) : ChatCatalog {
        private val gatewayProjects = gateway.projects
        private val gatewaySourceRepository = repositories.gatewaySourceRepository
        private val launchableTargetRepository = repositories.launchableTargetRepository
        private val ioDispatcher = repositories.ioDispatcher

        private val cache = ProjectSnapshotCache(store, scope, ioDispatcher)
        private val _refreshing = MutableStateFlow<Set<String>>(emptySet())
        val refreshing: StateFlow<Set<String>> = _refreshing
        private val consentedKeys = consent.stateIn(scope, SharingStarted.Eagerly, emptySet())
        val discoveryAllowed = consentedKeys.map { it.isNotEmpty() }.stateIn(scope, SharingStarted.Eagerly, false)

        /** Targets discovery may see: manual servers plus gateway agents granted launch consent. */
        val consentedTargets: StateFlow<List<LaunchableTarget>> =
            combine(launchableTargetRepository.getTargets(), consentedKeys) { targets, consented ->
                targets.filter { it.hasLaunchConsent(consented) }
            }.stateIn(scope, SharingStarted.Eagerly, emptyList())

        private val snapshots = cache.state
        private val inFlight = HashMap<String, Job>()
        private val lastFetchAt = HashMap<String, Long>()

        /** Chats this phone created, by target id; in memory only, shown until a fetch lists them. */
        private val created = MutableStateFlow<Map<String, List<SessionSummary>>>(emptyMap())

        val state: StateFlow<ProjectCatalogState> =
            combine(
                snapshots,
                gatewaySourceRepository.getGateways(),
                consentedTargets,
                created,
            ) { snapshots, gateways, targets, created ->
                if (snapshots == null) return@combine ProjectCatalogState()
                val gatewayTargets = targets.filterIsInstance<LaunchableTarget.GatewayAgent>()
                ProjectCatalogState(
                    projects =
                        gateways
                            .flatMap { gateway ->
                                snapshots[gateway.id]
                                    ?.let {
                                        projectsOf(
                                            gateway,
                                            it,
                                            gatewayTargets,
                                            created,
                                        )
                                    }.orEmpty()
                            }.sortedWith(projectOrder),
                    isReady = true,
                    agentStatuses =
                        gatewayTargets
                            .mapNotNull { target ->
                                snapshots[target.gatewaySource.id]?.statuses?.get(target.binding.agentId)?.let {
                                    target.id to
                                        it
                                }
                            }.toMap(),
                )
            }.stateIn(scope, SharingStarted.Eagerly, ProjectCatalogState())

        /** Per gateway, its streamed turn status with the hub's live status on top. */
        private val turnStatusCatalog =
            TurnStatusCatalog(
                gateway.turnStream,
                consentedTargets,
                scope,
                hub,
            )

        val turnStatuses: StateFlow<Map<String, TurnActivity>> = turnStatusCatalog.statuses

        /** Chats with a turn in flight: [turnStatuses] seen through the flag readers that predate it. */
        val inProgressChatKeys: StateFlow<Set<String>> = turnStatusCatalog.activeChatKeys

        /** Agent-reported activity is only a recency hint, never evidence of a running turn. */
        val activityUpdatedAt: StateFlow<Map<String, Long>> =
            combine(
                snapshots,
                consentedTargets,
                hub.trackedChatIds,
                inProgressChatKeys,
            ) { cache, targets, tracked, running ->
                activityTimestamps(cache.orEmpty(), targets) - tracked - running
            }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

        init {
            scope.launch(ioDispatcher) {
                turnStatusCatalog.turnEnded.collect { ended ->
                    launchableTargetRepository
                        .getTargets()
                        .first()
                        .filterIsInstance<LaunchableTarget.GatewayAgent>()
                        .filter { target -> ended.any { it.startsWith("${target.id}/") } }
                        .map { it.gatewaySource.id }
                        .distinct()
                        .forEach { gatewayId ->
                            launch {
                                // A fetch started before completion can still return the old timestamp.
                                synchronized(inFlight) { inFlight[gatewayId] }?.join()
                                refresh(gatewayId, force = true).join()
                            }
                        }
                }
            }
        }

        /** Shows [summary], just created on [serverId], in its project before the agent next lists it. */
        override fun chatCreated(
            serverId: String,
            summary: SessionSummary,
        ) {
            created.update {
                it +
                    (
                        serverId to
                            (
                                it[serverId].orEmpty().filterNot { chat ->
                                    chat.id == summary.id
                                } + summary.copy(serverId = serverId)
                            )
                    )
            }
        }

        override fun getSessions(serverId: String): Flow<List<SessionSummary>> =
            combine(
                snapshots.filterNotNull(),
                consentedTargets,
                created,
            ) { cache, targets, memory ->
                val target = targets.firstOrNull { it.id == serverId } ?: return@combine emptyList()
                chatsOf(
                    target,
                    cache,
                    cache[agentKey(serverId)]?.listings?.get(serverId).orEmpty(),
                    memory[serverId].orEmpty(),
                )
            }

        override fun getRecentSessions(limit: Int): Flow<List<SessionSummary>> =
            combine(
                snapshots.filterNotNull(),
                consentedTargets,
                created,
            ) { cache, targets, memory ->
                val stored = cache.values.flatMap { it.listings.values.flatten() }
                recentChatsOf(
                    targets,
                    cache,
                    stored,
                    memory.filterKeys { id -> targets.any { it.id == id } },
                    limit,
                )
            }

        override suspend fun getSession(
            serverId: String,
            sessionId: String,
        ): SessionSummary? = getSessions(serverId).first().firstOrNull { it.id == sessionId }

        override suspend fun recordListing(
            serverId: String,
            sessions: List<SessionSummary>,
        ) {
            snapshots.filterNotNull().first()
            val target = launchableTargetRepository.getTarget(serverId) as? LaunchableTarget.GatewayAgent
            cache.writes.withLock {
                val key = agentKey(serverId)
                val previous = snapshots.value?.get(key) ?: ProjectSnapshot()
                val ids = sessions.map { it.id }.toSet()
                val gatewayHidden =
                    target
                        ?.let {
                            snapshots.value
                                ?.get(it.gatewaySource.id)
                                ?.suppressed
                                ?.get(it.binding.agentId)
                        }.orEmpty()
                val hidden = (previous.suppressed[serverId].orEmpty() + gatewayHidden).intersect(ids)
                cache.publish(
                    key,
                    previous.copy(
                        listings =
                            mapOf(
                                serverId to
                                    visibleChats(
                                        target,
                                        snapshots.value.orEmpty(),
                                        sessions.filter { it.id !in hidden },
                                    ).map { it.copy(serverId = serverId) },
                            ),
                        suppressed = mapOf(serverId to hidden),
                    ),
                )
                retireCreated(serverId, ids)
            }
        }

        override suspend fun deleteSession(
            serverId: String,
            sessionId: String,
        ) {
            snapshots.filterNotNull().first()
            val target = launchableTargetRepository.getTarget(serverId)
            cache.writes.withLock {
                val gateway = target as? LaunchableTarget.GatewayAgent
                if (gateway != null) {
                    val key = gateway.gatewaySource.id
                    val agent = gateway.binding.agentId
                    val previous = snapshots.value?.get(key) ?: ProjectSnapshot()
                    cache.publish(
                        key,
                        previous.copy(
                            agents =
                                previous.agents +
                                    (agent to previous.agents[agent].orEmpty().filterNot { it.sessionId == sessionId }),
                            suppressed =
                                previous.suppressed + (agent to (previous.suppressed[agent].orEmpty() + sessionId)),
                        ),
                    )
                    val bindings =
                        launchableTargetRepository
                            .gatewayTargets(gateway.gatewaySource)
                            .filter { it.binding.agentId == agent }
                            .map { it.id }
                    bindings.forEach { suppressListing(it, sessionId) }
                } else {
                    suppressListing(serverId, sessionId)
                }
            }
        }

        private fun suppressListing(
            serverId: String,
            sessionId: String,
        ) {
            val key = agentKey(serverId)
            val previous = snapshots.value?.get(key) ?: ProjectSnapshot()
            cache.publish(
                key,
                previous.copy(
                    // An empty listing would shadow the gateway snapshot's chats, so never create one here.
                    listings = previous.listings.mapValues { (_, chats) -> chats.filterNot { it.id == sessionId } },
                    suppressed =
                        previous.suppressed + (serverId to (previous.suppressed[serverId].orEmpty() + sessionId)),
                ),
            )
            retireCreated(serverId, setOf(sessionId))
        }

        override suspend fun clearSessions(serverId: String) {
            snapshots.filterNotNull().first()
            cache.writes.withLock {
                cache.forget(agentKey(serverId))
                created.update { it - serverId }
            }
        }

        private fun retireCreated(
            serverId: String,
            ids: Set<String>,
        ) {
            created.update { all -> all + (serverId to all[serverId].orEmpty().filterNot { it.id in ids }) }
        }

        /**
         * Streams turn status while the app is visible, under the same discovery consent as the
         * listing. Cancelling it (the app hidden, the network lost) closes every socket.
         */
        suspend fun streamTurns() {
            discoveryAllowed.collectLatest { allowed ->
                if (allowed) turnStatusCatalog.stream(gatewaySourceRepository.getGateways())
            }
        }

        /** Fetches [gatewaySourceId], or every paired gateway; one in flight per gateway. */
        fun refresh(
            gatewaySourceId: String? = null,
            force: Boolean = false,
        ): Job =
            scope.launch(ioDispatcher) {
                if (consent.first().isEmpty()) return@launch
                // A fetch merges into the hydrated snapshot; starting from nothing would drop the cache.
                val hydrated = snapshots.filterNotNull().first()
                val gateways = gatewaySourceRepository.getGateways().first()
                val targets = launchableTargetRepository.getTargets().first()
                val retained = gateways.map { it.id }.toSet() + targets.map { agentKey(it.id) }
                cache.writes.withLock { (hydrated.keys - retained).forEach(cache::forget) }
                val paired = gateways.map { it.id }.toSet()
                turnStatusCatalog.retain(paired)
                gateways
                    .filter { gatewaySourceId == null || it.id == gatewaySourceId }
                    .mapNotNull { startFetch(it, force) }
                    .joinAll()
            }

        private fun startFetch(
            gateway: GatewaySource,
            force: Boolean,
        ): Job? =
            synchronized(inFlight) {
                inFlight[gateway.id]?.takeIf { it.isActive }?.let { return@synchronized it }
                val now = System.currentTimeMillis()
                val throttled = !force && lastFetchAt[gateway.id]?.let { now - it < FETCH_THROTTLE_MS } == true
                if (throttled) return@synchronized null
                lastFetchAt[gateway.id] = now
                scope
                    .launch(ioDispatcher) {
                        _refreshing.update { it + gateway.id }
                        try {
                            fetch(gateway)
                        } finally {
                            _refreshing.update { it - gateway.id }
                        }
                    }.also { inFlight[gateway.id] = it }
            }

        /**
         * Each line lands on disk as it arrives, so a stream cut before `checkouts` keeps the agent
         * lines already received and the previous checkouts. Failures keep the cache; the next
         * trigger retries.
         */
        private suspend fun fetch(gateway: GatewaySource) {
            val targets = launchableTargetRepository.consentedGatewayTargets(gateway, consent)
            val agentIds = targets.map { it.binding.agentId }.distinct()
            if (agentIds.isEmpty()) return
            try {
                val roots = knownRoots(snapshots.value.orEmpty(), gateway, targets, agentIds)
                // The gateway bounds the signed query; batching still uses its shared agent cache.
                val batches = roots.chunked(MAX_KNOWN_REPOS_PER_REQUEST).ifEmpty { listOf(emptyList()) }
                batches.forEach { knownRepos ->
                    gatewayProjects
                        .fetchProjects(
                            gateway.scheme,
                            gateway.host,
                            gateway.gatewayCredential,
                            agentIds,
                            knownRepos,
                        ).collect { line -> applyLine(gateway, line, targets) }
                }
            } catch (error: GatewayRequestException) {
                if (error.statusCode == HTTP_NOT_FOUND) {
                    cache.writes.withLock {
                        val previous = snapshots.value?.get(gateway.id)
                        if (previous?.suppressed?.values?.any { it.isNotEmpty() } == true) {
                            cache.publish(gateway.id, previous.copy(agents = emptyMap(), checkouts = null))
                        } else {
                            cache.forget(gateway.id)
                        }
                    }
                }
            } catch (_: GatewayCredentialExpiredException) {
                // The agent cards surface re-pairing; projects keep the last snapshot meanwhile.
            } catch (_: IOException) {
                // Offline or cut mid-stream: what arrived is already stored.
            } catch (_: SerializationException) {
                // A malformed line ends this fetch; the lines before it are already stored.
            }
        }

        private suspend fun applyLine(
            gateway: GatewaySource,
            line: GatewayProjectLine,
            targets: List<LaunchableTarget.GatewayAgent>,
        ) {
            // A consent revoked mid-stream stops the lines that already arrived too.
            val bound = targets.filter { it.hasLaunchConsent(consentedKeys.value) }
            if (line is GatewayProjectLine.Agent && bound.none { it.binding.agentId == line.agentId }) return
            cache.writes.withLock {
                val previous = snapshots.value?.get(gateway.id) ?: ProjectSnapshot()
                if (line is GatewayProjectLine.Checkouts && line.missingRepos.isNotEmpty()) {
                    val paths =
                        line.missingRepos +
                            previous.checkouts
                                .orEmpty()
                                .filter { it.repo in line.missingRepos }
                                .flatMap { it.worktrees.map { tree -> tree.path } }
                    bound.forEach { target -> pruneMissingPaths(target.id, paths) }
                }
                cache.publish(gateway.id, previous.merge(line))
                if (line is GatewayProjectLine.Agent && line.status == "ok") {
                    bound.filter { it.binding.agentId == line.agentId }.forEach {
                        cache.forget(agentKey(it.id))
                        retireCreated(it.id, line.chats.map { chat -> chat.sessionId }.toSet())
                    }
                }
            }
        }

        private fun pruneMissingPaths(
            serverId: String,
            paths: List<String>,
        ) {
            val key = agentKey(serverId)
            snapshots.value?.get(key)?.let { snapshot ->
                val kept =
                    snapshot.listings[serverId].orEmpty().filterNot { chat ->
                        chat.cwd?.let { cwd -> paths.any { isInside(cwd, it) } } == true
                    }
                cache.publish(key, snapshot.copy(listings = mapOf(serverId to kept)))
            }
            created.update { all ->
                all + (
                    serverId to
                        all[serverId].orEmpty().filterNot { chat ->
                            chat.cwd?.let { cwd -> paths.any { isInside(cwd, it) } } == true
                        }
                )
            }
        }
    }

/** The bindings on this phone for [gateway], as this catalog lists them. */
private suspend fun LaunchableTargetRepository.gatewayTargets(gateway: GatewaySource) =
    getTargets().first().filterIsInstance<LaunchableTarget.GatewayAgent>().filter { it.gatewaySource.id == gateway.id }

/** The gateway's bindings whose agent has launch consent; the request may name only these. */
private suspend fun LaunchableTargetRepository.consentedGatewayTargets(
    gateway: GatewaySource,
    consent: Flow<Set<String>>,
): List<LaunchableTarget.GatewayAgent> {
    val consented = consent.first()
    return gatewayTargets(gateway).filter { it.hasLaunchConsent(consented) }
}

/**
 * Repo paths the gateway already knows for [agentIds]. Checkouts stay gateway-wide inventory:
 * they carry no agent attribution, so they are not narrowed to consented agents.
 */
private fun knownRoots(
    snapshots: Map<String, ProjectSnapshot>,
    gateway: GatewaySource,
    targets: List<LaunchableTarget.GatewayAgent>,
    agentIds: List<String>,
): List<String> {
    val cached = snapshots[gateway.id]
    val fallbackPaths =
        targets.flatMap { target ->
            snapshots
                .get(agentKey(target.id))
                ?.listings
                ?.get(target.id)
                .orEmpty()
                .mapNotNull { it.cwd }
        }
    return (
        cached?.checkouts.orEmpty().map { it.repo } + cached?.missingPaths.orEmpty().keys +
            cached
                ?.agents
                .orEmpty()
                .filterKeys { it in agentIds }
                .values
                .flatten()
                .map { it.repo } +
            fallbackPaths
    ).distinct()
}

private fun agentKey(serverId: String) = "agent-$serverId"

/** Manual targets have no launch consent; gateway agents need their own grant. */
private fun LaunchableTarget.hasLaunchConsent(consentedKeys: Set<String>): Boolean =
    this !is LaunchableTarget.GatewayAgent || binding.launchConsentKey in consentedKeys
