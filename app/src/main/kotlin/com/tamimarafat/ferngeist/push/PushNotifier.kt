package com.tamimarafat.ferngeist.push

import android.database.sqlite.SQLiteException
import android.util.Log
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectCatalog
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.push.PushPayloadKeys
import com.tamimarafat.ferngeist.core.model.repository.ChatCatalog
import com.tamimarafat.ferngeist.core.model.repository.GatewayAgentBindingRepository
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.notification.ChatDeepLinkTarget
import com.tamimarafat.ferngeist.notification.ChatNotifications
import com.tamimarafat.ferngeist.notification.GatewayCardEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Resolves accepted gateway pushes in arrival order, even after the distributor unbinds. */
@Singleton
internal class PushNotifier
    @Inject
    constructor(
        private val gatewaySourceRepository: GatewaySourceRepository,
        private val gatewayAgentBindingRepository: GatewayAgentBindingRepository,
        private val sessionRepository: ChatCatalog,
        private val pushRegistrar: PushRegistrar,
        private val projectCatalog: ProjectCatalog,
        private val notifications: ChatNotifications,
    ) {
        private data class Push(
            val instance: String,
            val data: Map<String, String>,
        )

        private val queue = Channel<Push>(Channel.UNLIMITED)

        fun enqueue(
            instance: String,
            data: Map<String, String>,
        ) {
            queue.trySend(Push(instance, data))
        }

        fun start(scope: CoroutineScope) {
            scope.launch {
                for (push in queue) {
                    deliver(push)
                }
            }
        }

        private suspend fun deliver(push: Push) {
            try {
                if (!pushRegistrar.acceptsMessageFor(push.instance)) return
                projectCatalog.refresh(push.instance)
                val gateway = gatewaySourceRepository.getGateway(push.instance) ?: return
                val event = gatewayEvent(gateway, push.data) ?: return
                withContext(Dispatchers.Main.immediate) { notifications.acceptGateway(event) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SQLiteException) {
                reportDeliveryFailure(error)
            } catch (error: IllegalStateException) {
                reportDeliveryFailure(error)
            } catch (error: SecurityException) {
                reportDeliveryFailure(error)
            }
        }

        private fun reportDeliveryFailure(error: Exception) {
            // Keep the consumer available without including message or database contents in logs.
            Log.w("PushNotifier", "Could not handle a gateway notification (${error.javaClass.simpleName})")
        }

        private suspend fun gatewayEvent(
            gateway: GatewaySource,
            data: Map<String, String>,
        ): GatewayCardEvent? {
            val wireGatewayId = data[PushPayloadKeys.SERVER_ID]?.takeIf { it.isNotBlank() }
            if (wireGatewayId != null && gateway.gatewayId != null && wireGatewayId != gateway.gatewayId) return null
            val sessionId = data[PushPayloadKeys.SESSION_ID]?.takeIf { it.isNotBlank() }
            val target =
                if (wireGatewayId != null && wireGatewayId == gateway.gatewayId && sessionId != null) {
                    chatTarget(gateway, wireGatewayId, sessionId, data[PushPayloadKeys.CWD])
                } else {
                    null
                }
            return GatewayCardEvent(
                gatewayId = gateway.id,
                sessionId = sessionId,
                target = target,
                purpose = cardPurposeOf(data[PushPayloadKeys.CATEGORY]),
                title = data[PushPayloadKeys.TITLE],
                body = data[PushPayloadKeys.BODY],
                branch = data[PushPayloadKeys.BRANCH],
            )
        }

        /**
         * Navigation routes on the agent binding's id. The gateway may omit the working directory
         * until it sees the session open, and the agent needs a cwd to load it.
         */
        private suspend fun chatTarget(
            gateway: GatewaySource,
            wireGatewayId: String,
            sessionId: String,
            cwd: String?,
        ): ChatDeepLinkTarget? {
            val bindings = gatewayAgentBindingRepository.getBindingsForGateway(gateway.id)
            // getSession matches on the session id alone, so check the binding on the returned row.
            val stored =
                bindings.firstNotNullOfOrNull { binding ->
                    sessionRepository.getSession(binding.id, sessionId)?.takeIf { it.serverId == binding.id }
                }
            val serverId = stored?.serverId ?: bindings.singleOrNull()?.id ?: return null
            return ChatDeepLinkTarget(
                serverId = serverId,
                sessionId = sessionId,
                cwd = cwd?.takeIf { it.isNotBlank() } ?: stored?.cwd.orEmpty(),
                title = stored?.title.orEmpty(),
                gatewayId = wireGatewayId,
            )
        }
    }
