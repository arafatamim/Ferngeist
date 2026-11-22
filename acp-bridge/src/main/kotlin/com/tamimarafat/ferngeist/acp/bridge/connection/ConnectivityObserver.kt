package com.tamimarafat.ferngeist.acp.bridge.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.ktor.client.HttpClient
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

interface ConnectivityObserver {
    val isConnected: Flow<Boolean>
}

/**
 * What every ACP connection runs on: the process's one HTTP client, whose pool lets a socket
 * reuse a REST call's connection, and the network state that paces reconnects.
 */
data class AcpNetwork(
    val httpClient: HttpClient,
    val connectivity: ConnectivityObserver,
)

class AndroidConnectivityObserver(
    context: Context,
) : ConnectivityObserver {
    override val isConnected: Flow<Boolean> =
        callbackFlow {
            val manager = context.getSystemService(ConnectivityManager::class.java)!!
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        trySend(true)
                    }

                    override fun onLost(network: Network) {
                        trySend(false)
                    }
                }
            val request =
                NetworkRequest
                    .Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()

            manager.registerNetworkCallback(request, callback)
            trySend(manager.activeNetwork != null)

            awaitClose { manager.unregisterNetworkCallback(callback) }
        }.distinctUntilChanged()
}
