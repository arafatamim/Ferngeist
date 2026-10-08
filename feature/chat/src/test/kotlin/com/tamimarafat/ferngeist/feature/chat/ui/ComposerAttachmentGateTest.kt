package com.tamimarafat.ferngeist.feature.chat.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides whether pasted or dropped content is consumed as an attachment. It mirrors
 * the branch `processPickedUris` takes, so the same content cannot be accepted by the document
 * picker and rejected by paste (or the other way round).
 */
class ComposerAttachmentGateTest {
    @Test
    fun `images need image support or a file fallback`() {
        assertTrue(acceptsAttachmentMimeType("image/png", canSendImages = true, canSendFiles = false))
        // A session that takes files still receives the image, as a file — the picker's own
        // degradation for an embedded-context agent.
        assertTrue(acceptsAttachmentMimeType("image/png", canSendImages = false, canSendFiles = true))
        assertFalse(acceptsAttachmentMimeType("image/png", canSendImages = false, canSendFiles = false))
    }

    @Test
    fun `non-images ride the embedded-context capability`() {
        assertTrue(acceptsAttachmentMimeType("application/pdf", canSendImages = false, canSendFiles = true))
        assertTrue(acceptsAttachmentMimeType("text/plain", canSendImages = true, canSendFiles = true))
        assertFalse(acceptsAttachmentMimeType("application/pdf", canSendImages = true, canSendFiles = false))
    }

    @Test
    fun `unknown type is treated as a file, not as an image`() {
        // Providers may return no type at all; that must land on the file branch, not be dropped.
        assertTrue(acceptsAttachmentMimeType(null, canSendImages = true, canSendFiles = true))
        assertFalse(acceptsAttachmentMimeType(null, canSendImages = true, canSendFiles = false))
    }
}
