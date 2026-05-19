package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigCategory.Mode
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigCategory.Model

/**
 * Centralized configuration policy for handling legacy mode/model options alongside native config options.
 *
 * This object encapsulates all logic for:
 * - Resolving the effective list of config options presented to the UI (merging native options with
 *   synthetic legacy options when the SDK doesn't expose them as config options).
 * - Applying value changes to config options in a type-safe way.
 * - Mapping a user's config selection to the appropriate RPC dispatch (legacy mode, legacy model,
 *   or native config option).
 *
 * The ACP Kotlin SDK currently exposes mode/model via legacy RPCs (`session/set_mode`,
 * `session/set_model`) while newer agents may expose them as native config options. This policy
 * ensures both sources are unified and consistently presented to the UI, preventing duplicate
 * or missing options.
 *
 * Design decision: All configuration routing and compatibility logic lives here instead of being
 * scattered across SessionBridge, SessionRuntime, and SessionConfigModels. This reduces branching
 * complexity and provides a single test surface for the compatibility matrix.
 */
internal object SessionConfigPolicy {
    /**
     * Resolves the effective list of config options to expose to the UI.
     *
     * The SDK may provide native config options, but legacy mode/model options may need to be
     * synthesized from separate state fields. This function:
     *
     * 1. Annotates each native option with its category (Mode, Model, or Custom) if not already set.
     * 2. If there are no native options at all, returns only the synthetic legacy options.
     * 3. If native options exist, adds synthetic legacy options only if the category is missing
     *    from the native set (so we don't show duplicates).
     *
     * @param nativeOptions The list of config options received from the SDK's config_option_update.
     * @param legacyModes The current mode state (from ModesUpdated events), may be null.
     * @param legacyModel The current model state (from LegacyModelOptionsUpdated events), may be null.
     * @return A unified list of options ready for UI display, with categories inferred where needed.
     */
    fun resolveEffectiveOptions(
        nativeOptions: List<SessionConfigOption>,
        legacyModes: LegacyModeState?,
        legacyModel: LegacyModelState?,
    ): List<SessionConfigOption> {
        // Annotate each native option with a category if it doesn't already have one.
        val annotatedNative = nativeOptions.map { annotateCategory(it, legacyModes, legacyModel) }

        // If the agent doesn't support native config options at all, we still need to show
        // legacy mode/model as synthetic options so the user can change them.
        if (annotatedNative.isEmpty()) {
            return buildList {
                legacyModes?.toSyntheticOption()?.let(::add)
                legacyModel?.toSyntheticOption()?.let(::add)
            }
        }

        // Determine whether the native set already includes mode and/or model options.
        val hasModeOption = annotatedNative.any { it.category is Mode }
        val hasModelOption = annotatedNative.any { it.category is Model }

        // Combine native options with any missing synthetic legacy options.
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

    /**
     * Applies a new value to a config option in a type-safe manner.
     *
     * This is called from SessionRuntime when handling [AppSessionEvent.ConfigOptionValueChanged].
     * It updates the currentValue of the matching option while preserving other options unchanged.
     *
     * @param option The option to update.
     * @param optionId The ID of the option being changed.
     * @param value The new value (StringValue or BoolValue).
     * @return A new [SessionConfigOption] with updated currentValue, or the original if IDs don't match.
     */
    fun applyValueChange(
        option: SessionConfigOption,
        optionId: String,
        value: SessionConfigValue,
    ): SessionConfigOption {
        if (option.id != optionId) return option
        return when (option) {
            is SessionConfigOption.Select -> {
                // Select options only accept string values; if the value isn't a string, keep current.
                val newValue = (value as? SessionConfigValue.StringValue)?.value
                option.copy(currentValue = newValue ?: option.currentValue)
            }
            is SessionConfigOption.BooleanOption -> {
                // Boolean options only accept boolean values.
                val newValue = (value as? SessionConfigValue.BoolValue)?.value
                option.copy(currentValue = newValue ?: option.currentValue)
            }
            is SessionConfigOption.Unknown -> option.copy(currentValue = value)
        }
    }

    /**
     * Maps a config option selection to the appropriate dispatch action.
     *
     * This is the single point that decides whether a user's config change should:
     * - Call `session/set_mode` (legacy) + emit ModeChanged event
     * - Call `session/set_model` (legacy) + emit ModelSelectionConfirmed event
     * - Call `session/set_config_option` (native) + emit ConfigOptionValueChanged event
     *
     * The decision is based on the option's [SessionConfigOption.origin], which is either
     * [SessionConfigOrigin.LegacyMode], [SessionConfigOrigin.LegacyModel], or
     * [SessionConfigOrigin.NativeConfigOption].
     *
     * @param option The selected config option (may be null if not found).
     * @param value The user-selected value.
     * @return A [DispatchAction] describing the RPC to call and the optimistic event to emit,
     *         or null if the option is invalid (e.g., missing modeId for a legacy mode option).
     */
    sealed interface DispatchAction {
        data class SetLegacyMode(val modeId: String, val event: AppSessionEvent.ModeChanged) : DispatchAction
        data class SetLegacyModel(val modelId: String, val event: AppSessionEvent.ModelSelectionConfirmed) : DispatchAction
        data class SetNativeConfig(
            val optionId: String,
            val value: SessionConfigValue,
            val event: AppSessionEvent.ConfigOptionValueChanged,
        ) : DispatchAction
    }

    fun mapToDispatchAction(
        option: SessionConfigOption?,
        value: SessionConfigValue,
    ): DispatchAction? {
        val resolvedOption = option ?: return null
        return when (resolvedOption.origin) {
            SessionConfigOrigin.LegacyMode -> {
                // Legacy mode options must have a string value; if not, we can't dispatch.
                val modeId = (value as? SessionConfigValue.StringValue)?.value ?: return null
                DispatchAction.SetLegacyMode(modeId, AppSessionEvent.ModeChanged(modeId))
            }
            SessionConfigOrigin.LegacyModel -> {
                val modelId = (value as? SessionConfigValue.StringValue)?.value ?: return null
                DispatchAction.SetLegacyModel(modelId, AppSessionEvent.ModelSelectionConfirmed(modelId))
            }
            else -> DispatchAction.SetNativeConfig(resolvedOption.id, value, AppSessionEvent.ConfigOptionValueChanged(resolvedOption.id, value))
        }
    }

    /**
     * Annotates an option with a category (Mode/Model) if it doesn't already have one.
     *
     * This is the first step in category inference. If the option's category is null, we attempt
     * to infer it by comparing the option's choices against legacy mode/model lists or by scanning
     * the option's ID/name for heuristic keywords.
     *
     * @param option The option to annotate.
     * @param legacyModes The legacy mode state (for exact-match inference).
     * @param legacyModel The legacy model state (for exact-match inference).
     * @return A new option with category set if inference succeeded, or the original option if not.
     */
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

    /**
     * Infers the category of a config option by examining its choices or name.
     *
     * Inference strategy:
     *
     * 1. For [SessionConfigOption.Select] options: if all choices exactly match the set of
     *    legacy mode IDs or model values, we infer the corresponding category. This is a strong
     *    signal because mode and model choices typically have distinctive sets.
     *
     * 2. If exact match fails, fall back to keyword heuristics in the option's ID or name:
     *    - Mode hints: "mode", "modes", "sessionmode", "approvalpreset", or if "approval" appears anywhere.
     *    - Model hints: "model", "models", "sessionmodel".
     *
     * 3. Normalize by stripping non-alphanumeric characters and lowercasing to make matching
     *    robust against spacing/casing variations.
     *
     * @param option The option to infer category for.
     * @param legacyModes The legacy mode state for exact-match comparison.
     * @param legacyModel The legacy model state for exact-match comparison.
     * @return The inferred [SessionConfigCategory] (Mode or Model), or null if no inference possible.
     */
    private fun inferCategory(
        option: SessionConfigOption,
        legacyModes: LegacyModeState?,
        legacyModel: LegacyModelState?,
    ): SessionConfigCategory? {
        if (option is SessionConfigOption.Select) {
            // Exact match is a strong signal: if the set of choice values equals the set of
            // legacy mode IDs or model values, this option is almost certainly a mode or model.
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
                return Mode
            }
            if (modelValues.isNotEmpty() && optionValues == modelValues) {
                return Model
            }
        }

        // Fallback to keyword heuristics in the ID/name.
        val normalizedId = normalize(option.id)
        val normalizedName = normalize(option.name)
        return when {
            normalizedId in MODE_HINTS || normalizedName in MODE_HINTS || "approval" in normalizedName ->
                Mode
            normalizedId in MODEL_HINTS || normalizedName in MODEL_HINTS ->
                Model
            else -> null
        }
    }

    /** Lowercases and removes all non-alphanumeric characters from a string. */
    private fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

    private val MODE_HINTS = setOf("mode", "modes", "sessionmode", "approvalpreset")
    private val MODEL_HINTS = setOf("model", "models", "sessionmodel")
}

/**
 * Converts a legacy mode state into a synthetic [SessionConfigOption.Select] representing
 * the available modes. This synthetic option uses the [SessionConfigOrigin.LegacyMode] origin
 * so that UI selection dispatches via `session/set_mode` instead of the native config RPC.
 *
 * The synthetic option is only created if the state contains at least one mode.
 */
internal fun LegacyModeState.toSyntheticOption(): SessionConfigOption.Select? {
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

/**
 * Converts a legacy model state into a synthetic [SessionConfigOption.Select] representing
 * the available models. This synthetic option uses the [SessionConfigOrigin.LegacyModel] origin
 * so that UI selection dispatches via `session/set_model` instead of the native config RPC.
 */
internal fun LegacyModelState.toSyntheticOption(): SessionConfigOption.Select? {
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