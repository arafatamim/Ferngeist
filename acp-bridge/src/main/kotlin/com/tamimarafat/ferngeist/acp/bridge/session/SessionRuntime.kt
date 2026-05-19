package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns canonical transcript/session state for one ACP session.
 *
 * SessionRuntime is the single source of truth for a session's UI state. All mutations
 * are serialized through [mutex] to ensure deterministic event ordering. It maintains
 * two copies of state:
 * - [live]: the currently visible state (published to [snapshot])
 * - [buffered]: state accumulated during hydration (session/load replay)
 *
 * Events from the SDK arrive via [onEvent] and are reduced using [SessionMessageReducer]
 * for message changes and a side-field [when] block for auxiliary state (usage, commands,
 * legacy modes, etc.). Config option changes use [SessionConfigPolicy] to centralize
 * compatibility logic between native and legacy options.
 *
 * Implements [SessionStateEngine] to provide a clean interface for the connection
 * and bridge layers without exposing internal implementation details.
 */
class SessionRuntime(
    private val sessionId: String,
) : SessionStateEngine {
    companion object {
        private const val TAG = "TSRuntime"
    }

    /** Internal state snapshot. All fields are immutable; [reduce] produces new instances. */
    private data class RuntimeData(
        val messages: List<ChatMessage> = emptyList(),
        val isStreaming: Boolean = false,
        val usage: SessionUsage? = null,
        val availableCommands: List<CommandInfo> = emptyList(),
        val commandsAdvertised: Boolean = false,
        val nativeConfigOptions: List<SessionConfigOption> = emptyList(),
        val legacyModes: LegacyModeState? = null,
        val legacyModel: LegacyModelState? = null,
    )

    private val mutex = Mutex()
    private var live = RuntimeData()
    private var buffered = RuntimeData()
    private var eventSeq = 0L

    private val _snapshot =
        MutableStateFlow(
            SessionSnapshot(
                sessionId = sessionId,
                loadState = SessionLoadState.READY,
            ),
        )
    override val snapshot: StateFlow<SessionSnapshot> = _snapshot.asStateFlow()

    override suspend fun beginHydration() {
        mutex.withLock {
            debug("beginHydration: clearing buffered/live snapshot view")
            buffered = RuntimeData()
            _snapshot.value =
                _snapshot.value.copy(
                    loadState = SessionLoadState.HYDRATING,
                    messages = emptyList(),
                    isStreaming = false,
                    usage = null,
                    availableCommands = emptyList(),
                    commandsAdvertised = false,
                    error = null,
                )
        }
    }

    override suspend fun completeHydration() {
        mutex.withLock {
            debug(
                "completeHydration: committing buffered messages=${buffered.messages.size}, " +
                    "bufferedStreaming=${buffered.isStreaming}",
            )
            live =
                buffered.copy(
                    messages = SessionMessageReducer.finishStreaming(buffered.messages),
                    isStreaming = false,
                )
            publishLive(loadState = SessionLoadState.READY, error = null)
        }
    }

    override suspend fun failHydration(error: String?) {
        mutex.withLock {
            debug("failHydration: error=${error ?: "unknown"}")
            _snapshot.value =
                _snapshot.value.copy(
                    loadState = SessionLoadState.FAILED,
                    error = error,
                    isStreaming = false,
                )
        }
    }

    override suspend fun markReady() {
        mutex.withLock {
            if (_snapshot.value.loadState == SessionLoadState.HYDRATING) {
                debug("markReady: ignored while HYDRATING")
                return@withLock
            }
            debug("markReady: publishing READY with liveMessages=${live.messages.size}")
            publishLive(loadState = SessionLoadState.READY, error = null)
        }
    }

    override suspend fun onEvent(event: AppSessionEvent) {
        mutex.withLock {
            val seq = ++eventSeq
            val loadState = _snapshot.value.loadState
            debug(
                "event#$seq state=$loadState type=${event::class.simpleName} " +
                    "summary=${summarizeEvent(event)}",
            )
            if (_snapshot.value.loadState == SessionLoadState.HYDRATING) {
                buffered = reduce(buffered, event)
                debug(
                    "event#$seq bufferedApplied messages=${buffered.messages.size} " +
                        "streaming=${buffered.isStreaming}",
                )
                return@withLock
            }
            live = reduce(live, event)
            debug("event#$seq liveApplied messages=${live.messages.size} streaming=${live.isStreaming}")
            publishLive(loadState = SessionLoadState.READY, error = null)
        }
    }

    override suspend fun onLocalPromptStarted(
        text: String,
        images: List<Pair<String, String>>,
    ) {
        mutex.withLock {
            if (_snapshot.value.loadState != SessionLoadState.READY) {
                debug("onLocalPromptStarted ignored: loadState=${_snapshot.value.loadState}")
                return@withLock
            }

            val mappedImages =
                images.map { (base64, mimeType) ->
                    ChatImageData(base64 = base64, mimeType = mimeType)
                }
            debug("onLocalPromptStarted textLen=${text.length}, images=${mappedImages.size}")
            val withUser = SessionMessageReducer.appendLocalUserMessage(live.messages, text, mappedImages)
            val withAssistantPlaceholder = SessionMessageReducer.startStreaming(withUser)

            live =
                live.copy(
                    messages = withAssistantPlaceholder,
                    isStreaming = true,
                )
            publishLive(loadState = SessionLoadState.READY, error = null)
        }
    }

    override suspend fun onPromptSendFailed() {
        mutex.withLock {
            debug("onPromptSendFailed: finishing local streaming placeholder")
            live =
                live.copy(
                    messages = SessionMessageReducer.finishStreaming(live.messages),
                    isStreaming = false,
                )
            publishLive(loadState = SessionLoadState.READY, error = null)
        }
    }

    override suspend fun onLocalCancel() {
        mutex.withLock {
            debug("onLocalCancel: finishing local streaming placeholder")
            live =
                live.copy(
                    messages = SessionMessageReducer.finishStreaming(live.messages),
                    isStreaming = false,
                )
            publishLive(loadState = SessionLoadState.READY, error = null)
        }
    }

    /**
     * Pure function that reduces an event onto the current state.
     *
     * Message list changes are delegated to [SessionMessageReducer.handleEvent].
     * Side-field updates (usage, commands, config options, legacy mode/model) are handled
     * directly here. The branching is straightforward: each event type updates exactly one
     * field or a small related group.
     *
     * Config option value changes use [SessionConfigPolicy.applyValueChange] to ensure
     * type-safe updates without duplicating the origin-handling logic.
     */
    private fun reduce(
        current: RuntimeData,
        event: AppSessionEvent,
    ): RuntimeData {
        var messages = current.messages
        var isStreaming = current.isStreaming
        var usage = current.usage
        var availableCommands = current.availableCommands
        var commandsAdvertised = current.commandsAdvertised
        var nativeConfigOptions = current.nativeConfigOptions
        var legacyModes = current.legacyModes
        var legacyModel = current.legacyModel

        messages =
            when (event) {
                is AppSessionEvent.SessionLoadComplete -> SessionMessageReducer.finishStreaming(messages)
                else -> SessionMessageReducer.handleEvent(messages, event)
            }

        when (event) {
            is AppSessionEvent.TurnComplete -> isStreaming = false
            is AppSessionEvent.SessionLoadComplete -> isStreaming = false
            is AppSessionEvent.UsageUpdated -> {
                usage =
                    SessionUsage(
                        promptTokens = event.promptTokens,
                        completionTokens = event.completionTokens,
                        totalTokens = event.totalTokens,
                        cachedReadTokens = event.cachedReadTokens,
                        contextWindowTokens = event.contextWindowTokens,
                        costAmount = event.costAmount,
                        costCurrency = event.costCurrency,
                    )
            }
            is AppSessionEvent.CommandsUpdated -> {
                availableCommands = event.commands
                commandsAdvertised = true
            }
            is AppSessionEvent.ModeChanged -> {
                legacyModes = (legacyModes ?: LegacyModeState()).copy(currentModeId = event.modeId)
            }
            is AppSessionEvent.ModesUpdated -> {
                legacyModes =
                    LegacyModeState(
                        modes = event.modes,
                        currentModeId = event.currentModeId ?: legacyModes?.currentModeId,
                    )
            }
            is AppSessionEvent.ConfigOptionsUpdated -> {
                nativeConfigOptions = event.options
            }
            is AppSessionEvent.ConfigOptionValueChanged -> {
                // Delegate to the central policy for type-safe value mutation.
                nativeConfigOptions =
                    nativeConfigOptions.map { option ->
                        SessionConfigPolicy.applyValueChange(option, event.optionId, event.value)
                    }
            }
            is AppSessionEvent.LegacyModelOptionsUpdated -> {
                legacyModel =
                    LegacyModelState(
                        choices = event.choices,
                        currentModelId = event.currentModelId,
                    )
            }
            is AppSessionEvent.ModelSelectionConfirmed -> {
                val selectedModel = event.modelId
                if (!selectedModel.isNullOrBlank() && legacyModel != null) {
                    legacyModel = legacyModel.copy(currentModelId = selectedModel)
                }
            }
            else -> Unit
        }

        val derivedStreaming = isStreaming || messages.any { it.isStreaming }
        return current.copy(
            messages = messages,
            isStreaming = derivedStreaming,
            usage = usage,
            availableCommands = availableCommands,
            commandsAdvertised = commandsAdvertised,
            nativeConfigOptions = nativeConfigOptions,
            legacyModes = legacyModes,
            legacyModel = legacyModel,
        )
    }

    /**
     * Publishes the [live] state to the [_snapshot] StateFlow after resolving the effective
     * config options list.
     *
     * Config resolution via [SessionConfigPolicy.resolveEffectiveOptions] unifies native
     * options with synthetic legacy mode/model options. This ensures the UI always sees a
     * complete set of options regardless of what the agent provides natively.
     *
     * The snapshot's `configOptions` field contains the resolved list; `nativeConfigOptions`
     * stays internal to the runtime.
     */
    private fun publishLive(
        loadState: SessionLoadState,
        error: String?,
    ) {
        val effectiveConfigOptions =
            SessionConfigPolicy.resolveEffectiveOptions(
                nativeOptions = live.nativeConfigOptions,
                legacyModes = live.legacyModes,
                legacyModel = live.legacyModel,
            )
        val last = live.messages.lastOrNull()
        val lastRole = last?.role?.name ?: "none"
        val lastLen = last?.content?.length ?: 0
        val lastSegments = last?.segments?.size ?: 0
        _snapshot.value =
            SessionSnapshot(
                sessionId = sessionId,
                loadState = loadState,
                messages = live.messages,
                isStreaming = live.isStreaming,
                usage = live.usage,
                availableCommands = live.availableCommands,
                commandsAdvertised = live.commandsAdvertised,
                configOptions = effectiveConfigOptions,
                error = error,
            )
        debug(
            "publishLive state=$loadState messages=${live.messages.size} streaming=${live.isStreaming} " +
                "lastRole=$lastRole lastLen=$lastLen lastSegments=$lastSegments " +
                "commands=${live.availableCommands.size} configOptions=${effectiveConfigOptions.size}",
        )
    }

    private fun summarizeEvent(event: AppSessionEvent): String =
        when (event) {
            is AppSessionEvent.UserMessage -> "userLen=${event.text.length} append=${event.append}"
            is AppSessionEvent.AgentMessage -> "agentLen=${event.text.length}"
            is AppSessionEvent.AgentThought -> "thoughtLen=${event.text.length}"
            is AppSessionEvent.ToolCallStarted -> "toolCallId=${event.toolCallId} status=${event.status}"
            is AppSessionEvent.ToolCallUpdated -> "toolCallId=${event.toolCallId} status=${event.status}"
            is AppSessionEvent.ToolPermissionRequested -> "toolCallId=${event.toolCallId} options=${event.options.size}"
            is AppSessionEvent.ToolPermissionResolved -> "toolCallId=${event.toolCallId}"
            is AppSessionEvent.ModeChanged -> "modeId=${event.modeId}"
            is AppSessionEvent.ModesUpdated -> "modes=${event.modes.size} current=${event.currentModeId}"
            is AppSessionEvent.ConfigOptionsUpdated -> "configOptions=${event.options.size}"
            is AppSessionEvent.ConfigOptionValueChanged -> "optionId=${event.optionId} value=${event.value}"
            is AppSessionEvent.LegacyModelOptionsUpdated -> "legacyModels=${event.choices.size} current=${event.currentModelId}"
            is AppSessionEvent.ModelSelectionConfirmed -> "modelId=${event.modelId}"
            is AppSessionEvent.PlanUpdated -> "entries=${event.entries.size}"
            is AppSessionEvent.UsageUpdated -> "usageTotal=${event.totalTokens} context=${event.contextWindowTokens}"
            is AppSessionEvent.CommandsUpdated -> "commands=${event.commands.size}"
            is AppSessionEvent.SessionInfoUpdated -> "title=${event.title} updatedAt=${event.updatedAt}"
            is AppSessionEvent.SessionLoadComplete -> "sessionLoadComplete"
            is AppSessionEvent.TurnComplete -> "stopReason=${event.stopReason}"
            is AppSessionEvent.Unknown -> "unknownRawLen=${event.raw.length}"
        }

    private fun debug(message: String) {
        runCatching { android.util.Log.d(TAG, "[$sessionId] $message") }
    }
}