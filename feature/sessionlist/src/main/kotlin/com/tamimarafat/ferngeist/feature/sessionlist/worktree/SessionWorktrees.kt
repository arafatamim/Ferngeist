package com.tamimarafat.ferngeist.feature.sessionlist.worktree

import com.tamimarafat.ferngeist.acp.bridge.hub.GatewayEndpoint
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetSessionSettingsRepository
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListEvent
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.RecentCwdStore
import com.tamimarafat.ferngeist.gateway.GatewayCredentialExpiredException
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import com.tamimarafat.ferngeist.gateway.GatewayWorktree
import com.tamimarafat.ferngeist.gateway.gatewayErrorMessage
import com.tamimarafat.ferngeist.gateway.refreshGatewaySourceIfNeeded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Statuses the worktree endpoints answer with, named so the branches read as intent. */
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_CONFLICT = 409
private const val HTTP_NOT_FOUND = 404

private const val WORKTREE_CREATE_FAILED = "Failed to create the worktree."
private const val WORKTREE_REMOVE_FAILED = "Failed to remove the worktree."

/**
 * Gateway worktrees for one session list: creating one for a new chat, listing the gateway's
 * managed ones, and removing them.
 *
 * A separate type from the view model that drives it: a worktree is a self-contained gateway
 * concern with its own state, and the view model's part shrinks to supplying the endpoint,
 * opening the chat, and forwarding what the endpoints said.
 *
 * @param emitEvent hands a message to the session list's snackbar.
 * @param openChat opens the chat that will run in the created worktree.
 */
