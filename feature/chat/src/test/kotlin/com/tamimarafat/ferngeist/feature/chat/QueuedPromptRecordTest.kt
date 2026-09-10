package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.QueuedPromptRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Codec-level coverage for the persistence format [DataStorePendingPromptStore] writes: the
 * stored value is a JSON array of [QueuedPromptRecord] produced and read with the same [Json]
 * configuration the Hilt graph provides (`Json { ignoreUnknownKeys = true }`).
 */
class QueuedPromptRecordTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun roundTrip(records: List<QueuedPromptRecord>): List<QueuedPromptRecord> =
        json.decodeFromString(json.encodeToString(records))

    @Test
    fun `a single record round trips unchanged`() {
        val record = QueuedPromptRecord(clientId = "client-1", text = "hello agent")

        assertEquals(listOf(record), roundTrip(listOf(record)))
    }

    @Test
    fun `multiple records keep their queue order`() {
        val records =
            listOf(
                QueuedPromptRecord(clientId = "client-1", text = "first"),
                QueuedPromptRecord(clientId = "client-2", text = "second"),
                QueuedPromptRecord(clientId = "client-3", text = "third"),
            )

        assertEquals(records, roundTrip(records))
    }

    @Test
    fun `image and file payloads round trip`() {
        val record =
            QueuedPromptRecord(
                clientId = "client-attachments",
                text = "see attached",
                images =
                    listOf(
                        ChatImageData(base64 = "aW1n", mimeType = "image/png"),
                        ChatImageData(base64 = "aW1nMg"),
                    ),
                files =
                    listOf(
                        ChatFileData(
                            name = "notes.txt",
                            base64 = "ZmlsZQ==",
                            mimeType = "text/plain",
                            sizeBytes = 4L,
                        ),
                    ),
            )

        assertEquals(listOf(record), roundTrip(listOf(record)))
    }

    @Test
    fun `an empty list round trips as empty`() {
        assertEquals(emptyList<QueuedPromptRecord>(), roundTrip(emptyList()))
    }
}
