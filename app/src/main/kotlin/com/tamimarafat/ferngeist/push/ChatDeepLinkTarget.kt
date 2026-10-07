package com.tamimarafat.ferngeist.push

import android.content.Intent
import com.tamimarafat.ferngeist.service.FerngeistForegroundService

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
        intent.getStringExtra(FerngeistForegroundService.EXTRA_SERVER_ID) ?: return null
    val sessionId = intent.getStringExtra(FerngeistForegroundService.EXTRA_SESSION_ID) ?: return null
    return ChatDeepLinkTarget(
        serverId = localServerId,
        sessionId = sessionId,
        cwd = intent.getStringExtra(FerngeistForegroundService.EXTRA_CWD) ?: "",
        title = intent.getStringExtra(FerngeistForegroundService.EXTRA_TITLE).orEmpty(),
        gatewayId = intent.getStringExtra(FerngeistForegroundService.EXTRA_GATEWAY_ID),
    )
}
