package com.tamimarafat.ferngeist.acp.bridge.session

import kotlinx.serialization.Serializable

@Serializable
data class CommandInfo(
    val name: String,
    val description: String? = null,
)
