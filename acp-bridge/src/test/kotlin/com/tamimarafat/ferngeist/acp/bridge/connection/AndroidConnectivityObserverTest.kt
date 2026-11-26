package com.tamimarafat.ferngeist.acp.bridge.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import app.cash.turbine.test
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidConnectivityObserverTest {
    private val callback = slot<ConnectivityManager.NetworkCallback>()
    private val manager =
        mockk<ConnectivityManager> {
            every { registerNetworkCallback(any<NetworkRequest>(), capture(callback)) } just runs
            every { unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just runs
            every { activeNetwork } returns null
        }
    private val context = mockk<Context> { every { getSystemService(ConnectivityManager::class.java) } returns manager }

    init {
        mockkConstructor(NetworkRequest.Builder::class)
        val builder = mockk<NetworkRequest.Builder> { every { build() } returns mockk() }
        every { anyConstructed<NetworkRequest.Builder>().addCapability(any()) } returns builder
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `losing one network stays online while another network is still up`() =
        runTest {
            val wifi = mockk<Network>()
            val cellular = mockk<Network>()
            AndroidConnectivityObserver(context).isConnected.test {
                assertEquals(false, awaitItem())
                callback.captured.onAvailable(wifi)
                callback.captured.onAvailable(cellular)
                assertEquals(true, awaitItem())
                callback.captured.onLost(cellular)
                expectNoEvents()
                callback.captured.onLost(wifi)
                assertEquals(false, awaitItem())
            }
        }
}
