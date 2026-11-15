package com.tamimarafat.ferngeist.acp.bridge.session.blob

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.core.model.CachedBlob
import com.tamimarafat.ferngeist.core.model.blob.EmbeddedBlobStore
import com.tamimarafat.ferngeist.core.model.content.chatFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException

/** Store reply bytes before they reach the transcript or its saved state. */
internal suspend fun AppSessionEvent.cacheReplyBlob(
    cache: EmbeddedBlobStore?,
    io: CoroutineDispatcher,
): AppSessionEvent {
    if (cache == null) return this
    val message = this as? AppSessionEvent.AgentMessage ?: return this
    val block = message.contentBlock as? ContentBlock.Resource
    val resource = block?.resource as? EmbeddedResourceResource.BlobResourceContents ?: return this
    val file = checkNotNull(message.contentBlock.chatFile())
    val key =
        withContext(io) {
            try {
                cache.put(resource.blob)
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    return message.copy(
        contentBlock = ContentBlock.Resource(resource.copy(blob = "")),
        cachedBlob = CachedBlob(key, file.name, file.mimeType, file.sizeBytes),
    )
}
