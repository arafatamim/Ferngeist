package com.tamimarafat.ferngeist.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey


@Entity(
    tableName = "gateway_agent_bindings",
    foreignKeys = [
        ForeignKey(
            entity = GatewaySourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["gatewaySourceId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("gatewaySourceId")],
)
data class GatewayAgentBindingEntity(
    @PrimaryKey val id: String,
    val name: String,
    val gatewaySourceId: String,
    val agentId: String,
    val preferredAuthMethodId: String?,
)
