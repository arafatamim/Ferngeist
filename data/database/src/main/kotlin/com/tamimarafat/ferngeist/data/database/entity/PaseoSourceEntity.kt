package com.tamimarafat.ferngeist.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "paseo_sources")
data class PaseoSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mode: String,
    val scheme: String,
    val host: String,
    val password: String,
    val serverId: String,
    val daemonPublicKeyB64: String,
    val relayEndpoint: String,
    val relayUseTls: Boolean,
)
