package com.tamimarafat.ferngeist.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "paseo_agent_bindings",
    foreignKeys = [
        ForeignKey(
            entity = PaseoSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["paseoSourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("paseoSourceId")],
)
data class PaseoAgentBindingEntity(
    @PrimaryKey val id: String,
    val name: String,
    val paseoSourceId: String,
    val provider: String,
    val cwd: String,
    val preferredModelId: String?,
    val preferredAuthMethodId: String?,
)
