package com.tamimarafat.ferngeist.push

import com.tamimarafat.ferngeist.core.model.ChatPresence

/**
 * Decides whether an incoming push notification is redundant and should be suppressed.
 *
 * The gateway sends turn-complete (and similar) events whether or not the app is attached
 * over WebSocket, so a user actively watching a session would otherwise be buzzed for every
 * turn. We suppress only the one case where the notification truly adds nothing: the app is
 * in the foreground **and** the user is already viewing the exact session the push targets.
 * Any other case — backgrounded, or focused on a different session — still notifies.
 *
 * The foreground chat is the presence the connection hub publishes for the chat whose
 * screen is open (ChatConnectionHub.onScreenChat). A chat the user backed out of — even
 * one whose transport stays pooled, so the hub still has a tap target — has no on-screen
 * presence and therefore never suppresses: the user is not watching it live, so the push
 * should notify.
 */
object PushNotificationPolicy {
    /**
     * Server matching is done on the **gateway-owned** id, not the local server id.
     * Local ids churn (and can even duplicate) when a gateway is re-paired, so the
     * foreground chat and the push can carry different local ids for the same physical
     * gateway — comparing those produces false negatives. The gateway identity comes
     * straight from the presence entry's [ChatPresence.gatewaySourceId].
     *
     * @param foregroundChat the [ChatPresence] of the chat the user is currently viewing,
     *   or null when no chat screen is open.
     * @param targetGatewayId the gateway-owned id the push names, or null when absent.
     */
    fun shouldSuppress(
        isAppForeground: Boolean,
        foregroundChat: ChatPresence?,
        targetGatewayId: String?,
        targetSessionId: String?,
    ): Boolean {
        if (!isAppForeground || targetSessionId == null) return false
        // The session check above narrows foregroundChat to non-null: equality with a
        // non-null targetSessionId can only hold when the presence exists.
        if (foregroundChat?.sessionId != targetSessionId) return false
        if (gatewayMismatch(foregroundChat.gatewaySourceId, targetGatewayId)) return false
        return true
    }

    /**
     * Returns true when both gateway ids are known and they differ, indicating the
     * on-screen chat belongs to a different gateway than the push targets. A null on
     * either side falls back to the conclusive session-id match in [shouldSuppress]: an
     * unknown foreground gateway cannot disprove a session match, and a push that names
     * no gateway cannot be shown to come from another one.
     */
    private fun gatewayMismatch(
        foregroundGatewayId: String?,
        targetGatewayId: String?,
    ): Boolean = targetGatewayId != null && foregroundGatewayId != null && foregroundGatewayId != targetGatewayId
}
