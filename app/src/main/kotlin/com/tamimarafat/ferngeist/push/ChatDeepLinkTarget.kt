package com.tamimarafat.ferngeist.push

import android.content.Intent
import com.tamimarafat.ferngeist.core.model.push.FcmPayloadKeys
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
 * Resolves a notification tap to the chat it should open, preferring our own content intent's
 * extras and falling back to a raw FCM data payload (the app-killed case).
 *
 * [translateGatewayId] maps a push's gateway-owned server id onto the local one; a push naming a
 * gateway this install does not have resolves to null and the tap is ignored.
 */
suspend fun resolveChatDeepLink(
    intent: Intent,
    translateGatewayId: suspend (String) -> String?,
): ChatDeepLinkTarget? {
    buildLocalServerDeepLinkTarget(intent)?.let { return it }
    return buildFcmDeepLinkTargetOrNull(intent, translateGatewayId)
}

private suspend fun buildFcmDeepLinkTargetOrNull(
    intent: Intent,
    translateGatewayId: suspend (String) -> String?,
): ChatDeepLinkTarget? {
    val gatewayId = intent.getStringExtra(FcmPayloadKeys.SERVER_ID) ?: return null
    val sessionId = intent.getStringExtra(FcmPayloadKeys.SESSION_ID) ?: return null
    val mappedServerId = translateGatewayId(gatewayId) ?: return null
    return buildFcmDeepLinkTarget(intent, mappedServerId, sessionId, gatewayId)
}

private fun buildLocalServerDeepLinkTarget(intent: Intent): ChatDeepLinkTarget? {
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

private fun buildFcmDeepLinkTarget(
    intent: Intent,
    serverId: String,
    sessionId: String,
    gatewayId: String,
): ChatDeepLinkTarget =
    ChatDeepLinkTarget(
        serverId = serverId,
        sessionId = sessionId,
        cwd = intent.getStringExtra(FcmPayloadKeys.CWD) ?: "",
        title = "",
        gatewayId = gatewayId,
    )
