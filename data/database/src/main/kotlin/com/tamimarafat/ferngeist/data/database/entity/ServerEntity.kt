package com.tamimarafat.ferngeist.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sourceKind: String,
    val scheme: String,
    val host: String,
    val token: String,
    val workingDirectory: String,
    val preferredAuthMethodId: String?,
    val helperCredential: String,
    val helperCredentialExpiresAt: Long?,
    val helperRemoteMode: String?,
    val helperSourceId: String?,
    val selectedAgentId: String?,
    val selectedAgentName: String?,
)
