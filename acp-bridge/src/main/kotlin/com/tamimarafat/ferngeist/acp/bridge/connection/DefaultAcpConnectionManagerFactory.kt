package com.tamimarafat.ferngeist.acp.bridge.connection

import com.tamimarafat.ferngeist.gateway.GatewayRepository
import kotlinx.coroutines.CoroutineScope

/**
 * Default [AcpConnectionManagerFactory] used by the app.
 *
 * Every created manager is registered with the shared [AcpManagerRegistry] so
 * process-wide observers (foreground service, battery gate) can react to any
 * connection, not just the first one they were handed.
 */
class DefaultAcpConnectionManagerFactory(
    private val connectivityObserver: ConnectivityObserver,
    private val gatewayRepository: GatewayRepository,
    private val registry: AcpManagerRegistry,
) : AcpConnectionManagerFactory {
    override fun create(scope: CoroutineScope): AcpConnectionManager =
        AcpConnectionManager(connectivityObserver, gatewayRepository, scope).also { manager ->
            registry.register(manager)
        }
}
