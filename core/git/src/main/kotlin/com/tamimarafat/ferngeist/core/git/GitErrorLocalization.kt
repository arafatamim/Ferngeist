package com.tamimarafat.ferngeist.core.git

import android.content.Context

private const val GIT_DIFF_NO_WORKSPACE_PREFIX = "No gateway workspace connection to load git diff for "
private const val GIT_DIFF_FAILED_PREFIX = "Failed to load git diff for "

/** Localizes gateway Git failures at the presentation boundary. */
fun localizeGitError(
    context: Context,
    message: String,
): String =
    when {
        message.startsWith(GIT_DIFF_NO_WORKSPACE_PREFIX) ->
            context.getString(
                R.string.chat_git_diff_no_workspace,
                message.removePrefix(GIT_DIFF_NO_WORKSPACE_PREFIX),
            )

        message.startsWith(GIT_DIFF_FAILED_PREFIX) ->
            context.getString(
                R.string.chat_git_diff_load_failed,
                message.removePrefix(GIT_DIFF_FAILED_PREFIX),
            )

        else -> gitErrorResources[message]?.let(context::getString) ?: message
    }

private val gitErrorResources: Map<String, Int> =
    mapOf(
        "Failed to load the comparison" to R.string.chat_git_compare_load_failed,
        "No gateway workspace connection to load the comparison" to R.string.chat_git_compare_no_workspace,
        "The gateway did not apply the comparison" to R.string.chat_git_compare_not_honoured,
        "No gateway workspace connection to load history" to R.string.chat_git_history_no_workspace,
        "Failed to load history" to R.string.chat_git_history_load_failed,
        "Failed to load this commit" to R.string.chat_git_commit_load_failed,
    )
