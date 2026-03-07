package com.tamimarafat.ferngeist.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val scheme: String,
    val host: String,
    val token: String,
    val workingDirectory: String,
    val preferredAuthMethodId: String?,
)
