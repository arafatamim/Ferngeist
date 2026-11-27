package com.tamimarafat.ferngeist.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.tamimarafat.ferngeist.MainActivity
import com.tamimarafat.ferngeist.core.model.ChatPresence

/** A resolved chat destination for a notification tap. [serverId] is always the local id. */
data class ChatDeepLinkTarget(
    val serverId: String,
    val sessionId: String,
    val cwd: String,
    val title: String,
    val gatewayId: String?,
)

/**
 * Resolves a notification tap to the chat it should open, from the extras our own
 * notifications (connection and push alike) put on their content intent; null for any
 * other intent.
 */
fun resolveChatDeepLink(intent: Intent): ChatDeepLinkTarget? {
    val localServerId =
        intent.getStringExtra(EXTRA_SERVER_ID) ?: return null
    val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return null
    return ChatDeepLinkTarget(
        serverId = localServerId,
        sessionId = sessionId,
        cwd = intent.getStringExtra(EXTRA_CWD).orEmpty(),
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
        gatewayId = intent.getStringExtra(EXTRA_GATEWAY_ID),
    )
}

// 1.8.0 posted cards with these extra names, and its undismissed cards survive an update; rename
// once no 1.8.x install remains to update from.
private const val EXTRA_SERVER_ID = "com.tamimarafat.ferngeist.extra.SERVER_ID"
private const val EXTRA_SESSION_ID = "com.tamimarafat.ferngeist.extra.SESSION_ID"
private const val EXTRA_CWD = "com.tamimarafat.ferngeist.extra.CWD"
private const val EXTRA_TITLE = "com.tamimarafat.ferngeist.extra.TITLE"
private const val EXTRA_GATEWAY_ID = "com.tamimarafat.ferngeist.extra.GATEWAY_ID"

internal fun ChatPresence.toChatDeepLinkTarget(title: String = ""): ChatDeepLinkTarget =
    ChatDeepLinkTarget(serverId, sessionId, cwd, title, gatewaySourceId)

internal fun chatPendingIntent(
    context: Context,
    target: ChatDeepLinkTarget?,
    identity: String,
): PendingIntent {
    val intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            // PendingIntent matching ignores extras, so give each card its own activity identity.
            identifier = identity
            if (target != null) {
                putExtra(EXTRA_SERVER_ID, target.serverId)
                putExtra(EXTRA_SESSION_ID, target.sessionId)
                putExtra(EXTRA_CWD, target.cwd)
                putExtra(EXTRA_TITLE, target.title)
                putExtra(EXTRA_GATEWAY_ID, target.gatewayId)
            }
        }
    return PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
