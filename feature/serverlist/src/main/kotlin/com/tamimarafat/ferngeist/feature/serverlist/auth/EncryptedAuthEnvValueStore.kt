package com.tamimarafat.ferngeist.feature.serverlist.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private const val FILE_NAME = "auth_env_values"

private val Context.encryptedDataStore by preferencesDataStore(name = FILE_NAME)

@Singleton
class EncryptedAuthEnvValueStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val json: Json,
    ) : AuthEnvValueStore {
        private val cipherProvider = AndroidKeystoreCipherProvider()

        override suspend fun getValues(serverId: String): Map<String, String> =
            withContext(Dispatchers.IO) {
                val preferencesKey = stringPreferencesKey(storageKey(serverId))
                val encrypted = context.encryptedDataStore.data.first()[preferencesKey] ?: return@withContext emptyMap()
                decodeValues(cipherProvider.decrypt(encrypted))
            }

        override suspend fun deleteValues(serverId: String): Unit =
            withContext(Dispatchers.IO) {
                context.encryptedDataStore.edit { prefs ->
                    prefs.remove(stringPreferencesKey(storageKey(serverId)))
                }
            }

        override suspend fun updateValues(
            serverId: String,
            envVarNames: Set<String>,
            envValues: Map<String, String>,
        ): Unit =
            withContext(Dispatchers.IO) {
                val storeKey = storageKey(serverId)
                val preferencesKey = stringPreferencesKey(storeKey)
                val existingEncrypted = context.encryptedDataStore.data.first()[preferencesKey]
                val existing =
                    if (existingEncrypted != null) {
                        decodeValues(cipherProvider.decrypt(existingEncrypted))
                    } else {
                        emptyMap()
                    }
                val mergedValues =
                    existing.toMutableMap().apply {
                        envVarNames.forEach(::remove)
                        envValues.forEach { (name, value) ->
                            val sanitized = value.trim()
                            if (sanitized.isNotEmpty()) {
                                put(name, sanitized)
                            }
                        }
                    }

                context.encryptedDataStore.edit { prefs ->
                    if (mergedValues.isEmpty()) {
                        prefs.remove(preferencesKey)
                    } else {
                        val serialized = json.encodeToString(valueSerializer, mergedValues)
                        prefs[preferencesKey] = cipherProvider.encrypt(serialized)
                    }
                }
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
            private val valueSerializer = MapSerializer(String.serializer(), String.serializer())
        }
    }

private class AndroidKeystoreCipherProvider {
    private val keystoreAlias = "auth_env_values_key"
    private val transform = "AES/GCM/NoPadding"
    private val gcmIvLength = 12
    private val gcmTagLength = 128

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getEntry(keystoreAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore",
            )
        keyGenerator.init(
            KeyGenParameterSpec
                .Builder(
                    keystoreAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    fun encrypt(plaintext: String): String {
        val secretKey = getOrCreateKey()
        val cipher = Cipher.getInstance(transform)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = iv + ciphertext
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val secretKey = getOrCreateKey()
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, gcmIvLength)
        val ciphertext = combined.copyOfRange(gcmIvLength, combined.size)
        val cipher = Cipher.getInstance(transform)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(gcmTagLength, iv))
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }
}
