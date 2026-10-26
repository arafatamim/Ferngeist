package com.tamimarafat.ferngeist.acp.bridge.catalog.project

import com.tamimarafat.ferngeist.acp.bridge.connection.AcpSessionUpdateMapper
import com.tamimarafat.ferngeist.core.model.LaunchableTarget

internal fun activityTimestamps(
    snapshots: Map<String, ProjectSnapshot>,
    targets: List<LaunchableTarget>,
): Map<String, Long> =
    targets
        .filterIsInstance<LaunchableTarget.GatewayAgent>()
        .flatMap { target ->
            snapshots[target.gatewaySource.id]?.agents?.get(target.binding.agentId).orEmpty().mapNotNull { chat ->
                AcpSessionUpdateMapper.parseIsoOrMillis(chat.updatedAt)?.let { "${target.id}/${chat.sessionId}" to it }
            }
        }.toMap()
