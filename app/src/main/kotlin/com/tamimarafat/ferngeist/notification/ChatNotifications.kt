package com.tamimarafat.ferngeist.notification

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.tamimarafat.ferngeist.AppForegroundState
import com.tamimarafat.ferngeist.R
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.model.worktreeBranchOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Application lifetime owner of individual chat cards. Called on main, including from the live renderer. */
@Singleton
internal class ChatNotifications
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val hub: ChatConnectionHub,
        private val foreground: AppForegroundState,
    ) {
        private val manager = context.getSystemService(NotificationManager::class.java)
        private val policy = ChatNotificationPolicy()
        private val approvalRenewals = mutableMapOf<CardKey, Job>()
        private lateinit var appScope: CoroutineScope

        fun start(scope: CoroutineScope) {
            appScope = scope
            scope.launch(Dispatchers.Main.immediate) {
                merge(hub.liveTurns, hub.openChatIds, foreground.isForeground)
                    .collect { updateLocal() }
            }
        }

        fun acceptGateway(event: GatewayCardEvent) {
            updateLocal()
            publish(policy.acceptGateway(event, SystemClock.elapsedRealtime()))
        }

        fun setLiveCoverage(chatIds: Set<String>) {
            updateLocal()
            publish(policy.setLiveCoverage(chatIds, SystemClock.elapsedRealtime()))
        }

        private fun updateLocal() {
            val inForeground = foreground.isForeground.value
            val visible = if (inForeground) hub.openChatIds.value else emptySet()
            publish(policy.updateLocal(hub.liveTurns.value, visible, SystemClock.elapsedRealtime(), inForeground))
        }

        private fun publish(changes: List<CardChange>) {
            changes.forEach { change ->
                when (change) {
                    is CardChange.Cancel -> {
                        approvalRenewals.remove(change.key)?.cancel()
                        manager.cancel(change.key.tag, CHAT_CARD_ID)
                    }
                    is CardChange.CancelChat -> cancelChat(change.chatId)
                    is CardChange.Post -> {
                        approvalRenewals.remove(change.key)?.cancel()
                        post(change)
                        if (change.key.purpose == Purpose.APPROVAL && change.timeoutMs == null) {
                            approvalRenewals[change.key] = renewApproval(change)
                        }
                    }
                }
            }
        }

        // Drawer cards can outlive this process and its record of which exact chat they target.
        private fun cancelChat(chatId: String) {
            manager.activeNotifications
                .filter { it.id == CHAT_CARD_ID && it.notification.extras.getString(CHAT_ID_EXTRA) == chatId }
                .forEach { manager.cancel(it.tag, it.id) }
        }

        // Renew only locally confirmed requests which are still in the drawer. Every posting
        // has a platform timeout so a process death cannot strand a confirmed approval.
        private fun renewApproval(card: CardChange.Post): Job =
            appScope.launch(Dispatchers.Main.immediate) {
                val quiet = card.copy(silent = true)
                while (true) {
                    delay(APPROVAL_RENEW_AFTER_MS)
                    val stillPosted =
                        manager.activeNotifications.any {
                            it.id == CHAT_CARD_ID && it.tag == card.key.tag
                        }
                    if (!stillPosted) break
                    post(quiet)
                }
                approvalRenewals.remove(card.key)
            }

        private fun post(card: CardChange.Post) {
            val purpose = card.key.purpose
            val title = notificationTitle(card)
            val body = notificationBody(card)
            val branch = card.gateway?.branch ?: card.target?.cwd?.let(::worktreeBranchOf)
            val builder =
                NotificationCompat
                    .Builder(context, purpose.channelId)
                    .setContentTitle(title)
                    .setContentText(body)
                    // The branch distinguishes chats in different worktrees.
                    .setSubText(branch?.takeIf { it.isNotBlank() })
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setSmallIcon(R.drawable.ic_notification)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setSilent(card.silent)
                    .setContentIntent(chatPendingIntent(context, card.target, card.key.tag))
            val timeout = card.timeoutMs ?: APPROVAL_LIFETIME_MS.takeIf { purpose == Purpose.APPROVAL }
            timeout?.let(builder::setTimeoutAfter)
            card.target?.takeUnless { purpose == Purpose.OTHER }?.let { target ->
                builder.addExtras(Bundle().apply { putString(CHAT_ID_EXTRA, target.chatId) })
            }
            manager.notify(card.key.tag, CHAT_CARD_ID, builder.build())
        }

        private fun notificationTitle(card: CardChange.Post): String {
            val chatTitle = card.target?.title?.ifBlank { null }
            val gateway = card.gateway
            val title =
                when {
                    gateway == null -> chatTitle
                    // The gateway titles every turn end "Turn Complete", even a refusal, and never names the chat.
                    card.key.purpose == Purpose.COMPLETION ->
                        chatTitle ?: context.getString(R.string.notification_turn_ended)
                    else -> gateway.title ?: chatTitle
                }
            return title ?: context.getString(R.string.push_default_title)
        }

        private fun notificationBody(card: CardChange.Post): String {
            card.gateway?.let { return it.body ?: context.getString(R.string.push_default_body) }
            return context.getString(card.key.purpose.localBody)
        }

        private companion object {
            const val APPROVAL_RENEW_AFTER_MS = APPROVAL_LIFETIME_MS - 300_000L
            const val CHAT_CARD_ID = 4
            const val CHAT_ID_EXTRA = "com.tamimarafat.ferngeist.notification.CHAT_ID"
        }
    }
