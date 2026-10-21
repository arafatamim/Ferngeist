package com.tamimarafat.ferngeist.feature.sessionlist.worktree

import android.content.Context
import androidx.annotation.StringRes
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetSessionSettingsRepository
import com.tamimarafat.ferngeist.feature.sessionlist.R
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

/**
 * Gateway worktrees for one session list: creating one for a new chat, listing the gateway's
 * managed ones, and removing them.
 *
 * A separate type from the view model that drives it: a worktree is a self-contained gateway
 * concern with its own state, and the view model's part shrinks to opening the chat and
 * forwarding what the endpoints said.
 *
 * @param emitEvent hands a message to the session list's snackbar.
 * @param openChat opens the chat that will run in the created worktree.
 */
internal class SessionWorktrees(
    private val context: Context,
    private val serverId: String,
    private val gatewayRepository: GatewayRepository,
    private val gatewaySourceRepository: GatewaySourceRepository,
    private val launchableTargetRepository: LaunchableTargetRepository,
    private val sessionSettingsRepository: LaunchableTargetSessionSettingsRepository,
    private val recentCwdStore: RecentCwdStore,
    private val emitEvent: suspend (SessionListEvent) -> Unit,
    private val openChat: (String) -> Unit,
) {
    private fun text(
        @StringRes id: Int,
    ) = context.getString(id)

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
    suspend fun refresh() {
        val gateway =
            gatewaySource() ?: run {
                _managed.value = null
                return
            }
        refreshWith(gateway)
    }

    private suspend fun refreshWith(gateway: GatewaySource) {
        try {
            _managed.value =
                withContext(Dispatchers.IO) {
                    gatewayRepository.listWorktrees(gateway.scheme, gateway.host, gateway.gatewayCredential)
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
     * This target's stored gateway endpoint, or null for a non-gateway target.
     *
     * The stored source, never a re-fetched one: a refresh must not cost a REST round trip
     * ([create] refreshes the credential itself before it lists).
     */
    private suspend fun gatewaySource(): GatewaySource? =
        withContext(Dispatchers.IO) {
            (launchableTargetRepository.getTarget(serverId) as? LaunchableTarget.GatewayAgent)?.gatewaySource
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
                _error.value = text(R.string.sessionlist_worktree_error_gateway_only)
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
            refreshWith(gateway)
            openChat(worktree.path)
        } catch (_: GatewayCredentialExpiredException) {
            _error.value = text(R.string.sessionlist_worktree_error_credential)
        } catch (error: GatewayRequestException) {
            val gatewayMessage = gatewayErrorMessage(error.responseBody)
            when (error.statusCode) {
                HTTP_BAD_REQUEST ->
                    _error.value =
                        gatewayMessage ?: text(R.string.sessionlist_worktree_error_branch_invalid)
                HTTP_CONFLICT -> _error.value = text(R.string.sessionlist_worktree_error_branch_exists)
                // 422: not a repo, unknown base, or `git worktree add` failed.
                else ->
                    emitEvent(
                        SessionListEvent.ShowError(
                            gatewayMessage ?: text(R.string.sessionlist_worktree_error_create),
                        ),
                    )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emitEvent(SessionListEvent.ShowError(text(R.string.sessionlist_worktree_error_create)))
        } finally {
            _creating.value = false
        }
    }

    /** Opens a new chat in an existing [worktree], recording its repo the way [create] does. */
    suspend fun open(worktree: GatewayWorktree) {
        sessionSettingsRepository.updateCwd(serverId, worktree.repo)
        recentCwdStore.addCwd(serverId, worktree.repo)
        openChat(worktree.path)
    }

    /**
     * Deletes the worktree the user confirmed in [request], discarding uncommitted changes when
     * [SessionListEvent.ConfirmRemoveWorktree.discardChanges] is set.
     *
     * A 409 without it means git refused over uncommitted changes the listing did not show yet,
     * which only the user can answer, so it comes back as the same confirm, now to discard.
     *
     * @return true when the worktree is gone, including when the gateway had already lost it.
     */
    suspend fun remove(request: SessionListEvent.ConfirmRemoveWorktree): Boolean {
        val force = request.discardChanges
        val gateway = gatewaySource() ?: return false
        var removed = true
        try {
            gatewayRepository.deleteWorktree(
                scheme = gateway.scheme,
                host = gateway.host,
                gatewayCredential = gateway.gatewayCredential,
                worktreeId = request.worktreeId,
                force = force,
            )
        } catch (error: GatewayRequestException) {
            removed = error.statusCode == HTTP_NOT_FOUND
            when (error.statusCode) {
                HTTP_CONFLICT ->
                    if (force) {
                        emitRemovalFailure(error.responseBody)
                    } else {
                        emitEvent(request.copy(discardChanges = true))
                    }

                // Already gone: the list was stale, so re-read it below.
                HTTP_NOT_FOUND -> Unit
                else -> emitRemovalFailure(error.responseBody)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            removed = false
            emitEvent(SessionListEvent.ShowError(text(R.string.sessionlist_worktree_error_remove)))
        }
        refresh()
        return removed
    }

    private suspend fun emitRemovalFailure(responseBody: String?) {
        emitEvent(
            SessionListEvent.ShowError(
                gatewayErrorMessage(responseBody) ?: text(R.string.sessionlist_worktree_error_remove),
            ),
        )
    }
}
