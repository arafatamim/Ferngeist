package com.tamimarafat.ferngeist.acp.bridge.connection.gateway

/**
 * `POST /v1/sessions/{id}/resume` answered HTTP 400: the gateway no longer has that
 * session (it restarted, or the session's agent failed). Retrying the same session id
 * can never succeed, so the transport stops its reconnect loop and reports
 * [AcpManagerEvent.SessionResumeRejected]; the facade starts a fresh connect instead.
 */
class GatewaySessionResumeRejectedException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
