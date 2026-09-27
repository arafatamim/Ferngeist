package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.protocol.JsonRpcException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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

/**
 * True when the failure is a real cancellation rather than a failure.
 *
 * Only the type is evidence. A wording test ("was cancelled", "StandaloneCoroutine")
 * also matched *failures* that merely mentioned cancellation — a gateway error body, a
 * peer's "handshake was cancelled by the peer" — and callers route on this predicate:
 * `AcpTransportClient.handleEstablishFailure` and `handleUnexpectedTransportTermination`
 * mark such a connection Disconnected and skip both the diagnostics entry and the
 * reconnect, so a real failure produced a dead chat with no cause on record.
 *
 * [kotlinx.coroutines.TimeoutCancellationException] is excluded on purpose: it is a
 * `CancellationException` by inheritance but means "we ran out of time", which must
 * still surface as a failure.
 */
fun isCancellationLikeError(error: Throwable): Boolean =
    generateSequence(error as Throwable?) { it.cause }.any { cause ->
        cause is CancellationException && cause !is TimeoutCancellationException
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
