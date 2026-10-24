package com.tamimarafat.ferngeist.acp.bridge.catalog.project

import com.tamimarafat.ferngeist.acp.bridge.connection.LaunchRepositories
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.gateway.GatewayCredentialExpiredException
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.IOException

/** Triggers within this long of a gateway's last fetch are skipped, except a forced refresh. */
private const val FETCH_THROTTLE_MS = 5_000L
private const val HTTP_NOT_FOUND = 404

/**
 * Projects across paired gateways, drawn from the last `/v1/projects` response of each.
 *
 * Owns the per-gateway snapshots: hydrates them from disk, refreshes them on the triggers the
 * app wires (no timer or socket), and projects them onto this phone's bindings. A gateway
 * without a snapshot, including one that answered 404, contributes no projects.
 */
class ProjectCatalog(
    private val gatewayProjects: GatewayProjects,
    repositories: LaunchRepositories,
    private val store: ProjectSnapshotStore,
    private val scope: CoroutineScope,
) {
    private val gatewaySourceRepository = repositories.gatewaySourceRepository
    private val launchableTargetRepository = repositories.launchableTargetRepository
    private val ioDispatcher = repositories.ioDispatcher

    private val snapshots = MutableStateFlow<Map<String, ProjectSnapshot>?>(null)
    private val inFlight = HashMap<String, Job>()
    private val lastFetchAt = HashMap<String, Long>()

    val state: StateFlow<ProjectCatalogState> =
        combine(
            snapshots,
            gatewaySourceRepository.getGateways(),
            launchableTargetRepository.getTargets(),
        ) { snapshots, gateways, targets ->
            if (snapshots == null) return@combine ProjectCatalogState()
            val gatewayTargets = targets.filterIsInstance<LaunchableTarget.GatewayAgent>()
            ProjectCatalogState(
                projects =
                    gateways
                        .flatMap { gateway ->
                            snapshots[gateway.id]?.let { projectsOf(gateway, it, gatewayTargets) }.orEmpty()
                        }.sortedWith(projectOrder),
                isReady = true,
            )
        }.stateIn(scope, SharingStarted.Eagerly, ProjectCatalogState())

    init {
        scope.launch(ioDispatcher) { snapshots.value = store.readAll() }
    }

    /** Fetches [gatewaySourceId], or every paired gateway; one in flight per gateway. */
    fun refresh(
        gatewaySourceId: String? = null,
        force: Boolean = false,
    ): Job =
        scope.launch(ioDispatcher) {
            // A fetch merges into the hydrated snapshot; starting from nothing would drop the cache.
            val hydrated = snapshots.filterNotNull().first()
            val gateways = gatewaySourceRepository.getGateways().first()
            (hydrated.keys - gateways.map { it.id }.toSet()).forEach(::forget)
            gateways
                .filter { gatewaySourceId == null || it.id == gatewaySourceId }
                .forEach { startFetch(it, force) }
        }

    private fun startFetch(
        gateway: GatewaySource,
        force: Boolean,
    ) = synchronized(inFlight) {
        val now = System.currentTimeMillis()
        val throttled = !force && lastFetchAt[gateway.id]?.let { now - it < FETCH_THROTTLE_MS } == true
        if (inFlight[gateway.id]?.isActive == true || throttled) return@synchronized
        lastFetchAt[gateway.id] = now
        inFlight[gateway.id] = scope.launch(ioDispatcher) { fetch(gateway) }
    }

    /**
     * Each line lands on disk as it arrives, so a stream cut before `checkouts` keeps the agent
     * lines already received and the previous checkouts. Failures keep the cache; the next
     * trigger retries.
     */
    private suspend fun fetch(gateway: GatewaySource) {
        val agentIds = targetsOf(gateway).map { it.binding.agentId }.distinct()
        if (agentIds.isEmpty()) return
        try {
            gatewayProjects
                .fetchProjects(gateway.scheme, gateway.host, gateway.gatewayCredential, agentIds)
                .collect { line ->
                    val next = (snapshots.value?.get(gateway.id) ?: ProjectSnapshot()).merge(line)
                    store.write(gateway.id, next)
                    snapshots.update { it.orEmpty() + (gateway.id to next) }
                }
        } catch (error: GatewayRequestException) {
            if (error.statusCode == HTTP_NOT_FOUND) forget(gateway.id)
        } catch (_: GatewayCredentialExpiredException) {
            // The agent cards surface re-pairing; projects keep the last snapshot meanwhile.
        } catch (_: IOException) {
            // Offline or cut mid-stream: what arrived is already stored.
        } catch (_: SerializationException) {
            // A malformed line ends this fetch; the lines before it are already stored.
        }
    }

    private suspend fun targetsOf(gateway: GatewaySource) =
        launchableTargetRepository.getTargets().first().filterIsInstance<LaunchableTarget.GatewayAgent>().filter {
            it.gatewaySource.id == gateway.id
        }

    private fun forget(gatewaySourceId: String) {
        store.delete(gatewaySourceId)
        snapshots.update { it?.minus(gatewaySourceId) }
    }
}
