package com.tamimarafat.ferngeist.acp.bridge.paseo

import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigValue
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPermissionOption
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPort
import com.tamimarafat.ferngeist.acp.bridge.session.SessionRuntime
import com.tamimarafat.ferngeist.acp.bridge.session.SessionSnapshot
import com.tamimarafat.ferngeist.acp.bridge.session.SessionStateEngine
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/** A permission action offered by Paseo (e.g. allow once, allow always, deny). */
internal data class PaseoPermissionAction(
    val id: String,
    val label: String,
    val behavior: String,
)

private data class PermissionRecord(
    val requestId: String,
    val actions: List<PaseoPermissionAction>,
)

/** Side-channel describing a Paseo `attention_required` event for the notification layer. */
data class PaseoAttention(
    val agentId: String,
    val reason: String,
    val shouldNotify: Boolean,
    val title: String?,
    val body: String?,
)

/**
 * Actions a [PaseoSessionPort] dispatches back to the daemon. Implemented by
 * [PaseoConnectionManager], bound to a single connected source.
 */
internal interface PaseoSessionActions {
    suspend fun sendMessage(
        agentId: String,
        text: String,
        images: List<Pair<String, String>>,
    )

    suspend fun cancel(agentId: String)

    suspend fun respondPermission(
        agentId: String,
        requestId: String,
        allow: Boolean,
        selectedActionId: String?,
    )

    suspend fun setModel(
        agentId: String,
        modelId: String?,
    )

    suspend fun setMode(
        agentId: String,
        modeId: String,
    )
}

/**
 * [SessionPort] implementation over a reused [SessionRuntime], driven by Paseo
 * `agent_stream` events. One port per Paseo agent (== Ferngeist session).
 */
