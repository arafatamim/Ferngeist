package com.tamimarafat.ferngeist.core.model.blob

/** Stores agent reply bytes outside the transcript; a null key means the blob is unavailable. */
interface EmbeddedBlobStore {
    fun put(base64: String): String?

    fun clean()
}
