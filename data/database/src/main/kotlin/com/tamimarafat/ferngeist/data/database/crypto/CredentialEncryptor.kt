package com.tamimarafat.ferngeist.data.database.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// TODO: Remove SharedPreferencesMigration for "ferngeist_credentials" after
// all users have upgraded from pre-DataStore builds (legacy migration).
private val Context.credentialDataStore by preferencesDataStore(
    name = "ferngeist_credentials",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, "ferngeist_credentials"))
    },
)

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

    private val dataStore by lazy { context.credentialDataStore }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    suspend fun encrypt(
        plaintext: String,
        key: String,
    ): String {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = iv + ciphertext
        val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(key)] = encoded
        }
        return ENCRYPTED_PREFIX + key
    }

    @RequiresApi(Build.VERSION_CODES.M)
    suspend fun decrypt(
        ciphertext: String,
        key: String,
    ): String {
        if (!ciphertext.startsWith(ENCRYPTED_PREFIX)) return ciphertext
        val encoded =
            dataStore.data
                .map { prefs -> prefs[stringPreferencesKey(key)] }
                .first()
                ?: throw CredentialUnavailableException(
                    message =
                        "Encrypted credential backing entry not found for key: $key. " +
                            "The credential may have been invalidated or the app data may be corrupted.",
                    key = key,
                )
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        if (combined.size < GCM_IV_LENGTH_BYTES) {
            throw CredentialUnavailableException(
                message = "Stored credential for key $key is truncated.",
                key = key,
            )
        }
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertextBytes = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        val plaintext = cipher.doFinal(ciphertextBytes)
        return String(plaintext, Charsets.UTF_8)
    }

    suspend fun delete(key: String) {
        dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(key))
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(AES_KEY_SIZE_BITS)
                    .build(),
            )
        }.generateKey()
    }

    companion object {
        const val ENCRYPTED_PREFIX = "_enc_:"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "ferngeist_credential_key"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_SIZE_BITS = 256
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128

        fun serverTokenKey(serverId: String) = "server_token:$serverId"

        fun gatewayCredentialKey(gatewayId: String) = "gateway_credential:$gatewayId"
    }
}