internal class SessionWorktrees(
    private val serverId: String,
    private val gatewayRepository: GatewayRepository,
    private val gatewaySourceRepository: GatewaySourceRepository,
    private val launchableTargetRepository: LaunchableTargetRepository,
    private val sessionSettingsRepository: LaunchableTargetSessionSettingsRepository,
    private val recentCwdStore: RecentCwdStore,
    private val emitEvent: suspend (SessionListEvent) -> Unit,
    private val openChat: (String) -> Unit,
) {
    private val _managed = MutableStateFlow<List<GatewayWorktree>?>(null)

    /**
     * This gateway's managed worktrees, or null when there is nothing to show for it — an
     * older gateway whose `/v1/worktrees` answers 404, or a non-gateway target. Null hides
     * the worktree UI instead of offering actions that can only fail.
     */
    val managed: StateFlow<List<GatewayWorktree>?> = _managed.asStateFlow()

    private val _creating = MutableStateFlow(false)

    /** True while a create is in flight; the dialog's confirm button stays busy. */
    val creating: StateFlow<Boolean> = _creating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** Inline error for the worktree form (bad repo/branch, existing branch), else null. */
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    /**
     * Refreshes the gateway's worktree list.
     *
     * A 404 is the signal that the gateway predates the worktree API, so the UI goes back to
     * hidden. Any other failure keeps the last successful list: a transient network fault
     * must not make the branch chips of running chats vanish.
     */
    suspend fun refresh(endpoint: GatewayEndpoint?) {
        if (endpoint == null) {
            _managed.value = null
            return
        }
        try {
            _managed.value =
                withContext(Dispatchers.IO) {
                    gatewayRepository.listWorktrees(endpoint.scheme, endpoint.host, endpoint.credential)
                }
        } catch (error: CancellationException) {
            throw error
        } catch (error: GatewayRequestException) {
            if (error.statusCode == HTTP_NOT_FOUND) _managed.value = null
        } catch (_: Exception) {
            // Transport fault: keep the last successful list.
        }
    }

    /**
     * Creates a worktree for [repo] and opens a chat in it.
     *
     * The repo — never the worktree directory — is what the cwd filter and the recents record,
     * so throwaway `.worktrees/` paths do not accumulate in the suggestion list.
     *
     * Creation runs `git worktree add` on the gateway and can take seconds on a large repo, so
     * nothing navigates until the directory exists.
     */
    suspend fun create(
        repo: String,
        base: String?,
        branch: String?,
    ) {
        val normalizedRepo = repo.trim()
        if (normalizedRepo.isBlank() || _creating.value) return
        _error.value = null
        _creating.value = true
        try {
            sessionSettingsRepository.updateCwd(serverId, normalizedRepo)
            recentCwdStore.addCwd(serverId, normalizedRepo)
            val target = withContext(Dispatchers.IO) { launchableTargetRepository.getTarget(serverId) }
            val gatewayTarget = target as? LaunchableTarget.GatewayAgent
            if (gatewayTarget == null) {
                _error.value = "Worktrees are only available for gateway agents."
                return
            }
            val gateway =
                withContext(Dispatchers.IO) {
                    refreshGatewaySourceIfNeeded(
                        gatewayTarget.gatewaySource,
                        gatewayRepository,
                        gatewaySourceRepository,
                    )
                }
            val worktree =
                withContext(Dispatchers.IO) {
                    gatewayRepository.createWorktree(
                        scheme = gateway.scheme,
                        host = gateway.host,
                        gatewayCredential = gateway.gatewayCredential,
                        repo = normalizedRepo,
                        base = base,
                        branch = branch,
                    )
                }
            refresh(
                GatewayEndpoint(
                    scheme = gateway.scheme,
                    host = gateway.host,
                    credential = gateway.gatewayCredential,
                ),
            )
            openChat(worktree.path)
        } catch (_: GatewayCredentialExpiredException) {
            _error.value = "This gateway's credential expired. Pair it again to create worktrees."
        } catch (error: GatewayRequestException) {
            val gatewayMessage = gatewayErrorMessage(error.responseBody)
            when (error.statusCode) {
                HTTP_BAD_REQUEST -> _error.value = gatewayMessage ?: "The branch name is not valid."
                HTTP_CONFLICT -> _error.value = "Branch already exists"
                // 422: not a repo, unknown base, or `git worktree add` failed.
                else -> emitEvent(SessionListEvent.ShowError(gatewayMessage ?: WORKTREE_CREATE_FAILED))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emitEvent(SessionListEvent.ShowError(WORKTREE_CREATE_FAILED))
        } finally {
            _creating.value = false
        }
    }

    /**
     * Deletes a worktree, mapping each failure onto its own surface.
     *
     * A 409 without [force] means git refused over uncommitted changes, which only the user
     * can answer, so it becomes a confirm event rather than an error.
     *
     * @return true when the worktree is gone, including when the gateway had already lost it.
     */
    suspend fun remove(
        endpoint: GatewayEndpoint,
        sessionId: String,
        worktreeId: String,
        force: Boolean,
    ): Boolean {
        var removed = true
        try {
            gatewayRepository.deleteWorktree(
                scheme = endpoint.scheme,
                host = endpoint.host,
                gatewayCredential = endpoint.credential,
                worktreeId = worktreeId,
                force = force,
            )
        } catch (error: GatewayRequestException) {
            removed = error.statusCode == HTTP_NOT_FOUND
            when (error.statusCode) {
                HTTP_CONFLICT ->
                    if (force) {
                        emitRemovalFailure(error.responseBody)
                    } else {
                        emitEvent(
                            SessionListEvent.ConfirmRemoveWorktree(
                                sessionId = sessionId,
                                worktreeId = worktreeId,
                                branch =
                                    _managed.value
                                        ?.firstOrNull { it.id == worktreeId }
                                        ?.branch
                                        .orEmpty(),
                            ),
                        )
                    }

                // Already gone: the list was stale, so re-read it below.
                HTTP_NOT_FOUND -> Unit
                else -> emitRemovalFailure(error.responseBody)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            removed = false
            emitEvent(SessionListEvent.ShowError(WORKTREE_REMOVE_FAILED))
        }
        refresh(endpoint)
        return removed
    }

    private suspend fun emitRemovalFailure(responseBody: String?) {
        emitEvent(SessionListEvent.ShowError(gatewayErrorMessage(responseBody) ?: WORKTREE_REMOVE_FAILED))
    }
}
