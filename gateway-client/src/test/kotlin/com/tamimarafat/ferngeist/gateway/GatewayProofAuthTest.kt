package com.tamimarafat.ferngeist.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class GatewayProofAuthTest {
    @Test
    fun `generateProofKey returns keys that sign and verify`() {
        val key = GatewayProofAuth.generateProofKey()
        val decoder = Base64.getUrlDecoder()
        val keyFactory = KeyFactory.getInstance("EC")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(decoder.decode(key.privateKey)))
        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(decoder.decode(key.publicKey)))

        val payload = "ferngeist-proof-key-round-trip".toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(payload)
        val signature = signer.sign()

        signer.initVerify(publicKey)
        signer.update(payload)
        assertTrue(signer.verify(signature))
    }

    @Test
    fun `encodeStoredCredential without proof key returns raw token`() {
        assertEquals("my-token", GatewayProofAuth.encodeStoredCredential("my-token", null))
        assertEquals("my-token", GatewayProofAuth.encodeStoredCredential("my-token", ""))
    }

    @Test
    fun `encodeStoredCredential with proof key produces prefixed credential`() {
        val key = GatewayProofAuth.generateProofKey()
        val result = GatewayProofAuth.encodeStoredCredential("my-token", key.privateKey)
        assertTrue(result.startsWith("ferngeist-gateway-auth-v1:"))
    }

    @Test
    fun `buildAuthHeaders with bare token returns Bearer authorization only`() {
        val headers =
            GatewayProofAuth.buildAuthHeaders(
                gatewayCredential = "simple-token",
                request = GatewayProofRequest(method = "GET", endpoint = "/v1/agents"),
            )
        assertEquals("Bearer simple-token", headers.authorization)
        assertEquals(null, headers.proofTimestamp)
        assertEquals(null, headers.proofNonce)
        assertEquals(null, headers.proofSignature)
    }

    @Test
    fun `buildAuthHeaders with proof credential includes proof headers`() {
        val key = GatewayProofAuth.generateProofKey()
        val credential = GatewayProofAuth.encodeStoredCredential("my-token", key.privateKey)
        val headers =
            GatewayProofAuth.buildAuthHeaders(
                gatewayCredential = credential,
                request = GatewayProofRequest(method = "GET", endpoint = "/v1/agents"),
            )
        assertEquals("Bearer my-token", headers.authorization)
        assertNotNull(headers.proofTimestamp)
        assertNotNull(headers.proofNonce)
        assertNotNull(headers.proofSignature)
    }

    @Test
    fun `rotateStoredCredential preserves proof key and updates token`() {
        val key = GatewayProofAuth.generateProofKey()
        val credential = GatewayProofAuth.encodeStoredCredential("old-token", key.privateKey)
        val rotated = GatewayProofAuth.rotateStoredCredential(credential, "new-token")
        assertTrue(rotated.startsWith("ferngeist-gateway-auth-v1:"))

        val headers =
            GatewayProofAuth.buildAuthHeaders(
                gatewayCredential = rotated,
                request = GatewayProofRequest(method = "GET", endpoint = "/v1/agents"),
            )
        assertEquals("Bearer new-token", headers.authorization)
        assertNotNull(headers.proofSignature)
    }
}
