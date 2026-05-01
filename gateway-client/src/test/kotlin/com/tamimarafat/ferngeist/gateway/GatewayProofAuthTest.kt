package com.tamimarafat.ferngeist.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayProofAuthTest {
    @Test
    fun `generateProofKey returns key pair with non-empty public and private keys`() {
        val key = GatewayProofAuth.generateProofKey()
        assertTrue(key.publicKey.isNotEmpty())
        assertTrue(key.privateKey.isNotEmpty())
    }

    @Test
    fun `encodeStoredCredential without proof key returns raw token`() {
        val result = GatewayProofAuth.encodeStoredCredential("my-token", null)
        assertEquals("my-token", result)
    }

    @Test
    fun `encodeStoredCredential without proof key returns raw token even if blank`() {
        val result = GatewayProofAuth.encodeStoredCredential("my-token", "")
        assertEquals("my-token", result)
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
                method = "GET",
                endpoint = "/v1/agents",
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
                method = "GET",
                endpoint = "/v1/agents",
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
                method = "GET",
                endpoint = "/v1/agents",
            )
        assertEquals("Bearer new-token", headers.authorization)
        assertNotNull(headers.proofSignature)
    }
}
