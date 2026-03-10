package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAgentCapabilities
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpInitializeResult
import com.tamimarafat.ferngeist.acp.bridge.connection.formatAcpErrorMessage
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.acp.bridge.session.SessionSnapshot
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.repository.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class ChatSessionCoordinator(
    private val scope: CoroutineScope,
    private val connectionManager: AcpConnectionManager,
    private val serverRepository: ServerRepository,
    private val serverId: String,
    private val initialSessionId: String,
    private val cwd: String,
    private val sessionLoadTimeoutMs: Long = 20_000L,
    private val trace: (String) -> Unit,
    private val logError: (String, Throwable?) -> Unit,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        suspend fun onLoadStarted()
        suspend fun onSnapshot(snapshot: SessionSnapshot)
        suspend fun onSessionReady()
        suspend fun onSessionStored(sessionId: String, cwd: String, updatedAt: Long)
        suspend fun onLoadFailed(message: String)
        suspend fun onOperationError(message: String, stopStreaming: Boolean)
        suspend fun onStreamingCancelled()
        suspend fun onCancelUnsupported()
        suspend fun onModelUpdated()
        suspend fun onCapabilitiesChanged(capabilities: AcpAgentCapabilities)
    }

    private var activeSessionId: String = initialSessionId
    private var sessionBridge: SessionBridge? = null
    private var bridgeObserverJobs: List<Job> = emptyList()
    private var pendingModelSelectionId: String? = null
    private var currentCapabilities: AcpAgentCapabilities? = null

    suspend fun loadSession() {
        trace("loadSession:start sessionId=$initialSessionId cwd=$cwd")
        callbacks.onLoadStarted()

        if (!ensureConnectedAndInitialized()) {
            callbacks.onLoadFailed("Disconnected. Reconnect to refresh this session.")
            return
        }

        publishCapabilities()

        connectionManager.getSession(initialSessionId)?.let { existing ->
            trace("loadSession:reusingExisting sessionId=$initialSessionId")
            attachSessionBridge(existing)
            callbacks.onSessionReady()
            return
        }

        val capabilities = currentCapabilities ?: connectionManager.agentCapabilities.value
        if (capabilities != null && !capabilities.loadSession) {
            callbacks.onLoadFailed("This agent does not advertise session/load support.")
            return
        }

        var loadTimedOut = false
        val bridge = try {
            withTimeout(sessionLoadTimeoutMs) {
                connectionManager.loadSession(
                    sessionId = initialSessionId,
                    cwd = cwd,
                )
            }
        } catch (_: TimeoutCancellationException) {
            loadTimedOut = true
            null
        }

        if (bridge != null) {
            trace("loadSession:bridgeReady requested=$initialSessionId active=${bridge.sessionId}")
            attachSessionBridge(bridge)
            return
        }

        if (loadTimedOut) {
            val fallbackBridge = runCatching {
                connectionManager.createSession(cwd)
            }.getOrNull()
            if (fallbackBridge != null) {
                trace("loadSession:fallbackCreated active=${fallbackBridge.sessionId}")
                attachSessionBridge(fallbackBridge)
                callbacks.onSessionReady()
                callbacks.onOperationError(
                    message = "Server did not acknowledge session/load. Opened a new live session.",
                    stopStreaming = false,
                )
                return
            }
        }

        val errorMessage = if (loadTimedOut) {
            "Session load timed out. Check server connection and retry."
        } else {
            "Could not load this session. Check connection and retry."
        }
        trace("loadSession:failed timedOut=$loadTimedOut sessionId=$initialSessionId")
        logError("loadSession failed: bridge is null for sessionId=$initialSessionId", null)
        callbacks.onLoadFailed(errorMessage)
    }

    suspend fun sendMessage(text: String, images: List<ChatImageData>) {
        if (text.isBlank() && images.isEmpty()) return

        val bridge = ensureSessionReadyForSend()
        if (bridge == null) {
            trace("sendMessage: bridge missing activeSessionId=$activeSessionId")
            callbacks.onOperationError(
                message = SESSION_NOT_READY_MESSAGE,
                stopStreaming = false,
            )
            return
        }

        val capabilities = connectionManager.agentCapabilities.value
        if (images.isNotEmpty() && capabilities != null && !capabilities.prompt.image) {
            callbacks.onOperationError(
                message = "This agent does not advertise image prompt support.",
                stopStreaming = false,
            )
            return
        }

        val imagePairs = images.map { Pair(it.base64, it.mimeType) }
        try {
            trace("sendMessage: session=${bridge.sessionId} textLen=${text.length} images=${imagePairs.size}")
            bridge.sendPrompt(text, imagePairs)
        } catch (error: Exception) {
            val errorMessage = userFacingSendError(error)
            logError("sendMessage failed for sessionId=$activeSessionId", error)
            trace("sendMessage:error session=$activeSessionId message=${error.message}")
            callbacks.onOperationError(
                message = errorMessage,
                stopStreaming = true,
            )
        }
    }

    suspend fun cancelStreaming() {
        trace("cancelStreaming requested session=$activeSessionId")
        val bridge = requireActiveBridge("cancelStreaming") ?: return
        val result = runCatching { bridge.cancel() }
        val error = result.exceptionOrNull()
        if (error != null) {
            if (isSessionCancelUnsupported(error)) {
                callbacks.onCancelUnsupported()
                return
            }
            callbacks.onOperationError(
                message = "Failed to cancel the current turn",
                stopStreaming = false,
            )
            return
        }
        callbacks.onStreamingCancelled()
    }

    suspend fun setMode(modeId: String) {
        requireActiveBridge("setMode")?.setMode(modeId)
    }

    suspend fun setConfigOption(optionId: String, value: String) {
        val bridge = requireActiveBridge("setConfigOption:$optionId") ?: return
        if (optionId == "model") {
            pendingModelSelectionId = value
            bridge.setModel(value)
        } else {
            bridge.setConfigOption(optionId, value)
        }
    }

    suspend fun grantPermission(toolCallId: String, optionId: String) {
        requireActiveBridge("grantPermission:$toolCallId")?.grantPermission(toolCallId, optionId)
    }

    suspend fun denyPermission(toolCallId: String) {
        requireActiveBridge("denyPermission:$toolCallId")?.denyPermission(toolCallId)
    }

    fun clear() {
        clearBridgeObservers()
    }

    private suspend fun ensureConnectedAndInitialized(): Boolean {
        if (connectionManager.isConnected) {
            publishCapabilities()
            return true
        }
        val server = serverRepository.getServer(serverId) ?: run {
            logError("ensureConnectedAndInitialized: server not found for serverId=$serverId", null)
            return false
        }
        val connected = connectionManager.connect(
            AcpConnectionConfig(
                scheme = server.scheme,
                host = server.host,
                preferredAuthMethodId = server.preferredAuthMethodId,
            )
        )
        if (!connected) return false
        return when (val initializeResult = connectionManager.initialize()) {
            is AcpInitializeResult.Ready -> {
                currentCapabilities = initializeResult.agentCapabilities
                callbacks.onCapabilitiesChanged(initializeResult.agentCapabilities)
                true
            }
            is AcpInitializeResult.AuthenticationRequired -> {
                callbacks.onLoadFailed(
                    "ACP authentication is required for this server. Reconnect from the server list and choose an auth method.",
                )
                false
            }

            null -> false
        }
    }

    private fun bindSessionBridge(bridge: SessionBridge) {
        activeSessionId = bridge.sessionId
        sessionBridge = bridge
        pendingModelSelectionId = null
    }

    private suspend fun attachSessionBridge(bridge: SessionBridge) {
        bindSessionBridge(bridge)
        persistSessionIfNeeded(bridge)
        observeSessionBridge(bridge)
    }

    private fun observeSessionBridge(bridge: SessionBridge) {
        clearBridgeObservers()
        bridgeObserverJobs = listOf(
            scope.launch {
                var lastSignature: String? = null
                bridge.snapshot.collect { snapshot ->
                    val signature = buildString {
                        append("state=")
                        append(snapshot.loadState)
                        append(" messages=")
                        append(snapshot.messages.size)
                        append(" streaming=")
                        append(snapshot.isStreaming)
                        append(" lastId=")
                        append(snapshot.messages.lastOrNull()?.id)
                        append(" lastRole=")
                        append(snapshot.messages.lastOrNull()?.role)
                        append(" lastLen=")
                        append(snapshot.messages.lastOrNull()?.content?.length ?: 0)
                        append(" commands=")
                        append(snapshot.availableCommands.size)
                        append(" modes=")
                        append(snapshot.availableModes.size)
                    }
                    if (signature != lastSignature) {
                        trace("snapshot session=${bridge.sessionId} $signature")
                        lastSignature = signature
                    }
                    callbacks.onSnapshot(snapshot)
                }
            },
            scope.launch {
                bridge.events.collect { event ->
                    trace("event session=${bridge.sessionId} type=${event::class.simpleName}")
                    handleBridgeEvent(event)
                }
            }
        )
    }

    private fun clearBridgeObservers() {
        bridgeObserverJobs.forEach { it.cancel() }
        bridgeObserverJobs = emptyList()
    }

    private suspend fun handleBridgeEvent(event: AppSessionEvent) {
        if (event !is AppSessionEvent.ModelSelectionConfirmed) return

        val pendingModel = pendingModelSelectionId
        if (pendingModel != null &&
            (event.modelId.isNullOrBlank() || event.modelId == pendingModel)
        ) {
            pendingModelSelectionId = null
            callbacks.onModelUpdated()
        }
    }

    private suspend fun ensureSessionReadyForSend(): SessionBridge? {
        sessionBridge?.let { return it }
        if (!ensureConnectedAndInitialized()) return null

        connectionManager.getSession(activeSessionId)?.let { existing ->
            attachSessionBridge(existing)
            callbacks.onSessionReady()
            return existing
        }

        val created = runCatching { connectionManager.createSession(cwd) }.getOrNull() ?: return null
        attachSessionBridge(created)
        callbacks.onSessionReady()
        return created
    }

    private suspend fun requireActiveBridge(action: String): SessionBridge? {
        return sessionBridge ?: run {
            trace("$action: bridge missing activeSessionId=$activeSessionId")
            callbacks.onOperationError(
                message = SESSION_NOT_READY_MESSAGE,
                stopStreaming = false,
            )
            null
        }
    }

    private fun userFacingSendError(error: Throwable): String {
        val detailedMessage = formatAcpErrorMessage(error, "Send failed")
        val raw = error.message.orEmpty()
        return when {
            raw.contains("Request timeout", ignoreCase = true) ->
                "Request timed out. Please try again."
            raw.contains("Invalid params", ignoreCase = true) ->
                "Send failed due to an invalid request format."
            detailedMessage != "Send failed" ->
                detailedMessage
            else ->
                "Send failed due to an unknown error."
        }
    }

    private fun isSessionCancelUnsupported(error: Throwable): Boolean {
        val raw = error.message.orEmpty()
        return raw.contains("Method not found", ignoreCase = true) &&
            raw.contains("session/cancel", ignoreCase = true)
    }

    private suspend fun publishCapabilities() {
        connectionManager.agentCapabilities.value?.let { capabilities ->
            currentCapabilities = capabilities
            callbacks.onCapabilitiesChanged(capabilities)
        }
    }

    private suspend fun persistSessionIfNeeded(bridge: SessionBridge) {
        if (currentCapabilities?.session?.list != false) return
        callbacks.onSessionStored(
            sessionId = bridge.sessionId,
            cwd = cwd,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private companion object {
        const val SESSION_NOT_READY_MESSAGE = "Session is not ready. Please retry in a moment."
    }
}
