package com.tamimarafat.ferngeist.acp.bridge.session

sealed interface SessionConfigCategory {
    val rawValue: String

    data object Mode : SessionConfigCategory {
        override val rawValue: String = "mode"
    }

    data object Model : SessionConfigCategory {
        override val rawValue: String = "model"
    }

    data class Custom(
        override val rawValue: String,
    ) : SessionConfigCategory
}

sealed interface SessionConfigOrigin {
    data object NativeConfigOption : SessionConfigOrigin

    data object LegacyMode : SessionConfigOrigin

    data object LegacyModel : SessionConfigOrigin
}

sealed interface SessionConfigValue {
    data class StringValue(
        val value: String,
    ) : SessionConfigValue

    data class BoolValue(
        val value: Boolean,
    ) : SessionConfigValue

    data class UnknownValue(
        val debugValue: String? = null,
    ) : SessionConfigValue
}

data class SessionConfigChoice(
    val id: String,
    val label: String,
    val value: String,
    val description: String? = null,
)

data class SessionConfigChoiceGroup(
    val id: String,
    val label: String? = null,
    val choices: List<SessionConfigChoice>,
)

sealed interface SessionConfigOption {
    val id: String
    val name: String
    val description: String?
    val category: SessionConfigCategory?
    val origin: SessionConfigOrigin

    data class Select(
        override val id: String,
        override val name: String,
        override val description: String? = null,
        override val category: SessionConfigCategory? = null,
        override val origin: SessionConfigOrigin = SessionConfigOrigin.NativeConfigOption,
        val currentValue: String? = null,
        val choices: List<SessionConfigChoice> = emptyList(),
        val groups: List<SessionConfigChoiceGroup> = emptyList(),
    ) : SessionConfigOption

    data class BooleanOption(
        override val id: String,
        override val name: String,
        override val description: String? = null,
        override val category: SessionConfigCategory? = null,
        override val origin: SessionConfigOrigin = SessionConfigOrigin.NativeConfigOption,
        val currentValue: Boolean,
    ) : SessionConfigOption

    data class Unknown(
        override val id: String,
        override val name: String,
        override val description: String? = null,
        override val category: SessionConfigCategory? = null,
        override val origin: SessionConfigOrigin = SessionConfigOrigin.NativeConfigOption,
        val kind: String? = null,
        val currentValue: SessionConfigValue? = null,
    ) : SessionConfigOption
}

fun SessionConfigOption.Select.allChoices(): List<SessionConfigChoice> =
    if (groups.isEmpty()) {
        choices
    } else {
        groups.flatMap {
            it.choices
        }
    }

fun SessionConfigOption.Select.selectedChoice(): SessionConfigChoice? {
    val value = currentValue ?: return null
    return allChoices().firstOrNull { it.value == value }
}

fun SessionConfigOption.displayValueLabel(): String? =
    when (this) {
        is SessionConfigOption.Select -> selectedChoice()?.label ?: currentValue
        is SessionConfigOption.BooleanOption -> currentValue.toString()
        is SessionConfigOption.Unknown ->
            when (val value = currentValue) {
                is SessionConfigValue.StringValue -> value.value
                is SessionConfigValue.BoolValue -> value.value.toString()
                is SessionConfigValue.UnknownValue -> value.debugValue
                null -> null
            }
    }

internal data class LegacyModeState(
    val modes: List<SessionMode> = emptyList(),
    val currentModeId: String? = null,
)

internal data class LegacyModelState(
    val choices: List<SessionConfigChoice> = emptyList(),
    val currentModelId: String? = null,
)