package com.tamimarafat.ferngeist.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageAttachmentHelperTest {
    // -- encodeImageBytes edge cases --

    @Test
    fun `returns null when bytes are not a valid image`() {
        val result = ImageAttachmentHelper.encodeImageBytes(ByteArray(4) { 0x00 }, "image/jpeg")
        assertNull(result)
    }

    @Test
    fun `returns null for empty byte array`() {
        val result = ImageAttachmentHelper.encodeImageBytes(ByteArray(0), "image/jpeg")
        assertNull(result)
    }

    // -- computeSampleSize --

    @Test
    fun `computeSampleSize returns 1 when image is within max dimension`() {
        val sampleSize = ImageAttachmentHelper.computeSampleSize(100, 100, 1024)
        assertEquals(1, sampleSize)
    }

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
    fun `computeSampleSize returns 1 for zero dimension`() {
        val sampleSize = ImageAttachmentHelper.computeSampleSize(0, 0, 1024)
        assertEquals(1, sampleSize)
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
    fun `MAX_IMAGES is a positive number`() {
        assert(ImageAttachmentHelper.MAX_IMAGES > 0)
    }

    @Test
    fun `MAX_IMAGE_DIMENSION is a reasonable size`() {
        assert(ImageAttachmentHelper.MAX_IMAGE_DIMENSION > 0)
        assert(ImageAttachmentHelper.MAX_IMAGE_DIMENSION <= 4096)
    }
}
