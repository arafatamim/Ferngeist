package com.tamimarafat.ferngeist.core.common.ui

data class ServerNameSharedBoundsKey(
    val serverId: String,
)

/** The agent card on the home screen, and the whole session list it opens into. */
data class ServerSharedBoundsKey(
    val serverId: String,
)

/** Repo names can repeat across paired gateways. */
data class ProjectTitleSharedBoundsKey(
    val gatewayId: String,
    val repo: String,
)

/** The project row the project screen grows out of. */
data class ProjectSharedBoundsKey(
    val gatewayId: String,
    val repo: String,
)
