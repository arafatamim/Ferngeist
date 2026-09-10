package com.tamimarafat.ferngeist.core.model

/**
 * Identity + chrome a consumer needs for one tracked chat: the notification
 * tap target and the push-suppression foreground check. Transport facts stay
 * in ChatConnectionHub; this type is the value crossing the seam.
 */
data class ChatPresence(
    val serverId: String,
    val sessionId: String,
    val cwd: String = "",
    val gatewaySourceId: String? = null,
)
