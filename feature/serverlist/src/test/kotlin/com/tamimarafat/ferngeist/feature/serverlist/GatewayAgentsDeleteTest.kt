package com.tamimarafat.ferngeist.feature.serverlist

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.tamimarafat.ferngeist.core.model.GatewayAgentBinding
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewayAgentBindingRepository
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.gateway.GatewayAgent
import com.tamimarafat.ferngeist.gateway.GatewayAgentSecurity
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayAgentsDeleteTest {
    private val dispatcher = StandardTestDispatcher()
    private val gatewayRepository = mockk<GatewayRepository>(relaxed = true)
    private val gatewaySourceRepository = mockk<GatewaySourceRepository>(relaxed = true)
    private val bindingRepository = mockk<GatewayAgentBindingRepository>(relaxed = true)
    private val application = mockk<Application>(relaxed = true)

    private val gateway =
        GatewaySource(id = "gw-1", name = "Home", scheme = "http", host = "gw.local:5788", gatewayCredential = "token")

    private val agent =
        GatewayAgent(
            id = "custom-my-agent",
            displayName = "My Agent",
            detected = true,
            manifestValid = true,
            security = GatewayAgentSecurity(allowsRemoteStart = true),
            source = "custom",
        )

    private val runningRefusal =
        GatewayRequestException(
            statusCode = 409,
            message = "Gateway request failed: 409 Conflict",
            responseBody = """{"error":"agent has running runtimes"}""",
        )

    private fun viewModel() =
        GatewayAgentsViewModel(
            application = application,
            savedStateHandle = SavedStateHandle(mapOf("serverId" to "gw-1")),
            gatewaySourceRepository = gatewaySourceRepository,
            gatewayAgentBindingRepository = bindingRepository,
            gatewayRepository = gatewayRepository,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { gatewaySourceRepository.getGateway("gw-1") } returns gateway
        coEvery { bindingRepository.getBindingsForGateway("gw-1") } returns
            listOf(GatewayAgentBinding(id = "b-1", name = "My Agent", gatewaySourceId = "gw-1", agentId = agent.id))
        every { bindingRepository.getBindings() } returns flowOf(emptyList())
        coEvery { gatewayRepository.fetchAgents(any(), any(), any()) } returns listOf(agent)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `deletes the agent and its local binding`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            vm.confirmDelete(agent, stopFirst = false)
            advanceUntilIdle()
            coVerify { gatewayRepository.deleteCustomAgent("http", "gw.local:5788", "token", "custom-my-agent") }
            coVerify { bindingRepository.deleteBinding("b-1") }
        }

    @Test
    fun `a live runtime surfaces the stop offer instead of an error`() =
        runTest {
            coEvery { gatewayRepository.deleteCustomAgent(any(), any(), any(), any()) } throws runningRefusal
            val vm = viewModel()
            advanceUntilIdle()
            vm.confirmDelete(agent, stopFirst = false)
            advanceUntilIdle()
            assertEquals(CustomAgentDelete.Running(agent), vm.pendingDelete.value)
            coVerify(exactly = 0) { bindingRepository.deleteBinding(any()) }
        }

    @Test
    fun `stopping first deletes the runtime and then the agent`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            vm.confirmDelete(agent, stopFirst = true)
            advanceUntilIdle()
            coVerify { gatewayRepository.stopAgent("http", "gw.local:5788", "token", "custom-my-agent") }
            coVerify { gatewayRepository.deleteCustomAgent("http", "gw.local:5788", "token", "custom-my-agent") }
            coVerify { bindingRepository.deleteBinding("b-1") }
        }

    @Test
    fun `a already-stopped runtime does not block the delete`() =
        runTest {
            coEvery { gatewayRepository.stopAgent(any(), any(), any(), any()) } throws
                GatewayRequestException(
                    statusCode = 404,
                    message = "Gateway request failed: 404 Not Found",
                    responseBody = null,
                )
            val vm = viewModel()
            advanceUntilIdle()
            vm.confirmDelete(agent, stopFirst = true)
            advanceUntilIdle()
            coVerify { gatewayRepository.deleteCustomAgent("http", "gw.local:5788", "token", "custom-my-agent") }
            coVerify { bindingRepository.deleteBinding("b-1") }
        }
}
