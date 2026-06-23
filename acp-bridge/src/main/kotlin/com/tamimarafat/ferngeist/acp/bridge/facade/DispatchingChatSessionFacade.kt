package com.tamimarafat.ferngeist.acp.bridge.facade

import com.tamimarafat.ferngeist.core.model.ChatAgentCapabilities
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatOperationError
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import com.tamimarafat.ferngeist.core.model.ChatSessionFacadeFactory
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Routes a chat session to the correct transport-specific [ChatSessionFacade] based on
 * the resolved [LaunchableTarget] type, keeping the Manual ACP / Gateway path on the
 * existing ACP facade and sending [LaunchableTarget.Paseo] targets to the Paseo facade.
 *
 * The delegate is resolved lazily on first use (the target type requires a suspend repo
 * lookup); the facade's state/event flows transparently switch to the delegate once it
 * is chosen.
 */
class DispatchingChatSessionFacadeFactory(
    private val acpFactory: ChatSessionFacadeFactory,
    private val paseoFactory: ChatSessionFacadeFactory,
    private val launchableTargetRepository: LaunchableTargetRepository,
) : ChatSessionFacadeFactory {
    override fun create(
        scope: CoroutineScope,
        serverId: String,
        sessionId: String,
        cwd: String,
    ): ChatSessionFacade =
        DispatchingChatSessionFacade(
            scope = scope,
            serverId = serverId,
            sessionId = sessionId,
            cwd = cwd,
            acpFactory = acpFactory,
            paseoFactory = paseoFactory,
            launchableTargetRepository = launchableTargetRepository,
        )
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class DispatchingChatSessionFacade(
    private val scope: CoroutineScope,
    private val serverId: String,
    private val sessionId: String,
    private val cwd: String,
    private val acpFactory: ChatSessionFacadeFactory,
    private val paseoFactory: ChatSessionFacadeFactory,
    private val launchableTargetRepository: LaunchableTargetRepository,
) : ChatSessionFacade {
    private val delegateFlow = MutableStateFlow<ChatSessionFacade?>(null)
    private val mutex = Mutex()

    override val connectionState: StateFlow<ChatConnectionState> =
        delegateFlow
            .flatMapLatest { it?.connectionState ?: flowOf(ChatConnectionState.Disconnected) }
            .stateIn(scope, SharingStarted.Eagerly, ChatConnectionState.Disconnected)

    override val diagnostics: StateFlow<ChatConnectionDiagnostics> =
        delegateFlow
            .flatMapLatest { it?.diagnostics ?: flowOf(ChatConnectionDiagnostics()) }
            .stateIn(scope, SharingStarted.Eagerly, ChatConnectionDiagnostics())

    override val sessionSnapshot: StateFlow<ChatSessionSnapshot?> =
        delegateFlow
            .flatMapLatest { it?.sessionSnapshot ?: flowOf(null) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    override val agentCapabilities: StateFlow<ChatAgentCapabilities> =
        delegateFlow
            .flatMapLatest { it?.agentCapabilities ?: flowOf(ChatAgentCapabilities()) }
            .stateIn(scope, SharingStarted.Eagerly, ChatAgentCapabilities())

    override val loadFailed: SharedFlow<String> =
        delegateFlow.filterNotNull().flatMapLatest { it.loadFailed }.shareIn(scope, SharingStarted.Eagerly)

    override val operationError: SharedFlow<ChatOperationError> =
        delegateFlow.filterNotNull().flatMapLatest { it.operationError }.shareIn(scope, SharingStarted.Eagerly)

    override val streamingCancelled: SharedFlow<Unit> =
        delegateFlow.filterNotNull().flatMapLatest { it.streamingCancelled }.shareIn(scope, SharingStarted.Eagerly)

    override val cancelUnsupported: SharedFlow<Unit> =
        delegateFlow.filterNotNull().flatMapLatest { it.cancelUnsupported }.shareIn(scope, SharingStarted.Eagerly)

    override val sessionReady: SharedFlow<Unit> =
        delegateFlow.filterNotNull().flatMapLatest { it.sessionReady }.shareIn(scope, SharingStarted.Eagerly)

    override val modelUpdated: SharedFlow<Unit> =
        delegateFlow.filterNotNull().flatMapLatest { it.modelUpdated }.shareIn(scope, SharingStarted.Eagerly)

    private suspend fun delegate(): ChatSessionFacade {
        delegateFlow.value?.let { return it }
        return mutex.withLock {
            delegateFlow.value ?: run {
                val target = launchableTargetRepository.getTarget(serverId)
                val factory = if (target is LaunchableTarget.Paseo) paseoFactory else acpFactory
                factory.create(scope, serverId, sessionId, cwd).also { delegateFlow.value = it }
            }
        }
    }

    override suspend fun loadSession() = delegate().loadSession()

    override suspend fun sendMessage(
        text: String,
        images: List<ChatImageData>,
    ) = delegate().sendMessage(text, images)

    override suspend fun cancelStreaming() = delegate().cancelStreaming()

    override suspend fun setConfigOption(
        optionId: String,
        value: ChatConfigValue,
    ) = delegate().setConfigOption(optionId, value)

    override suspend fun grantPermission(
        toolCallId: String,
        optionId: String,
    ) = delegate().grantPermission(toolCallId, optionId)

    override suspend fun denyPermission(toolCallId: String) = delegate().denyPermission(toolCallId)

    override fun clear() {
        delegateFlow.value?.clear()
    }

    override fun onConnectionStateChanged(connectionState: ChatConnectionState) {
        delegateFlow.value?.onConnectionStateChanged(connectionState)
    }
}
