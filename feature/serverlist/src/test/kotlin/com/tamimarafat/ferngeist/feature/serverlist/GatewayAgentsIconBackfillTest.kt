package com.tamimarafat.ferngeist.feature.serverlist

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.tamimarafat.ferngeist.core.model.GatewayAgentBinding
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewayAgentBindingRepository
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.gateway.GatewayAgent
import com.tamimarafat.ferngeist.gateway.GatewayAgentRegistry
import com.tamimarafat.ferngeist.gateway.GatewayAgentSecurity
import com.tamimarafat.ferngeist.gateway.GatewayRepository
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
import org.junit.Before
import org.junit.Test

/**
 * The icon is captured once, when an agent is added. A binding written before the
 * gateway exposed icons would otherwise keep a null icon forever, so a refresh
 * repairs it from the gateway's current view.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayAgentsIconBackfillTest {
    private val dispatcher = StandardTestDispatcher()
    private val gatewayRepository = mockk<GatewayRepository>(relaxed = true)
    private val gatewaySourceRepository = mockk<GatewaySourceRepository>(relaxed = true)
    private val bindingRepository = mockk<GatewayAgentBindingRepository>(relaxed = true)
    private val application = mockk<Application>(relaxed = true)

    private val gateway =
        GatewaySource(
            id = "gw-1",
            name = "Home",
            scheme = "http",
            host = "gw.local:5788",
            gatewayCredential = "token",
        )

    private fun registryAgent(icon: String?) =
        GatewayAgent(
            id = "codex-acp",
            displayName = "Codex",
            detected = true,
            manifestValid = true,
            security = GatewayAgentSecurity(allowsRemoteStart = true),
            source = "registry",
            registry = icon?.let { GatewayAgentRegistry(icon = it) },
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
        every { bindingRepository.getBindings() } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a binding stored without an icon gains one on refresh`() =
        runTest(dispatcher) {
            val stale =
                GatewayAgentBinding(
                    id = "b-1",
                    name = "Codex",
                    gatewaySourceId = "gw-1",
                    agentId = "codex-acp",
                    icon = null,
                )
            coEvery { bindingRepository.getBindingsForGateway("gw-1") } returns listOf(stale)
            val iconUrl = "https://cdn.agentclientprotocol.com/registry/v1/latest/codex-acp.svg"
            coEvery { gatewayRepository.fetchAgents(any(), any(), any()) } returns
                listOf(registryAgent(iconUrl))

            viewModel()
            advanceUntilIdle()

            coVerify(exactly = 1) {
                bindingRepository.updateBinding(stale.copy(icon = iconUrl))
            }
        }

    @Test
    fun `a binding whose icon already matches is not rewritten`() =
        runTest(dispatcher) {
            val iconUrl = "https://cdn.agentclientprotocol.com/registry/v1/latest/codex-acp.svg"
            val current =
                GatewayAgentBinding(
                    id = "b-1",
                    name = "Codex",
                    gatewaySourceId = "gw-1",
                    agentId = "codex-acp",
                    icon = iconUrl,
                )
            coEvery { bindingRepository.getBindingsForGateway("gw-1") } returns listOf(current)
            coEvery { gatewayRepository.fetchAgents(any(), any(), any()) } returns
                listOf(registryAgent(iconUrl))

            viewModel()
            advanceUntilIdle()

            coVerify(exactly = 0) { bindingRepository.updateBinding(any()) }
        }

    @Test
    fun `a renamed agent updates the stored name`() =
        runTest(dispatcher) {
            val renamed =
                GatewayAgentBinding(
                    id = "b-1",
                    name = "Old Name",
                    gatewaySourceId = "gw-1",
                    agentId = "codex-acp",
                )
            coEvery { bindingRepository.getBindingsForGateway("gw-1") } returns listOf(renamed)
            val iconUrl = "https://cdn.agentclientprotocol.com/registry/v1/latest/codex-acp.svg"
            coEvery { gatewayRepository.fetchAgents(any(), any(), any()) } returns
                listOf(registryAgent(iconUrl))

            viewModel()
            advanceUntilIdle()

            coVerify(exactly = 1) {
                bindingRepository.updateBinding(renamed.copy(name = "Codex", icon = iconUrl))
            }
        }
}
