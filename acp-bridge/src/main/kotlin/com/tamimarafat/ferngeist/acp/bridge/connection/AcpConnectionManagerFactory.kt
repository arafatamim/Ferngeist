package com.tamimarafat.ferngeist.acp.bridge.connection

import kotlinx.coroutines.CoroutineScope

/** Builds independent [AcpConnectionManager] instances — one per chat or browser surface. */
interface AcpConnectionManagerFactory {
    fun create(scope: CoroutineScope): AcpConnectionManager
}
