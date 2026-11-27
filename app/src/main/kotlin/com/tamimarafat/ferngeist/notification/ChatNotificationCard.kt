package com.tamimarafat.ferngeist.notification

import androidx.annotation.StringRes
import com.tamimarafat.ferngeist.R
import com.tamimarafat.ferngeist.core.model.ChatPresence
import com.tamimarafat.ferngeist.core.model.chatIdFor

internal const val APPROVAL_LIFETIME_MS = 3_600_000L

/**
 * What a card is for. On Android O+ the [channelId], not per-notification priority, decides
 * whether a card interrupts; a [quiet] purpose never sounds, even on its first post.
 */
internal enum class Purpose(
    val channelId: String,
    val quiet: Boolean,
    @param:StringRes val localBody: Int,
) {
    PROGRESS(UPDATES_CHANNEL_ID, quiet = true, R.string.push_default_body),
    APPROVAL(ALERTS_CHANNEL_ID, quiet = false, R.string.live_update_needs_you_alert),
    FAILURE(ALERTS_CHANNEL_ID, quiet = false, R.string.notification_turn_failed),
    COMPLETION(UPDATES_CHANNEL_ID, quiet = true, R.string.notification_turn_finished),
    OTHER(UPDATES_CHANNEL_ID, quiet = false, R.string.push_default_body),
    ;

    /** The other outcome of the same turn, which this outcome replaces. */
    val replaces: Purpose?
        get() =
            when (this) {
                COMPLETION -> FAILURE
                FAILURE -> COMPLETION
                else -> null
            }

    val isTerminal: Boolean get() = replaces != null
}

/** Whose card it is: each scope holds at most one card per [Purpose]. */
internal sealed interface ChatCardScope {
    /** A manual agent's chat, under the hub's chat id. */
    data class Local(
        val chatId: String,
    ) : ChatCardScope

    /** A gateway chat; [sessionId] is null when the push named none. */
    data class Gateway(
        val gatewayId: String,
        val sessionId: String?,
    ) : ChatCardScope

    /**
     * A gateway maintenance message, which never replaces another. [sequence] restarts with the
     * process, so [postedAtMs] keeps a new card off one an earlier process posted.
     */
    data class Maintenance(
        val gatewayId: String,
        val postedAtMs: Long,
        val sequence: Long,
    ) : ChatCardScope

    companion object {
        fun of(presence: ChatPresence): ChatCardScope =
            presence.gatewaySourceId?.let { Gateway(it, presence.sessionId) }
                ?: Local(chatIdFor(presence.serverId, presence.sessionId))
    }
}

internal data class CardKey(
    val scope: ChatCardScope,
    val purpose: Purpose,
)

// A length prefix keeps both the absent-session case and arbitrary wire ids distinct.
internal val CardKey.tag: String
    get() {
        val (owner, subject) =
            when (scope) {
                is ChatCardScope.Local -> null to scope.chatId
                is ChatCardScope.Gateway -> scope.gatewayId to scope.sessionId
                is ChatCardScope.Maintenance -> scope.gatewayId to "${scope.postedAtMs}/${scope.sequence}"
            }
        return "chat:${segment(owner)}${segment(subject)}${purpose.name}"
    }

private fun segment(value: String?): String = if (value == null) "-:" else "${value.length}:$value"

internal sealed interface CardChange {
    data class Post(
        val key: CardKey,
        val target: ChatDeepLinkTarget?,
        val silent: Boolean,
        val timeoutMs: Long? = null,
        val gateway: GatewayCardEvent? = null,
    ) : CardChange

    data class Cancel(
        val key: CardKey,
    ) : CardChange

    /** Cancel every drawer card that opens [chatId], including ones an earlier process posted. */
    data class CancelChat(
        val chatId: String,
    ) : CardChange
}

internal data class GatewayCardEvent(
    val gatewayId: String,
    val sessionId: String?,
    val target: ChatDeepLinkTarget?,
    val purpose: Purpose,
    val title: String?,
    val body: String?,
    val branch: String?,
)

internal val GatewayCardEvent.scope: ChatCardScope get() = ChatCardScope.Gateway(gatewayId, sessionId)

internal val ChatDeepLinkTarget.chatId: String get() = chatIdFor(serverId, sessionId)
