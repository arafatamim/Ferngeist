package com.tamimarafat.ferngeist.data.database.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CredentialEncryptor(
    context: Context,
) {
    class CredentialPersistenceException(
        message: String,
    ) : RuntimeException(message)

    class CredentialUnavailableException(
        message: String,
        val key: String,
    ) : RuntimeException(message)

    private val prefs: SharedPreferences by lazy {
        val masterKey =
            MasterKey
                .Builder(context)
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

    fun encrypt(
        plaintext: String,
        key: String,
    ): String {
        val success = prefs.edit().putString(key, plaintext).commit()
        if (!success) {
            throw CredentialPersistenceException("Failed to persist encrypted credential for key: $key")
        }
        return ENCRYPTED_PREFIX + key
    }

    fun decrypt(
        ciphertext: String,
        key: String,
    ): String {
        if (!ciphertext.startsWith(ENCRYPTED_PREFIX)) return ciphertext
        val value =
            prefs.getString(key, null)
                ?: throw CredentialUnavailableException(
                    message =
                        "Encrypted credential backing entry not found for key: $key. " +
                            "The credential may have been invalidated or the app data may be corrupted.",
                    key = key,
                )
        return value
    }

    fun delete(key: String) {
        val success = prefs.edit().remove(key).commit()
        if (!success) {
            throw CredentialPersistenceException("Failed to delete encrypted credential for key: $key")
        }
    }

    companion object {
        const val ENCRYPTED_PREFIX = "_enc_:"
        private const val FILE_NAME = "encrypted_credentials"

        fun serverTokenKey(serverId: String) = "server_token:$serverId"

        fun gatewayCredentialKey(gatewayId: String) = "gateway_credential:$gatewayId"
    }
}
