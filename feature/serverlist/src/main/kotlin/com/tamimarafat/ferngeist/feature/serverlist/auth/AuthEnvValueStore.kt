package com.tamimarafat.ferngeist.feature.serverlist.auth

interface AuthEnvValueStore {
    suspend fun getValues(serverId: String): Map<String, String>

    suspend fun deleteValues(serverId: String)

    suspend fun updateValues(
        serverId: String,
        envVarNames: Set<String>,
        envValues: Map<String, String>,
    )
}
