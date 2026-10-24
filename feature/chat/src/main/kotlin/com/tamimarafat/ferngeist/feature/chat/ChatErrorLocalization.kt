package com.tamimarafat.ferngeist.feature.chat

import android.content.Context
import com.tamimarafat.ferngeist.feature.chat.R

private const val GATEWAY_UNPAIRED_PREFIX = "Gateway is not paired for "
private const val RECONNECT_PREFIX = "Failed to reconnect to "
private const val RECONNECT_SEPARATOR = ": "
private const val GIT_DIFF_NO_WORKSPACE_PREFIX = "No gateway workspace connection to load git diff for "
private const val GIT_DIFF_FAILED_PREFIX = "Failed to load git diff for "

/**
 * Maps the fixed error strings the facade (acp-bridge) and this module emit onto their
 * localized resources, at the point they become user-visible text (snackbar / load-error
 * card body).
 *
 * acp-bridge has no resources of its own, so it emits English. Keys here must stay
 * byte-identical to the emitters — AcpChatSessionFacade, AcpChatSessionFacadeMappers,
 * ChatViewModel and the git loaders — and messages with no resource (agent text, SDK errors)
 * pass through untranslated.
 */
internal fun localizeChatError(
    context: Context,
    message: String,
): String =
    when {
        message.startsWith(GATEWAY_UNPAIRED_PREFIX) ->
            context.getString(
                R.string.chat_session_error_gateway_unpaired,
                message.removePrefix(GATEWAY_UNPAIRED_PREFIX).removeSuffix("."),
            )

        message.startsWith(RECONNECT_PREFIX) && RECONNECT_SEPARATOR in message -> {
            val (server, cause) =
                message.removePrefix(RECONNECT_PREFIX).split(RECONNECT_SEPARATOR, limit = 2)
            context.getString(R.string.chat_session_error_reconnect, server, cause)
        }

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

        else -> chatErrorResources[message]?.let(context::getString) ?: message
    }

private val chatErrorResources: Map<String, Int> =
    mapOf(
        "Disconnected. Reconnect to refresh this session."
            to R.string.chat_session_error_disconnected,
        "This agent does not advertise session/load support."
            to R.string.chat_session_error_no_load,
        "This agent does not advertise image prompt support."
            to R.string.chat_session_error_no_image,
        "This agent does not advertise file attachment support."
            to R.string.chat_session_error_no_file,
        "Session is not ready. Please retry in a moment."
            to R.string.chat_session_error_not_ready,
        "Failed to cancel the current turn"
            to R.string.chat_session_error_cancel_failed,
        "Could not load this session. Check connection and retry."
            to R.string.chat_session_error_could_not_load,
        "Session load timed out. Check server connection and retry."
            to R.string.chat_session_error_load_timeout,
        "Request timed out. Please try again."
            to R.string.chat_session_error_timeout,
        "Send failed due to an invalid request format."
            to R.string.chat_session_error_invalid_request,
        "Send failed due to an unknown error."
            to R.string.chat_session_error_unknown,
        "Failed to send message"
            to R.string.chat_error_send_failed,
        "Cancel is not supported by this server"
            to R.string.chat_cancel_not_supported,
        "Model updated"
            to R.string.chat_model_updated,
        "ACP authentication is required for this server. Return to the session list and authenticate first."
            to R.string.chat_session_error_auth_required,
        "ACP authentication is required for this server. Reconnect from the server list and choose an auth method."
            to R.string.chat_session_error_auth_reconnect,
        "This session is already active elsewhere. Disconnect it from the session list, then reopen."
            to R.string.chat_session_error_already_active,
        "This session is still responding. Cancel or close it from inside the chat first."
            to R.string.chat_session_error_close_streaming,
        "Close is only available for gateway sessions."
            to R.string.chat_session_error_close_gateway_only,
        "Failed to close session."
            to R.string.chat_session_error_close_failed,
        "The connection to the ACP bridge was lost while loading this session. Opened a new live session."
            to R.string.chat_session_error_bridge_connection_lost,
        "Failed to load the comparison"
            to R.string.chat_git_compare_load_failed,
        "No gateway workspace connection to load the comparison"
            to R.string.chat_git_compare_no_workspace,
        "The gateway did not apply the comparison"
            to R.string.chat_git_compare_not_honoured,
        "No gateway workspace connection to load history"
            to R.string.chat_git_history_no_workspace,
        "Failed to load history"
            to R.string.chat_git_history_load_failed,
        "Failed to load this commit"
            to R.string.chat_git_commit_load_failed,
        "No gateway workspace connection to read the file"
            to R.string.chat_file_no_workspace,
    )
