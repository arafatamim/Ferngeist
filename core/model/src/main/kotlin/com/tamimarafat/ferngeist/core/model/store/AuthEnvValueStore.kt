package com.tamimarafat.ferngeist.core.model.store

/**
 * Persists per-server auth env-var values (declared by ACP auth methods) so
 * the auth dialog can be prefilled on the next challenge.
 *
 * Implementations must encrypt values at rest. Values are namespaced per
 * [serverId] and only names declared by the challenged auth methods are
 * readable/writable through [getValues]/[updateValues].
 */
interface AuthEnvValueStore {
    suspend fun getValues(serverId: String): Map<String, String>

    suspend fun deleteValues(serverId: String)

    suspend fun updateValues(
        serverId: String,
        envVarNames: Set<String>,
        envValues: Map<String, String>,
    )
}
