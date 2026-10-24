package com.tamimarafat.ferngeist.gateway.workspace

/** Optional selectors for a workspace diff, including a commit's rename source. */
data class GatewayDiffQuery(
    val path: String? = null,
    val acpSessionId: String? = null,
    val commit: String? = null,
    val oldPath: String? = null,
    val compare: String? = null,
)

/** A pinned page of history, optionally compared with another ref. */
data class GatewayLogQuery(
    val head: String?,
    val skip: Int,
    val limit: Int,
    val acpSessionId: String? = null,
    val compare: String? = null,
    val beyond: Int = 0,
)
