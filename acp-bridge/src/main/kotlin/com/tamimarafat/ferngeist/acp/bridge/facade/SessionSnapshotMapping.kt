package com.tamimarafat.ferngeist.acp.bridge.facade

import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigCategory
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigChoice
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOption
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigValue
import com.tamimarafat.ferngeist.acp.bridge.session.SessionLoadState
import com.tamimarafat.ferngeist.acp.bridge.session.SessionSnapshot
import com.tamimarafat.ferngeist.core.model.ChatCommand
import com.tamimarafat.ferngeist.core.model.ChatConfigCategory
import com.tamimarafat.ferngeist.core.model.ChatConfigChoice
import com.tamimarafat.ferngeist.core.model.ChatConfigChoiceGroup
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.ChatLoadState
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.UsageState

/**
 * Transport-agnostic mapping from the bridge-layer [SessionSnapshot]/config types to
 * the chat-domain equivalents. Shared by the ACP and Paseo facades — these mappings
 * never depend on the underlying transport.
 */
internal object SessionSnapshotMapping {
    fun mapSnapshot(snapshot: SessionSnapshot): ChatSessionSnapshot =
        ChatSessionSnapshot(
            loadState = mapLoadState(snapshot.loadState),
            messages = snapshot.messages,
            isStreaming = snapshot.isStreaming,
            configOptions = snapshot.configOptions.map { mapConfigOption(it) },
            availableCommands = snapshot.availableCommands.map { ChatCommand(it.name, it.description) },
            commandsAdvertised = snapshot.commandsAdvertised,
            error = snapshot.error,
            usage =
                snapshot.usage?.let {
                    UsageState(
                        promptTokens = it.promptTokens,
                        completionTokens = it.completionTokens,
                        totalTokens = it.totalTokens,
                        cachedReadTokens = it.cachedReadTokens,
                        contextWindowTokens = it.contextWindowTokens,
                        costAmount = it.costAmount,
                        costCurrency = it.costCurrency,
                    )
                },
        )

    fun mapLoadState(state: SessionLoadState): ChatLoadState =
        when (state) {
            // Treat IDLE as hydrating so the UI shows a loading state until a snapshot arrives.
            SessionLoadState.IDLE -> ChatLoadState.HYDRATING
            SessionLoadState.HYDRATING -> ChatLoadState.HYDRATING
            SessionLoadState.READY -> ChatLoadState.READY
            SessionLoadState.FAILED -> ChatLoadState.FAILED
        }

    fun mapConfigOption(option: SessionConfigOption): ChatConfigOption {
        val category = option.category?.let { mapConfigCategory(it) }
        return when (option) {
            is SessionConfigOption.Select ->
                ChatConfigOption.Select(
                    id = option.id,
                    name = option.name,
                    description = option.description,
                    category = category,
                    currentValue = option.currentValue,
                    choices = option.choices.map { mapChoice(it) },
                    groups =
                        option.groups.map { group ->
                            ChatConfigChoiceGroup(
                                id = group.id,
                                label = group.label,
                                choices = group.choices.map { mapChoice(it) },
                            )
                        },
                )
            is SessionConfigOption.BooleanOption ->
                ChatConfigOption.BooleanOption(
                    id = option.id,
                    name = option.name,
                    description = option.description,
                    category = category,
                    currentValue = option.currentValue,
                )
            is SessionConfigOption.Unknown ->
                ChatConfigOption.Unknown(
                    id = option.id,
                    name = option.name,
                    description = option.description,
                    category = category,
                    kind = option.kind,
                    currentValue = option.currentValue?.let { mapConfigValue(it) },
                )
        }
    }

    fun mapConfigCategory(category: SessionConfigCategory): ChatConfigCategory =
        when (category) {
            SessionConfigCategory.Mode -> ChatConfigCategory.Mode
            SessionConfigCategory.Model -> ChatConfigCategory.Model
            is SessionConfigCategory.Custom -> ChatConfigCategory.Custom(category.rawValue)
        }

    fun mapChoice(choice: SessionConfigChoice): ChatConfigChoice =
        ChatConfigChoice(
            id = choice.id,
            label = choice.label,
            value = choice.value,
            description = choice.description,
        )

    fun mapConfigValue(value: SessionConfigValue): ChatConfigValue =
        when (value) {
            is SessionConfigValue.StringValue -> ChatConfigValue.StringValue(value.value)
            is SessionConfigValue.BoolValue -> ChatConfigValue.BoolValue(value.value)
            is SessionConfigValue.UnknownValue -> ChatConfigValue.UnknownValue(value.debugValue)
        }

    fun toBridgeConfigValue(value: ChatConfigValue): SessionConfigValue =
        when (value) {
            is ChatConfigValue.StringValue -> SessionConfigValue.StringValue(value.value)
            is ChatConfigValue.BoolValue -> SessionConfigValue.BoolValue(value.value)
            is ChatConfigValue.UnknownValue -> SessionConfigValue.UnknownValue(value.debugValue)
        }
}
