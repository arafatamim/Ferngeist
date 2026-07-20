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

/**
 * True when a WebSocket upgrade failed with HTTP 409 Conflict anywhere in the
 * cause chain. The gateway returns 409 when a resilient session already has an
 * attached client, signalling that the resume flow must run before re-attaching.
 * Ktor surfaces this as "Expected status code 101 but was 409", often nested in
 * a cause rather than on the top-level exception.
 */
fun isWebSocketConflictError(error: Throwable): Boolean =
    generateSequence(error as Throwable?) { it.cause }.any { cause ->
        val message = cause.message.orEmpty()
        message.contains("but was 409") || message.contains("409 Conflict", ignoreCase = true)
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

private const val MAX_ERROR_DATA_CHARS = 200

private fun stringifyJsonRpcData(data: JsonElement?): String? =
    when (data) {
        null, JsonNull -> null
        is JsonPrimitive -> if (data.isString) data.content else data.toString()
        else -> data.toString()
    }?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
        if (text.length > MAX_ERROR_DATA_CHARS) {
            text.take(MAX_ERROR_DATA_CHARS).trimEnd() + "… (truncated)"
        } else {
            text
        }
    }
