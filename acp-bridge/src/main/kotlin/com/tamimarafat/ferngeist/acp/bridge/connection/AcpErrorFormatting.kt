package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.protocol.JsonRpcException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

fun formatAcpErrorMessage(
    error: Throwable,
    fallback: String,
): String {
    return when (error) {
        is JsonRpcException -> formatJsonRpcErrorMessage(error, fallback)
        else -> error.message?.takeIf { it.isNotBlank() } ?: fallback
    }
}

private fun formatJsonRpcErrorMessage(
    error: JsonRpcException,
    fallback: String,
): String {
    val message = error.message.takeIf { it.isNotBlank() } ?: fallback
    val formattedData = stringifyJsonRpcData(error.data)
    if (formattedData.isNullOrBlank()) return message
    if (message.contains(formattedData)) return message
    return "$message: $formattedData"
}

private fun stringifyJsonRpcData(data: JsonElement?): String? {
    return when (data) {
        null, JsonNull -> null
        is JsonPrimitive -> if (data.isString) data.content else data.toString()
        else -> data.toString()
    }?.trim()?.takeIf { it.isNotEmpty() }
}
