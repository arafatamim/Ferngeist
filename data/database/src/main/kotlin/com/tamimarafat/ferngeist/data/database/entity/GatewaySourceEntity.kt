package com.tamimarafat.ferngeist.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "gateway_sources",
    indices = [Index(value = ["gatewayId"], unique = true)],
)
data class GatewaySourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val scheme: String,
    val host: String,
    val gatewayCredential: String,
    val gatewayCredentialExpiresAt: Long?,
    val gatewayRemoteMode: String?,
    val gatewayId: String? = null,
)
