package com.tamimarafat.ferngeist.core.model.store

/**
 * Thrown by [AuthEnvValueStore.updateValues] when the stored values for a server
 * decrypt but cannot be parsed.
 *
 * The read path degrades to an empty map, because a dialog can only render blanks, but
 * an update must never use that empty map as its base: the read-modify-write would then
 * silently replace values it never managed to read. Callers surface this to the user and
 * leave the stored copy alone.
 */
class AuthEnvValuesUnreadableException(
    val serverId: String,
) : IllegalStateException(
        "Saved environment values for this server could not be read on this device. " +
            "Re-enter them to replace the stored copy.",
    )

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

    /**
     * Merges [envValues] for [envVarNames] into the stored values.
     *
     * @throws AuthEnvValuesUnreadableException when the stored values cannot be parsed,
     *   so the caller can tell the user instead of overwriting them.
     */
    suspend fun updateValues(
        serverId: String,
        envVarNames: Set<String>,
        envValues: Map<String, String>,
    )
}
