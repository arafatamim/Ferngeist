package com.tamimarafat.ferngeist.feature.serverlist.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptedAuthEnvValueStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) : AuthEnvValueStore {

    private val preferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun getValues(serverId: String): Map<String, String> = withContext(Dispatchers.IO) {
        decodeValues(preferences.getString(storageKey(serverId), null))
    }

    override suspend fun deleteValues(serverId: String) = withContext(Dispatchers.IO) {
        preferences.edit().remove(storageKey(serverId)).apply()
    }

    override suspend fun updateValues(
        serverId: String,
        envVarNames: Set<String>,
        envValues: Map<String, String>,
    ) = withContext(Dispatchers.IO) {
        val key = storageKey(serverId)
        val mergedValues = decodeValues(preferences.getString(key, null)).toMutableMap().apply {
            envVarNames.forEach(::remove)
            envValues.forEach { (name, value) ->
                val sanitizedValue = value.trim()
                if (sanitizedValue.isNotEmpty()) {
                    put(name, sanitizedValue)
                }
            }
        }

        preferences.edit().apply {
            if (mergedValues.isEmpty()) {
                remove(key)
            } else {
                putString(key, json.encodeToString(valueSerializer, mergedValues))
            }
        }.apply()
    }

    private fun decodeValues(encoded: String?): Map<String, String> {
        if (encoded.isNullOrBlank()) {
            return emptyMap()
        }
        return runCatching {
            json.decodeFromString(valueSerializer, encoded)
        }.getOrElse { error ->
            if (error is SerializationException) {
                emptyMap()
            } else {
                throw error
            }
        }
    }

    private fun storageKey(serverId: String): String = "server:$serverId"

    private companion object {
        private const val FILE_NAME = "auth_env_values"
        private val valueSerializer = MapSerializer(String.serializer(), String.serializer())
    }
}
