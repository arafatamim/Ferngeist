package com.tamimarafat.ferngeist.data.database.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "turn_timings",
    primaryKeys = ["serverId", "sessionId", "turnIndex"],
    indices = [Index(value = ["finishedAt"])],
)
data class TurnTimingEntity(
    val serverId: String,
    val sessionId: String,
    val turnIndex: Int,
    val promptHash: Int,
    val startedAt: Long,
    val finishedAt: Long,
)
