package com.tamimarafat.ferngeist.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gateway_sources")
data class GatewaySourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val scheme: String,
    val host: String,
    val gatewayCredential: String,
    val gatewayCredentialExpiresAt: Long?,
    val gatewayRemoteMode: String?,
)
