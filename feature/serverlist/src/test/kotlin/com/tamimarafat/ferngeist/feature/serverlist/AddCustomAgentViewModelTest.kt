package com.tamimarafat.ferngeist.feature.serverlist

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.gateway.GatewayAgent
import com.tamimarafat.ferngeist.gateway.GatewayAgentSecurity
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AddCustomAgentViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val gatewayRepository = mockk<GatewayRepository>(relaxed = true)
    private val gatewaySourceRepository = mockk<GatewaySourceRepository>(relaxed = true)
    private val application = mockk<Application>(relaxed = true)

    private val gateway =
        GatewaySource(id = "gw-1", name = "Home", scheme = "http", host = "gw.local:5788", gatewayCredential = "token")

    private fun viewModel() =
        AddCustomAgentViewModel(
            application = application,
            gatewaySourceRepository = gatewaySourceRepository,
            gatewayRepository = gatewayRepository,
            savedStateHandle = SavedStateHandle(mapOf("serverId" to "gw-1")),
        )

    private fun agent(detected: Boolean) =
        GatewayAgent(
            id = "custom-my-agent",
            displayName = "My Agent",
            detected = detected,
            manifestValid = true,
            security = GatewayAgentSecurity(allowsRemoteStart = true),
            source = "custom",
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { gatewaySourceRepository.getGateway("gw-1") } returns gateway
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `refuses to submit an invalid form`() =
        runTest {
            val vm = viewModel()
            vm.updateDisplayName("")
            vm.events.test {
                vm.submit()
                advanceUntilIdle()
                // The rule-to-message mapping is pinned by CustomAgentValidationTest;
                // here the contract is "rejected locally, before any request".
                assertTrue(awaitItem() is AddCustomAgentEvent.ShowError)
                cancelAndIgnoreRemainingEvents()
            }
            coVerify(exactly = 0) {
                gatewayRepository.createCustomAgent(any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `creates the agent and reports a detected result`() =
        runTest {
            coEvery {
                gatewayRepository.createCustomAgent(any(), any(), any(), any(), any(), any(), any())
            } returns agent(detected = true)
            val vm = viewModel()
            vm.updateDisplayName("My Agent")
            vm.updateCommand("my-agent")
            vm.updateArguments("--acp")
            vm.events.test {
                vm.submit()
                advanceUntilIdle()
                assertEquals(AddCustomAgentEvent.Created, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            coVerify {
                gatewayRepository.createCustomAgent(
                    "http",
                    "gw.local:5788",
                    "token",
                    "My Agent",
                    "my-agent",
                    listOf("--acp"),
                    "",
                )
            }
        }

    @Test
    fun `reports a not-detected result with the command`() =
        runTest {
            coEvery {
                gatewayRepository.createCustomAgent(any(), any(), any(), any(), any(), any(), any())
            } returns agent(detected = false)
            val vm = viewModel()
            vm.updateDisplayName("My Agent")
            vm.updateCommand("my-agent")
            vm.events.test {
                vm.submit()
                advanceUntilIdle()
                assertEquals(AddCustomAgentEvent.CreatedNotDetected("my-agent"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `surfaces the gateway message when creation is refused`() =
        runTest {
            val refusal =
                GatewayRequestException(
                    statusCode = 409,
                    message = "Gateway request failed: 409 Conflict",
                    responseBody = """{"error":"custom agent limit reached (50)"}""",
                )
            coEvery {
                gatewayRepository.createCustomAgent(any(), any(), any(), any(), any(), any(), any())
            } throws refusal
            val vm = viewModel()
            vm.updateDisplayName("My Agent")
            vm.updateCommand("my-agent")
            vm.events.test {
                vm.submit()
                advanceUntilIdle()
                assertEquals(AddCustomAgentEvent.ShowError("custom agent limit reached (50)"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `contains a transport fault instead of letting it escape the view model`() =
        runTest {
            // An IOException (host unreachable, TLS, malformed body) is not a
            // GatewayRequestException: uncaught it escapes viewModelScope.launch and kills
            // the process, so the form must report it and stay usable.
            coEvery {
                gatewayRepository.createCustomAgent(any(), any(), any(), any(), any(), any(), any())
            } throws IOException("unreachable host")
            val vm = viewModel()
            vm.updateDisplayName("My Agent")
            vm.updateCommand("my-agent")
            vm.events.test {
                vm.submit()
                advanceUntilIdle()
                assertTrue(awaitItem() is AddCustomAgentEvent.ShowError)
                cancelAndIgnoreRemainingEvents()
            }
            assertFalse(vm.isSubmitting.value)
        }
}
