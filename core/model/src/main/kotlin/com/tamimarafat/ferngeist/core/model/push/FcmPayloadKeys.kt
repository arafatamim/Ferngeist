package com.tamimarafat.ferngeist.core.model.push

/**
 * Keys used in FCM data payloads and raw FCM intents.
 * These must match between the gateway's push payload and the client's handling code.
 */
object FcmPayloadKeys {
    const val SERVER_ID = "serverId"
    const val SESSION_ID = "sessionId"
    const val CWD = "cwd"
    const val TITLE = "title"
    const val BODY = "body"
    const val CATEGORY = "category"
}
