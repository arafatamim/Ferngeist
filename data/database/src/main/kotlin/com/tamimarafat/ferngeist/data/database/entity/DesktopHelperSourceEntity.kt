package com.tamimarafat.ferngeist.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "desktop_helper_sources")
data class DesktopHelperSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val scheme: String,
    val host: String,
    val helperCredential: String,
    val helperCredentialExpiresAt: Long?,
    val helperRemoteMode: String?,
)
