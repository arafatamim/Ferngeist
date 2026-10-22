package com.tamimarafat.ferngeist.feature.sessionlist.worktree

import android.content.Context
import androidx.annotation.StringRes
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.cwdKey
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetSessionSettingsRepository
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.SessionListEvent
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.RecentCwdStore
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.isInside
import com.tamimarafat.ferngeist.gateway.GatewayCredentialExpiredException
import com.tamimarafat.ferngeist.gateway.GatewayGitRefs
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
 * @param openChat opens a chat in the worktree at `path`, which belongs to `repo`.
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
    private val openChat: (repo: String, path: String) -> Unit,
) {
    private fun text(
        @StringRes id: Int,
    ) = context.getString(id)

    private val _managed = MutableStateFlow<List<GatewayWorktree>?>(null)

    /**
     * This gateway's worktrees, whoever made them, or null when there is nothing to show for it:
     * an older gateway whose `/v1/worktrees` answers 404, or a non-gateway target. Null hides
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

    /** `cwdKey`s of [refresh]'s other folders already asked about, so each costs one call. */
    private val probed = mutableSetOf<String>()

    /**
     * Refreshes the gateway's worktree list, making sure it covers [repo] (the cwd filter) and
     * [others] (the folders chats ran in), so a worktree made elsewhere folds into its repo.
     *
     * A 404 is the signal that the gateway predates the worktree API, so the UI goes back to
     * hidden. Any other failure keeps the last successful list: a transient network fault
     * must not make the branch chips of running chats vanish.
     */
    suspend fun refresh(
        repo: String?,
        others: Collection<String> = emptyList(),
    ) {
        val gateway =
            gatewaySource() ?: run {
                _managed.value = null
                return
            }
        refreshWith(gateway, repo, others)
    }

    private suspend fun refreshWith(
        gateway: GatewaySource,
        repo: String?,
        others: Collection<String> = emptyList(),
    ) {
        try {
            _managed.value =
                withContext(Dispatchers.IO) {
                    var all = gatewayRepository.listWorktrees(gateway.scheme, gateway.host, gateway.gatewayCredential)
                    // The gateway only lists repos it has seen a chat, a worktree or a ?repo= in. Asking
                    // for an uncovered folder makes its repo known, so later listings carry it; one that
                    // is no repo (400/422) adds none. The filter is asked each time, in case the gateway
                    // restarted and forgot it; the other folders only once.
                    // shortcut: a gateway restart forgets those others until this screen is rebuilt.
                    val target = repo?.trim()?.takeIf { isAbsolutePath(it) }
                    val dirs = (listOfNotNull(target) + others.filter(::isAbsolutePath)).distinctBy(::cwdKey)
                    for (dir in dirs) {
                        val covered = all.any { isInside(dir, it.repo) || isInside(dir, it.path) }
                        if (covered || (dir != target && !probed.add(cwdKey(dir)))) continue
                        val extra =
                            attempt(emptyList()) {
                                gatewayRepository.listWorktrees(
                                    gateway.scheme,
                                    gateway.host,
                                    gateway.gatewayCredential,
                                    dir,
                                )
                            }
                        all = all + extra.filter { found -> all.none { it.id == found.id } }
                    }
                    all
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
            refreshWith(gateway, normalizedRepo)
            openChat(worktree.repo, worktree.path)
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

    private val _missingCwds = MutableStateFlow<Set<String>>(emptySet())

    /** [cwdKey]s of chat directories the gateway reports gone from disk, usually deleted worktrees. */
    val missingCwds: StateFlow<Set<String>> = _missingCwds.asStateFlow()

    /**
     * Asks the gateway which of [cwds] no longer exist. Derived afresh on every call and never
     * stored, so a restored folder brings its chats back; any failure, including a gateway that
     * predates the route, reads as every folder still there.
     */
    suspend fun refreshMissing(cwds: Collection<String>) {
        val gateway = gatewaySource()
        // The gateway rejects the whole request over one relative path.
        val paths = cwds.filter(::isAbsolutePath).distinctBy(::cwdKey)
        _missingCwds.value =
            if (gateway == null || paths.isEmpty()) {
                emptySet()
            } else {
                attempt(emptyList()) {
                    gatewayRepository.findMissingPaths(gateway.scheme, gateway.host, gateway.gatewayCredential, paths)
                }.mapTo(HashSet(), ::cwdKey)
            }
    }

    /** [repo]'s refs and default branch, or none when the gateway cannot read them or is too old to ask. */
    suspend fun repoRefs(repo: String): GatewayGitRefs {
        val gateway = gatewaySource()
        if (repo.isBlank() || gateway == null) return GatewayGitRefs()
        return attempt(GatewayGitRefs()) {
            gatewayRepository.fetchRepoRefs(gateway.scheme, gateway.host, gateway.gatewayCredential, repo.trim())
        }
    }

    private suspend fun <T> attempt(
        fallback: T,
        block: suspend () -> T,
    ): T =
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            fallback
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
        refresh(repo = null)
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

private val WINDOWS_ABSOLUTE = Regex("""^[A-Za-z]:[\\/]""")

internal fun isAbsolutePath(path: String): Boolean = path.startsWith('/') || WINDOWS_ABSOLUTE.containsMatchIn(path)
