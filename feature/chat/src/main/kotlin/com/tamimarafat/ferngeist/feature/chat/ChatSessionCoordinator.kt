package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.feature.chat.BuildConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAgentCapabilities
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticationRequiredException
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpInitializeResult
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.formatAcpErrorMessage
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigCategory
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigValue
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.acp.bridge.session.SessionSnapshot
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.DesktopHelperSource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.DesktopHelperSourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperConnectResponse
import com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperRepository
import com.tamimarafat.ferngeist.feature.serverlist.helper.refreshHelperSourceIfNeeded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

internal class ChatSessionCoordinator(
    private val scope: CoroutineScope,
    private val connectionManager: AcpConnectionManager,
    private val launchableTargetRepository: LaunchableTargetRepository,
    private val helperSourceRepository: DesktopHelperSourceRepository,
    private val helperRepository: DesktopHelperRepository,
    private val serverId: String,
    private val initialSessionId: String,
    private val cwd: String,
    private val sessionLoadTimeoutMs: Long = 20_000L,
    private val bridgeRecoveryRetryDelayMs: Long = 3_000L,
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
    private var bridgeRecoveryJob: Job? = null
    // Legacy model changes are confirmed locally because the current SDK surface does not
    // expose a dedicated CurrentModelUpdate event on the client side.
    private var pendingModelSelectionId: String? = null
    private var currentCapabilities: AcpAgentCapabilities? = null
    private var shouldRecoverBridge: Boolean = false
    private val bridgeOperationMutex = Mutex()

    /**
     * Reattaches chat to an existing ACP session, or falls back to creating a
     * fresh live session when the server never acknowledges session/load.
     */
    suspend fun loadSession() {
        bridgeOperationMutex.withLock {
            shouldRecoverBridge = true
            cancelBridgeRecovery()
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
                shouldRecoverBridge = false
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
            } catch (error: AcpAuthenticationRequiredException) {
                callbacks.onLoadFailed(
                    "ACP authentication is required for this server. Return to the session list and authenticate first.",
                )
                return
            } catch (_: TimeoutCancellationException) {
                loadTimedOut = true
                null
            } catch (error: Exception) {
                if (isDestroyedBridgeStreamError(error)) {
                    val fallbackBridge = runCatching {
                        connectionManager.createSession(cwd)
                    }.getOrNull()
                    if (fallbackBridge != null) {
                        trace("loadSession:bridgeRestartFallback active=${fallbackBridge.sessionId}")
                        attachSessionBridge(fallbackBridge)
                        callbacks.onSessionReady()
                        callbacks.onOperationError(
                            message = "The ACP bridge process restarted while loading this session. Opened a new live session.",
                            stopStreaming = false,
                        )
                        return
                    }
                }
                // Preserve the original session/load failure so provider-specific
                // JSON-RPC errors are shown to the user instead of a generic
                // "Could not load this session" message.
                callbacks.onLoadFailed(formatAcpErrorMessage(error, "Failed to load session"))
                return
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
    }

    fun onConnectionStateChanged(connectionState: AcpConnectionState) {
        when (connectionState) {
            is AcpConnectionState.Connected -> scheduleBridgeRecovery()
            is AcpConnectionState.Connecting,
            is AcpConnectionState.Disconnected,
            is AcpConnectionState.Failed -> invalidateActiveBridge()
        }
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

    suspend fun setConfigOption(optionId: String, value: SessionConfigValue) {
        val bridge = requireActiveBridge("setConfigOption:$optionId") ?: return
        val option = bridge.snapshot.value.configOptions.firstOrNull { it.id == optionId }
        val selectedModelId = if (option?.category is SessionConfigCategory.Model) {
            (value as? SessionConfigValue.StringValue)?.value
        } else {
            null
        }
        if (!selectedModelId.isNullOrBlank()) {
            pendingModelSelectionId = selectedModelId
        }
        bridge.setConfigOption(optionId, value)
    }

    suspend fun grantPermission(toolCallId: String, optionId: String) {
        requireActiveBridge("grantPermission:$toolCallId")?.grantPermission(toolCallId, optionId)
    }

    suspend fun denyPermission(toolCallId: String) {
        requireActiveBridge("denyPermission:$toolCallId")?.denyPermission(toolCallId)
    }

    fun clear() {
        cancelBridgeRecovery()
        invalidateActiveBridge()
        clearBridgeObservers()
    }

    private suspend fun ensureConnectedAndInitialized(): Boolean {
        if (connectionManager.isConnected) {
            publishCapabilities()
            return true
        }
        val server = launchableTargetRepository.getTarget(serverId) ?: run {
            logError("ensureConnectedAndInitialized: target not found for serverId=$serverId", null)
            return false
        }
        val connected = when (server) {
            is LaunchableTarget.HelperAgent -> {
                val config = buildHelperConnectionConfig(server) ?: return false
                connectionManager.connect(config)
            }

            is LaunchableTarget.Manual -> {
                connectionManager.connect(
                    AcpConnectionConfig(
                        scheme = server.server.scheme,
                        host = server.server.host,
                        preferredAuthMethodId = server.server.preferredAuthMethodId,
                        serverDisplayName = server.name,
                    )
                )
            }
        }
        if (!connected) return false
        return when (val initializeResult = connectionManager.initialize()) {
            is AcpInitializeResult.Ready -> {
                currentCapabilities = initializeResult.agentCapabilities
                callbacks.onCapabilitiesChanged(initializeResult.agentCapabilities)
                true
            }
            is AcpInitializeResult.AuthenticationRequired -> {
                shouldRecoverBridge = false
                callbacks.onLoadFailed(
                    "ACP authentication is required for this server. Reconnect from the server list and choose an auth method.",
                )
                false
            }

            null -> false
        }
    }

    private suspend fun buildHelperConnectionConfig(target: LaunchableTarget.HelperAgent): AcpConnectionConfig? {
        val helperSource = target.helperSource
        if (helperSource.helperCredential.isBlank()) {
            callbacks.onLoadFailed("Desktop companion is not paired for ${target.name}.")
            return null
        }
        return try {
            val refreshedSource = refreshHelperSourceIfNeeded(helperSource, helperRepository, helperSourceRepository)
            val runtime = helperRepository.startAgent(
                scheme = refreshedSource.scheme,
                host = refreshedSource.host,
                helperCredential = refreshedSource.helperCredential,
                agentId = target.binding.agentId,
            )
            val handoff = helperRepository.connectRuntime(
                scheme = refreshedSource.scheme,
                host = refreshedSource.host,
                helperCredential = refreshedSource.helperCredential,
                runtimeId = runtime.id,
            )
            AcpConnectionConfig(
                scheme = refreshedSource.scheme,
                host = refreshedSource.host,
                webSocketUrl = resolveDesktopHelperWebSocketUrl(refreshedSource, handoff),
                webSocketBearerToken = handoff.bearerToken,
                preferredAuthMethodId = target.binding.preferredAuthMethodId,
                helperRuntimeId = runtime.id,
                helperSourceId = refreshedSource.id,
                serverDisplayName = target.name,
            )
        } catch (error: Throwable) {
            callbacks.onLoadFailed("Failed to reconnect to ${target.name}: ${error.message ?: "unknown error"}")
            null
        }
    }

    private fun resolveDesktopHelperWebSocketUrl(
        helperSource: DesktopHelperSource,
        handoff: DesktopHelperConnectResponse,
    ): String {
        val advertisedUrl = handoff.webSocketUrl.trim()
        val advertisedHost = runCatching { URI(advertisedUrl).host?.lowercase() }.getOrNull()
        if (advertisedHost != null && !isUnroutableHelperHost(advertisedHost)) {
            return advertisedUrl
        }

        val socketScheme = when (helperSource.scheme.lowercase()) {
            "https", "wss" -> "wss"
            else -> "ws"
        }
        return "$socketScheme://${helperSource.host}${handoff.webSocketPath}"
    }

    private fun isUnroutableHelperHost(host: String): Boolean {
        return host == "0.0.0.0" || host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    private fun bindSessionBridge(bridge: SessionBridge) {
        activeSessionId = bridge.sessionId
        sessionBridge = bridge
        pendingModelSelectionId = null
        shouldRecoverBridge = true
        cancelBridgeRecovery()
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
                    if (BuildConfig.DEBUG) {
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
                            append(" configOptions=")
                            append(snapshot.configOptions.size)
                        }
                        if (signature != lastSignature) {
                            trace("snapshot session=${bridge.sessionId} $signature")
                            lastSignature = signature
                        }
                    }
                    callbacks.onSnapshot(snapshot)
                }
            },
            scope.launch {
                bridge.events.collect { event ->
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

        val created = try {
            connectionManager.createSession(cwd)
        } catch (_: AcpAuthenticationRequiredException) {
            callbacks.onLoadFailed(
                "ACP authentication is required for this server. Return to the session list and authenticate first.",
            )
            null
        } ?: return null
        attachSessionBridge(created)
        callbacks.onSessionReady()
        return created
    }

    private fun scheduleBridgeRecovery() {
        if (!shouldRecoverBridge || sessionBridge != null || bridgeRecoveryJob != null) return

        bridgeRecoveryJob = scope.launch {
            try {
                while (shouldRecoverBridge && sessionBridge == null) {
                    if (!connectionManager.isConnected) break

                    val recoveredBridge = bridgeOperationMutex.withLock {
                        if (sessionBridge != null) {
                            sessionBridge
                        } else {
                            recoverSessionBridge()
                        }
                    }
                    if (recoveredBridge != null) {
                        trace("recoverSessionBridge:recovered sessionId=${recoveredBridge.sessionId}")
                        callbacks.onSessionReady()
                        break
                    }

                    delay(bridgeRecoveryRetryDelayMs)
                }
            } finally {
                bridgeRecoveryJob = null
            }
        }
    }

    private fun cancelBridgeRecovery() {
        bridgeRecoveryJob?.cancel()
        bridgeRecoveryJob = null
    }

    private fun invalidateActiveBridge() {
        sessionBridge = null
        pendingModelSelectionId = null
        clearBridgeObservers()
    }

    private suspend fun recoverSessionBridge(): SessionBridge? {
        connectionManager.getSession(activeSessionId)?.let { existing ->
            attachSessionBridge(existing)
            return existing
        }

        publishCapabilities()
        val capabilities = currentCapabilities ?: connectionManager.agentCapabilities.value
        if (capabilities != null && !capabilities.loadSession) {
            val created = runCatching { connectionManager.createSession(cwd) }.getOrNull() ?: return null
            attachSessionBridge(created)
            return created
        }

        val recovered = try {
            withTimeout(sessionLoadTimeoutMs) {
                connectionManager.loadSession(
                    sessionId = activeSessionId,
                    cwd = cwd,
                )
            }
        } catch (_: AcpAuthenticationRequiredException) {
            callbacks.onLoadFailed(
                "ACP authentication is required for this server. Return to the session list and authenticate first.",
            )
            null
        } catch (_: TimeoutCancellationException) {
            null
        } catch (error: Exception) {
            callbacks.onLoadFailed(formatAcpErrorMessage(error, "Failed to load session"))
            null
        }
        if (recovered != null) {
            attachSessionBridge(recovered)
        }
        return recovered
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

    private fun isDestroyedBridgeStreamError(error: Throwable): Boolean {
        val message = formatAcpErrorMessage(error, "").lowercase()
        return message.contains("write after a stream was destroyed") ||
            message.contains("stream was destroyed")
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
