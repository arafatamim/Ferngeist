package com.tamimarafat.ferngeist.acp.bridge.session

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

@Serializable
data class CommandInfo(
    val name: String,
    val description: String? = null,
    /**
     * The agent's placeholder for this command's argument. Older saved states predate the
     * field, so a missing value decodes as null; null is also omitted when saving so the
     * existing state format does not need a version bump for an additive field.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val inputHint: String? = null,
)
