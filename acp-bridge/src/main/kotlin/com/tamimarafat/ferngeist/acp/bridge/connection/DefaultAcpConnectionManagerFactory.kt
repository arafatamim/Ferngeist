package com.tamimarafat.ferngeist.acp.bridge.connection

import com.tamimarafat.ferngeist.gateway.GatewayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Default [AcpConnectionManagerFactory] used by the app.
 *
 * Every created manager is registered with the shared [AcpManagerRegistry] so
 * process-wide observers (foreground service, battery gate) can react to any
 * connection, not just the first one they were handed.
 *
 * When the manager's owning scope completes (e.g. the ViewModel that created
 * it is cleared), the manager is unregistered and closed ([AcpConnectionManager.close]
 * disconnects the transport and releases the heavyweight HTTP client): a
 * dead-scope transport is a zombie (no reconnect loop alive) and the gateway's
 * resilient session makes return cheap. Task 5 (ChatConnectionHub) relocates
 * lifetime control with a proper stay-alive policy.
 */
class DefaultAcpConnectionManagerFactory(
    private val connectivityObserver: ConnectivityObserver,
    private val gatewayRepository: GatewayRepository,
    private val registry: AcpManagerRegistry,
) : AcpConnectionManagerFactory {
    override fun create(scope: CoroutineScope): AcpConnectionManager =
        AcpConnectionManager(connectivityObserver, gatewayRepository, scope).also { manager ->
            registry.register(manager)
            scope.coroutineContext[Job]?.invokeOnCompletion {
                registry.unregister(manager)
                manager.close()
            }
        }
}
