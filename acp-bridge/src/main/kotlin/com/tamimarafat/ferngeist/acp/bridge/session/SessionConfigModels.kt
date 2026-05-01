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

internal object SessionConfigCompatibility {
    // Stand-in for missing SDK API: the current ACP Kotlin SDK parses config-option
    // category on the wire but does not expose it on SessionConfigOption, so Ferngeist
    // has to infer mode/model semantics here until the SDK surfaces category directly.
    fun resolve(
        nativeOptions: List<SessionConfigOption>,
        legacyModes: LegacyModeState?,
        legacyModel: LegacyModelState?,
    ): List<SessionConfigOption> {
        val annotatedNative = nativeOptions.map { annotateCategory(it, legacyModes, legacyModel) }
        if (annotatedNative.isEmpty()) {
            return buildList {
                legacyModes?.toSyntheticOption()?.let(::add)
                legacyModel?.toSyntheticOption()?.let(::add)
            }
        }

        val hasModeOption = annotatedNative.any { it.category is SessionConfigCategory.Mode }
        val hasModelOption = annotatedNative.any { it.category is SessionConfigCategory.Model }

        return buildList {
            addAll(annotatedNative)
            if (!hasModeOption) {
                legacyModes?.toSyntheticOption()?.let(::add)
            }
            if (!hasModelOption) {
                legacyModel?.toSyntheticOption()?.let(::add)
            }
        }
    }

    private fun annotateCategory(
        option: SessionConfigOption,
        legacyModes: LegacyModeState?,
        legacyModel: LegacyModelState?,
    ): SessionConfigOption {
        if (option.category != null) return option

        val inferred = inferCategory(option, legacyModes, legacyModel) ?: return option
        return when (option) {
            is SessionConfigOption.Select -> option.copy(category = inferred)
            is SessionConfigOption.BooleanOption -> option.copy(category = inferred)
            is SessionConfigOption.Unknown -> option.copy(category = inferred)
        }
    }

    private fun inferCategory(
        option: SessionConfigOption,
        legacyModes: LegacyModeState?,
        legacyModel: LegacyModelState?,
    ): SessionConfigCategory? {
        if (option is SessionConfigOption.Select) {
            val optionValues = option.allChoices().map { it.value }.toSet()
            val modeValues =
                legacyModes
                    ?.modes
                    ?.map { it.id }
                    ?.toSet()
                    .orEmpty()
            val modelValues =
                legacyModel
                    ?.choices
                    ?.map { it.value }
                    ?.toSet()
                    .orEmpty()

            if (modeValues.isNotEmpty() && optionValues == modeValues) {
                return SessionConfigCategory.Mode
            }
            if (modelValues.isNotEmpty() && optionValues == modelValues) {
                return SessionConfigCategory.Model
            }
        }

        val normalizedId = normalize(option.id)
        val normalizedName = normalize(option.name)
        return when {
            normalizedId in MODE_HINTS || normalizedName in MODE_HINTS || "approval" in normalizedName ->
                SessionConfigCategory.Mode
            normalizedId in MODEL_HINTS || normalizedName in MODEL_HINTS ->
                SessionConfigCategory.Model
            else -> null
        }
    }

    private fun LegacyModeState.toSyntheticOption(): SessionConfigOption.Select? {
        if (modes.isEmpty()) return null
        return SessionConfigOption.Select(
            id = "legacy_mode",
            name = "Mode",
            description = "Session mode",
            category = SessionConfigCategory.Mode,
            origin = SessionConfigOrigin.LegacyMode,
            currentValue = currentModeId,
            choices =
                modes.map { mode ->
                    SessionConfigChoice(
                        id = mode.id,
                        label = mode.name,
                        value = mode.id,
                        description = mode.description,
                    )
                },
        )
    }

    private fun LegacyModelState.toSyntheticOption(): SessionConfigOption.Select? {
        if (choices.isEmpty()) return null
        return SessionConfigOption.Select(
            id = "legacy_model",
            name = "Model",
            description = "Session model",
            category = SessionConfigCategory.Model,
            origin = SessionConfigOrigin.LegacyModel,
            currentValue = currentModelId,
            choices = choices,
        )
    }

    private fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

    private val MODE_HINTS = setOf("mode", "modes", "sessionmode", "approvalpreset")
    private val MODEL_HINTS = setOf("model", "models", "sessionmodel")
}
