package com.tamimarafat.ferngeist.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "helper_agent_bindings",
    foreignKeys = [
        ForeignKey(
            entity = DesktopHelperSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["helperSourceId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("helperSourceId")],
)
data class HelperAgentBindingEntity(
    @PrimaryKey val id: String,
    val name: String,
    val helperSourceId: String,
    val agentId: String,
    val workingDirectory: String,
    val preferredAuthMethodId: String?,
)
