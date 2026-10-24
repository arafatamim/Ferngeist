package com.tamimarafat.ferngeist.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageAttachmentHelperTest {
    // -- encodeImageBytes edge cases --

    @Test
    fun `returns null when the decoder yields no bounds`() {
        // Real-image decode is untested here: this module has no Robolectric. What this guards is
        // the no-bounds path, which both inputs hit.
        assertNull(ImageAttachmentHelper.encodeImageBytes(ByteArray(4) { 0x00 }, "image/jpeg"))
        assertNull(ImageAttachmentHelper.encodeImageBytes(ByteArray(0), "image/jpeg"))
    }

    // -- computeSampleSize --

    @Test
    fun `computeSampleSize returns power-of-two when image exceeds max dimension`() {
        val sampleSize = ImageAttachmentHelper.computeSampleSize(2048, 1024, 1024)
        assertEquals(2, sampleSize)
    }

    @Test
    fun `computeSampleSize returns larger power-of-two for much larger images`() {
        val sampleSize = ImageAttachmentHelper.computeSampleSize(4096, 4096, 1024)
        assertEquals(4, sampleSize)
    }

    @Test
    fun `computeSampleSize returns 1 for dimension exactly at max`() {
        val sampleSize = ImageAttachmentHelper.computeSampleSize(1024, 1024, 1024)
        assertEquals(1, sampleSize)
    }

    @Test
    fun `computeSampleSize returns 2 for dimension one pixel over max`() {
        val sampleSize = ImageAttachmentHelper.computeSampleSize(1025, 1024, 1024)
        assertEquals(2, sampleSize)
    }

    // -- constants --

    @Test
    fun `MAX_IMAGES is the user-visible five-image cap`() {
        assertEquals(5, ImageAttachmentHelper.MAX_IMAGES)
    }

    @Test
    fun `MAX_IMAGE_DIMENSION is the 1024px downscale target`() {
        assertEquals(1024, ImageAttachmentHelper.MAX_IMAGE_DIMENSION)
    }
}
