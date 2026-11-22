package com.tamimarafat.ferngeist.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import okhttp3.Interceptor
import okhttp3.Protocol
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Bounds whole-call REST latency so network-backed spinners can never hang. Ktor skips it for WebSockets. */
private const val HTTP_REQUEST_TIMEOUT_MS = 30_000L

/** Bounds TCP/TLS establishment for every REST call and WebSocket upgrade. */
private const val HTTP_CONNECT_TIMEOUT_MS = 10_000L

/** Bounds inactivity between packets; the WebSocket ping keeps an idle socket under it. */
private const val HTTP_SOCKET_TIMEOUT_MS = 30_000L

// Keeps the WebSocket alive and detects dead peers: OkHttp pings on this interval and fails
// the socket when a pong does not come back. The Ktor plugin's ping is ignored on OkHttp.
private const val WEB_SOCKET_PING_INTERVAL_SECONDS = 15L

private const val HTTP_SWITCHING_PROTOCOLS = 101

/** A WebSocket upgrade the server answered with something other than 101, with what it said. */
class WebSocketUpgradeRefusedException(
    val statusCode: Int,
    val statusDescription: String,
    val dateHeader: String?,
    val responseBody: String,
) : IOException("WebSocket upgrade refused: $statusCode $statusDescription")

/**
 * The process's one HTTP client, for REST and WebSockets alike: one OkHttp connection pool, so
 * a WebSocket upgrade picks up the idle keep-alive connection a REST call just used instead of
 * paying a fresh TCP and TLS handshake (over a relay like Tailscale Funnel, about a second).
 */
fun gatewayHttpClient(): HttpClient =
    HttpClient(OkHttp) {
        engine {
            config {
                // OkHttp runs every WebSocket over HTTP/1.1 and pools connections per protocol
                // list, so a client that also offered h2 would never lend one to an upgrade.
                protocols(listOf(Protocol.HTTP_1_1))
                pingInterval(WEB_SOCKET_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
                addInterceptor(refusedUpgradeInterceptor)
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = HTTP_REQUEST_TIMEOUT_MS
            connectTimeoutMillis = HTTP_CONNECT_TIMEOUT_MS
            socketTimeoutMillis = HTTP_SOCKET_TIMEOUT_MS
        }
        // No Ktor deflate extension: OkHttp always offers permessage-deflate itself, and its
        // Ktor session rejects any negotiated Ktor extension.
        install(WebSockets)
    }

// Ktor's OkHttp engine hands a refused upgrade back as a response only for 401; any other status
// is a bare ProtocolException, which would lose the 404 that tells an old gateway apart.
private val refusedUpgradeInterceptor =
    Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (chain.request().header("Upgrade") == null || response.code == HTTP_SWITCHING_PROTOCOLS) {
            return@Interceptor response
        }
        val body = response.use { it.body.string() }
        throw WebSocketUpgradeRefusedException(response.code, response.message, response.header("Date"), body)
    }
