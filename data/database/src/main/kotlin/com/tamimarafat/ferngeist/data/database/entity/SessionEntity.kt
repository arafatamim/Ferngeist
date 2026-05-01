package com.tamimarafat.ferngeist.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sessions",
    indices = [Index("serverId")],
)
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val serverId: String,
    val title: String?,
    val cwd: String?,
    val updatedAt: Long?,
)
