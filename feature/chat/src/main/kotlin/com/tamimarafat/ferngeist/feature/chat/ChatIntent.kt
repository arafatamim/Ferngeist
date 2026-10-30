package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.ChatElicitationValue
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatGitRef
import com.tamimarafat.ferngeist.core.model.ChatImageData

/** User intents emitted from the chat UI. */
sealed interface ChatIntent {
    data class SendMessage(
        val text: String,
        val images: List<ChatImageData> = emptyList(),
        val files: List<ChatFileData> = emptyList(),
    ) : ChatIntent

    data object CancelStreaming : ChatIntent

    data class SetConfigOption(
        val optionId: String,
        val value: ChatConfigValue,
    ) : ChatIntent

    data class GrantPermission(
        val toolCallId: String,
        val optionId: String,
    ) : ChatIntent

    data class DenyPermission(
        val toolCallId: String,
    ) : ChatIntent

    data class SubmitElicitation(
        val key: String,
        val values: Map<String, ChatElicitationValue>,
    ) : ChatIntent

    data class DeclineElicitation(
        val key: String,
    ) : ChatIntent

    data class CancelElicitation(
        val key: String,
    ) : ChatIntent

    data object RetryLoad : ChatIntent

    /** Retry sending a previously failed (or queued) message identified by its [clientId]. */
    data class RetryMessage(
        val clientId: String,
    ) : ChatIntent

    /** Stop the turn and send this QUEUED prompt, with those queued ahead of it, right away. */
    data class ForceSendMessage(
        val clientId: String,
    ) : ChatIntent

    /** Drop a QUEUED or FAILED prompt without sending it. */
    data class RemoveQueuedMessage(
        val clientId: String,
    ) : ChatIntent

    /** Re-fetch the git status, and history once it is loaded, for the gateway-backed working tree (e.g. after a turn). */
    data object RefreshGitStatus : ChatIntent

    /**
     * Load the diff for a single changed file from the gateway-backed working tree, or from
     * [commit] against its first parent. [oldPath] names a renamed file's old side.
     */
    data class LoadGitDiff(
        val path: String,
        val commit: String? = null,
        val oldPath: String? = null,
    ) : ChatIntent

    /**
     * Load a latest-turn file's working-tree diff for inline display in the turn's
     * close, when its tool calls reported no diff. [path] is git-relative;
     * [oldPath] names a renamed file's old side. [revision] identifies the git entry
     * asked about: cached per path and revision, so a changed entry refetches and a
     * failed path retries on the next request.
     */
    data class LoadTurnFileDiff(
        val path: String,
        val oldPath: String? = null,
        val revision: String = "",
    ) : ChatIntent

    /**
     * Open a file in the file sheet, read at the revision the reader is looking at: the working
     * tree's copy by default, the copy [commit] holds, or the working tree marked against the
     * compare ref [compare]. [oldPath] names a renamed file's old side.
     */
    data class OpenFile(
        val path: String,
        val commit: String? = null,
        val compare: String? = null,
        val oldPath: String? = null,
    ) : ChatIntent

    /** Close the file sheet. */
    data object CloseFile : ChatIntent

    /** Reload history from `HEAD`, keeping the current list until it lands, or append the next page ([more]). */
    data class LoadGitLog(
        val more: Boolean,
    ) : ChatIntent

    /** Load the refs the git sheet can compare against. */
    data object LoadGitRefs : ChatIntent

    /** Compare the git sheet against [ref]; null returns to the working tree. */
    data class SelectCompareRef(
        val ref: ChatGitRef?,
    ) : ChatIntent

    /** Load the files a commit changed. */
    data class LoadGitCommit(
        val sha: String,
    ) : ChatIntent

    /** Consumes the switcher bubble's first-run swipe hint so it never shows again. */
    data object MarkSwitcherHintSeen : ChatIntent

    /**
     * Releases a live session the switcher listed, the way the session
     * list's Disconnect does. Carries the row's own ids: the sheet lists every
     * warm server, so the target is not always this chat's server.
     */
    data class CloseSession(
        val serverId: String,
        val sessionId: String,
    ) : ChatIntent
}
