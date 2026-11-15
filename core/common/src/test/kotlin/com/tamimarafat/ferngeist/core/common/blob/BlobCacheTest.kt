package com.tamimarafat.ferngeist.core.common.blob

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Base64
import java.util.concurrent.TimeUnit

class BlobCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `least recently used blobs are evicted when the budget is exceeded`() {
        val cache = BlobCache(temporary.root)
        val first = cache.put(encoded("abc"))!!
        val second = cache.put(encoded("def"))!!
        val firstFile = temporary.root.resolve(first)
        val secondFile = temporary.root.resolve(second)
        RandomAccessFile(firstFile, "rw").use { it.setLength(50L * 1024 * 1024) }
        RandomAccessFile(secondFile, "rw").use { it.setLength(50L * 1024 * 1024) }
        firstFile.setLastModified(System.currentTimeMillis() - 2000)
        secondFile.setLastModified(System.currentTimeMillis() - 1000)
        cache.use(first) { it.length() }
        cache.put(encoded("ghi"))
        assertNull(cache.use(second) { it.length() })
        assertNotNull(cache.use(first) { it.length() })
    }

    @Test
    fun `expired blobs are unavailable`() {
        val cache = BlobCache(temporary.root)
        val key = cache.put(encoded("abc"))!!
        temporary.root.resolve(key).setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(15))
        cache.clean()
        assertNull(cache.use(key) { it.readBytes() })
        assertFalse(temporary.root.listFiles()!!.any { it.isFile })
    }

    @Test
    fun `invalid base64 leaves no partial file`() {
        val cache = BlobCache(temporary.root)
        assertThrows(IOException::class.java) { cache.put("aGVsbG8!") }
        assertFalse(temporary.root.listFiles()!!.any { it.isFile })
    }

    private fun encoded(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())
}
