package com.tamimarafat.ferngeist.core.model.push

/**
 * Keys of the gateway's push payload: the JSON object a decrypted Web Push message
 * carries. These must match the gateway's push contract (docs/api.md, "Payload").
 */
object PushPayloadKeys {
    const val SERVER_ID = "serverId"
    const val SESSION_ID = "sessionId"
    const val CWD = "cwd"
    const val TITLE = "title"
    const val BODY = "body"
    const val CATEGORY = "category"
}
