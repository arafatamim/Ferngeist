package com.tamimarafat.ferngeist.push

import com.tamimarafat.ferngeist.core.model.store.ActiveChat

/**
 * Decides whether an incoming push notification is redundant and should be suppressed.
 *
 * The gateway sends turn-complete (and similar) events whether or not the app is attached
 * over WebSocket, so a user actively watching a session would otherwise be buzzed for every
 * turn. We suppress only the one case where the notification truly adds nothing: the app is
 * in the foreground **and** the user is already viewing the exact session the push targets.
 * Any other case — backgrounded, or focused on a different session — still notifies.
 */
object PushNotificationPolicy {
    /**
     * Server matching is done on the **gateway-owned** id, not the local server id.
     * Local ids churn (and can even duplicate) when a gateway is re-paired, so the
     * active chat and the push can carry different local ids for the same physical
     * gateway — comparing those produces false negatives. The gatewayId is stable.
     *
     * @param activeChatGatewayId the gateway-owned id of the chat the user is viewing
     *   (resolved from its local server id), or null when it couldn't be resolved.
     * @param targetGatewayId the gateway-owned id the push names, or null when absent.
     */
    fun shouldSuppress(
        isAppForeground: Boolean,
        activeChat: ActiveChat?,
        activeChatGatewayId: String?,
        targetGatewayId: String?,
        targetSessionId: String?,
    ): Boolean {
        if (!isAppForeground || targetSessionId == null) return false
        if (activeChat?.sessionId != targetSessionId) return false
        if (gatewayMismatch(activeChatGatewayId, targetGatewayId)) return false
        return true
    }

    /**
     * Returns true when both gateway ids are known and they differ, indicating the
     * active chat belongs to a different gateway than the push targets.
     * A null on either side falls back to the conclusive session-id match in [shouldSuppress].
     */
    private fun gatewayMismatch(
        activeChatGatewayId: String?,
        targetGatewayId: String?,
    ): Boolean =
        targetGatewayId != null &&
            activeChatGatewayId != null &&
            activeChatGatewayId != targetGatewayId
}
