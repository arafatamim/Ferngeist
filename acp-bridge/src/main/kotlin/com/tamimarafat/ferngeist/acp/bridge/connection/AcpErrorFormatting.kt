package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.protocol.JsonRpcException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

fun formatAcpErrorMessage(
    error: Throwable,
    fallback: String,
): String =
    when {
        isCancellationLikeError(error) -> fallback
        error is JsonRpcException -> formatJsonRpcErrorMessage(error, fallback)
        else -> error.message?.takeIf { it.isNotBlank() } ?: fallback
    }

fun isCancellationLikeError(error: Throwable): Boolean =
    generateSequence(error as Throwable?) { it.cause }.any { cause ->
        if (cause is CancellationException) {
            true
        } else {
            val message = cause.message?.trim().orEmpty()
            message.contains("StandaloneCoroutine", ignoreCase = true) ||
                message.contains("was cancelled", ignoreCase = true) ||
                message.contains("was canceled", ignoreCase = true)
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

private fun stringifyJsonRpcData(data: JsonElement?): String? =
    when (data) {
        null, JsonNull -> null
        is JsonPrimitive -> if (data.isString) data.content else data.toString()
        else -> data.toString()
    }?.trim()?.takeIf { it.isNotEmpty() }