internal class PaseoSessionPort(
    override val sessionId: String,
    private val actions: PaseoSessionActions,
    private val onAttention: (PaseoAttention) -> Unit,
) : SessionPort {
    private val runtime: SessionStateEngine = SessionRuntime(sessionId = sessionId)
    override val snapshot: StateFlow<SessionSnapshot> = runtime.snapshot

    // The snapshot already carries the current model; no separate confirmation stream is needed.
    override val modelSelectionEvents: SharedFlow<AppSessionEvent.ModelSelectionConfirmed>? = null

    // toolCallId (== Paseo permission id) -> record for building the response.
    private val permissions = ConcurrentHashMap<String, PermissionRecord>()

    // ---- Hydration lifecycle (driven by PaseoConnectionManager) ----

    suspend fun beginHydration() = runtime.beginHydration()

    suspend fun completeHydration() = runtime.completeHydration()

    suspend fun failHydration(error: String?) = runtime.failHydration(error)

    suspend fun markReady() = runtime.markReady()

    suspend fun emitEvent(event: AppSessionEvent) = runtime.onEvent(event)

    /** Replays a historical timeline item (resume/hydration). */
    suspend fun ingestTimelineItem(item: JsonObject) {
        PaseoEventMapper.mapTimelineItem(item).forEach { runtime.onEvent(it) }
    }

    /** Handles a live `AgentStreamEvent` object. */
    suspend fun ingestStreamEvent(event: JsonObject) {
        when (event.typeTag()) {
            "permission_requested" -> event.obj("request")?.let { handlePermissionRequest(it) }
            "permission_resolved" -> handlePermissionResolved(event.str("requestId"))
            "attention_required" -> handleAttention(event)
            else -> PaseoEventMapper.mapStreamEvent(event).forEach { runtime.onEvent(it) }
        }
    }

    /** Handles a top-level `agent_permission_request` payload (the `request` object). */
    suspend fun handlePermissionRequest(request: JsonObject) {
        val id = request.firstString("id", "requestId") ?: return
        val title = request.firstString("title", "name")
        val parsedActions =
            request.arr("actions")?.mapNotNull { el ->
                val o = el.asObjectOrNull() ?: return@mapNotNull null
                val actionId = o.str("id") ?: return@mapNotNull null
                PaseoPermissionAction(
                    id = actionId,
                    label = o.firstString("label") ?: actionId,
                    behavior = o.str("behavior") ?: "allow",
                )
            } ?: emptyList()

        val options =
            if (parsedActions.isNotEmpty()) {
                parsedActions.map { SessionPermissionOption(id = it.id, label = it.label, kind = it.behavior) }
            } else {
                listOf(
                    SessionPermissionOption(SENTINEL_ALLOW, "Allow", "allow"),
                    SessionPermissionOption(SENTINEL_DENY, "Deny", "deny"),
                )
            }

        permissions[id] = PermissionRecord(requestId = id, actions = parsedActions)
        runtime.onEvent(
            AppSessionEvent.ToolPermissionRequested(
                toolCallId = id,
                requestId = id,
                title = title,
                options = options,
            ),
        )
    }

    suspend fun handlePermissionResolved(requestId: String?) {
        val id = requestId ?: return
        permissions.remove(id)
        runtime.onEvent(AppSessionEvent.ToolPermissionResolved(toolCallId = id))
    }

    private fun handleAttention(event: JsonObject) {
        val notification = event.obj("notification")
        onAttention(
            PaseoAttention(
                agentId = sessionId,
                reason = event.str("reason") ?: "finished",
                shouldNotify = event.boolOrNull("shouldNotify") ?: false,
                title = notification?.firstString("title"),
                body = notification?.firstString("body", "message"),
            ),
        )
    }

    // ---- SessionPort actions ----

    override suspend fun sendPrompt(
        text: String,
        images: List<Pair<String, String>>,
    ) {
        runtime.onLocalPromptStarted(text, images)
        try {
            actions.sendMessage(sessionId, text, images)
        } catch (t: Throwable) {
            runtime.onPromptSendFailed()
            throw t
        }
    }

    override suspend fun cancel() {
        actions.cancel(sessionId)
        runtime.onLocalCancel()
    }

    override suspend fun setConfigOption(
        optionId: String,
        value: SessionConfigValue,
    ) {
        val stringValue = (value as? SessionConfigValue.StringValue)?.value
        when (optionId) {
            CONFIG_MODEL -> {
                actions.setModel(sessionId, stringValue)
                runtime.onEvent(AppSessionEvent.ModelSelectionConfirmed(stringValue))
                runtime.onEvent(AppSessionEvent.ConfigOptionValueChanged(optionId, value))
            }

            CONFIG_MODE -> {
                if (stringValue != null) {
                    actions.setMode(sessionId, stringValue)
                    runtime.onEvent(AppSessionEvent.ConfigOptionValueChanged(optionId, value))
                }
            }

            else -> Unit
        }
    }

    override suspend fun grantPermission(
        toolCallId: String,
        optionId: String,
    ) {
        val record = permissions[toolCallId]
        val action = record?.actions?.firstOrNull { it.id == optionId }
        val allow = action?.behavior != "deny" && optionId != SENTINEL_DENY
        val selectedActionId = if (optionId == SENTINEL_ALLOW || optionId == SENTINEL_DENY) null else optionId
        actions.respondPermission(
            agentId = sessionId,
            requestId = record?.requestId ?: toolCallId,
            allow = allow,
            selectedActionId = selectedActionId,
        )
        handlePermissionResolved(toolCallId)
    }

    override suspend fun denyPermission(toolCallId: String) {
        val record = permissions[toolCallId]
        actions.respondPermission(
            agentId = sessionId,
            requestId = record?.requestId ?: toolCallId,
            allow = false,
            selectedActionId = null,
        )
        handlePermissionResolved(toolCallId)
    }

    companion object {
        const val CONFIG_MODEL = "model"
        const val CONFIG_MODE = "mode"
        private const val SENTINEL_ALLOW = "__paseo_allow"
        private const val SENTINEL_DENY = "__paseo_deny"
    }
}
