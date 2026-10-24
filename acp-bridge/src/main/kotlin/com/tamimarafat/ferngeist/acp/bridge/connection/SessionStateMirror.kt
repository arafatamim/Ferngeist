package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientSession
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigChoice
import com.tamimarafat.ferngeist.acp.bridge.session.SessionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/*
 * Mirrors a [ClientSession]'s capability state (modes, models, config options) into app events.
 * The SDK exposes these as plain lists and StateFlows that [SessionGateway] would otherwise have
 * to translate inline; keeping the mapping here leaves the gateway to the RPC paths.
 */

/**
 * Emits the session's initial capability state. Called before the bridge is marked ready, so
 * the events land in its replay buffer.
 */
@OptIn(UnstableApi::class)
internal suspend fun ClientSession.pushInitialState(emit: suspend (AppSessionEvent) -> Unit) {
    // One-shot initial reads of availableModes/availableModels (plain List, not
    // StateFlow, so the SDK doesn't expose change notifications for them) and
    // of currentMode/currentModel/configOptions. The initial values land in the
    // bridge replay buffer before markReady(). After that, observeStateChanges
    // forwards subsequent changes so the UI updates when the agent mutates them.
    if (modesSupported) pushInitialModes(emit)
    if (modelsSupported) pushInitialModels(emit)
    if (configOptionsSupported) pushInitialConfigOptions(emit)
}

@OptIn(UnstableApi::class)
private suspend fun ClientSession.pushInitialModes(emit: suspend (AppSessionEvent) -> Unit) {
    val modes =
        availableModes.map {
            SessionMode(
                id = it.id.value,
                name = it.name,
                description = it.description,
            )
        }
    emit(
        AppSessionEvent.ModesUpdated(
            modes = modes,
            currentModeId = currentMode.value.value,
        ),
    )
}

@OptIn(UnstableApi::class)
private suspend fun ClientSession.pushInitialModels(emit: suspend (AppSessionEvent) -> Unit) {
    val current = currentModel.value.value
    val modelChoices =
        availableModels.map { model ->
            SessionConfigChoice(
                id = model.modelId.value,
                label = model.name,
                value = model.modelId.value,
                description = model.description,
            )
        }
    emit(
        AppSessionEvent.LegacyModelOptionsUpdated(
            choices = modelChoices,
            currentModelId = current,
        ),
    )
    // The SDK exposes currentModel on the session object but not a
    // corresponding SessionUpdate.CurrentModelUpdate event for
    // notify() consumers, so we mirror the initial selection into app state.
    emit(AppSessionEvent.ModelSelectionConfirmed(current))
}

@OptIn(UnstableApi::class)
private suspend fun ClientSession.pushInitialConfigOptions(emit: suspend (AppSessionEvent) -> Unit) {
    emit(
        AppSessionEvent.ConfigOptionsUpdated(
            options =
                configOptions.value.map(
                    AcpSessionUpdateMapper::mapSdkConfigOption,
                ),
        ),
    )
}

/**
 * Forwards later changes to the session's capability state through [emit]. Each subscription
 * runs in [scope]; the returned jobs are cancelled when the session is cleared.
 *
 * `drop(1)` skips the current value, which [pushInitialState] already emitted.
 */
@OptIn(UnstableApi::class)
internal fun ClientSession.observeStateChanges(
    scope: CoroutineScope,
    emit: suspend (AppSessionEvent) -> Unit,
): List<Job> =
    buildList {
        if (modesSupported) add(observeModes(scope, emit))
        if (modelsSupported) add(observeModels(scope, emit))
        if (configOptionsSupported) add(observeConfigOptions(scope, emit))
    }

@OptIn(UnstableApi::class)
private fun ClientSession.observeModes(
    scope: CoroutineScope,
    emit: suspend (AppSessionEvent) -> Unit,
): Job {
    val availableModesSnapshot = availableModes
    return currentMode
        .drop(1)
        .onEach { newModeId ->
            emit(
                AppSessionEvent.ModesUpdated(
                    modes =
                        availableModesSnapshot.map { mode ->
                            SessionMode(
                                id = mode.id.value,
                                name = mode.name,
                                description = mode.description,
                            )
                        },
                    currentModeId = newModeId.value,
                ),
            )
        }.launchIn(scope)
}

@OptIn(UnstableApi::class)
private fun ClientSession.observeModels(
    scope: CoroutineScope,
    emit: suspend (AppSessionEvent) -> Unit,
): Job =
    currentModel
        .drop(1)
        .onEach { newModelId ->
            emit(AppSessionEvent.ModelSelectionConfirmed(newModelId.value))
        }.launchIn(scope)

@OptIn(UnstableApi::class)
private fun ClientSession.observeConfigOptions(
    scope: CoroutineScope,
    emit: suspend (AppSessionEvent) -> Unit,
): Job =
    configOptions
        .drop(1)
        .onEach { newOptions ->
            emit(
                AppSessionEvent.ConfigOptionsUpdated(
                    options = newOptions.map(AcpSessionUpdateMapper::mapSdkConfigOption),
                ),
            )
        }.launchIn(scope)
