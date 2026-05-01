package com.tamimarafat.ferngeist.gateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

private const val AUTH_BUNDLE_PREFIX = "ferngeist-gateway-auth-v1:"
private const val PROOF_DOMAIN = "FERNGEIST-HTTP-PROOF-V1"

data class GatewayAuthHeaders(
    val authorization: String,
    val proofTimestamp: String? = null,
    val proofNonce: String? = null,
    val proofSignature: String? = null,
)

data class GatewayGeneratedProofKey(
    val publicKey: String,
    val privateKey: String,
)

@Serializable
private data class GatewayStoredAuthBundle(
    val token: String,
    val proofPrivateKey: String? = null,
)

private data class GatewayAuthBundle(
    val token: String,
    val proofPrivateKey: String? = null,
)

object GatewayProofAuth {
    private val json = Json { ignoreUnknownKeys = true }
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun generateProofKey(): GatewayGeneratedProofKey {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = generator.generateKeyPair()
        return GatewayGeneratedProofKey(
            publicKey = encoder.encodeToString(keyPair.public.encoded),
            privateKey = encoder.encodeToString(keyPair.private.encoded),
        )
    }

    fun encodeStoredCredential(token: String, proofPrivateKey: String?): String {
        if (proofPrivateKey.isNullOrBlank()) return token
        val payload = json.encodeToString(
            GatewayStoredAuthBundle(
                token = token,
                proofPrivateKey = proofPrivateKey,
            ),
        )
        return AUTH_BUNDLE_PREFIX + encoder.encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    fun rotateStoredCredential(existingCredential: String, newToken: String): String {
        val existing = decodeStoredCredential(existingCredential)
        return encodeStoredCredential(newToken, existing.proofPrivateKey)
    }

    fun buildAuthHeaders(
        gatewayCredential: String,
        method: String,
        endpoint: String,
        body: String? = null,
        timestampSeconds: Long = System.currentTimeMillis() / 1000,
        nonce: String = java.util.UUID.randomUUID().toString(),
    ): GatewayAuthHeaders {
        val auth = decodeStoredCredential(gatewayCredential)
        if (auth.proofPrivateKey.isNullOrBlank()) {
            return GatewayAuthHeaders(authorization = "Bearer ${auth.token}")
        }
        val bodyHash = sha256Base64Url(body.orEmpty().toByteArray(Charsets.UTF_8))
        val pathWithQuery = URI(endpoint).run {
            buildString {
                append(rawPath ?: "/")
                rawQuery?.takeIf { it.isNotBlank() }?.let {
                    append('?')
                    append(it)
                }
            }
        }
        val timestamp = timestampSeconds.toString()
        val message = listOf(
            PROOF_DOMAIN,
            method.trim().uppercase(),
            pathWithQuery,
            auth.token,
            timestamp,
            nonce,
            bodyHash,
        ).joinToString("\n")
        val keyFactory = KeyFactory.getInstance("EC")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(decoder.decode(auth.proofPrivateKey)))
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(message.toByteArray(Charsets.UTF_8))
        return GatewayAuthHeaders(
            authorization = "Bearer ${auth.token}",
            proofTimestamp = timestamp,
            proofNonce = nonce,
            proofSignature = encoder.encodeToString(signature.sign()),
        )
    }

    private fun decodeStoredCredential(gatewayCredential: String): GatewayAuthBundle {
        if (!gatewayCredential.startsWith(AUTH_BUNDLE_PREFIX)) {
            return GatewayAuthBundle(token = gatewayCredential)
        }
        val encodedPayload = gatewayCredential.removePrefix(AUTH_BUNDLE_PREFIX)
        val payload = decoder.decode(encodedPayload).toString(Charsets.UTF_8)
        val parsed = json.decodeFromString<GatewayStoredAuthBundle>(payload)
        return GatewayAuthBundle(
            token = parsed.token,
            proofPrivateKey = parsed.proofPrivateKey,
        )
    }

    private fun sha256Base64Url(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return encoder.encodeToString(digest)
    }
}
