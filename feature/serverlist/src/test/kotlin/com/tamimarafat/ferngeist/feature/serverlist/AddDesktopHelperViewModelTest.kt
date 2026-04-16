package com.tamimarafat.ferngeist.feature.serverlist

import com.tamimarafat.ferngeist.core.model.DesktopHelperSource
import com.tamimarafat.ferngeist.core.model.repository.DesktopHelperSourceRepository
import com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperPairStartResponse
import com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperPairingResult as PairingResult
import com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperRemoteStatus
import com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperRepository
import com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AddDesktopHelperViewModelTest {

    private lateinit var fakeHelperRepo: FakeDesktopHelperRepository
    private lateinit var fakeSourceRepo: FakeDesktopHelperSourceRepository
    private lateinit var viewModel: AddDesktopHelperViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeHelperRepo = FakeDesktopHelperRepository()
        fakeSourceRepo = FakeDesktopHelperSourceRepository()
        viewModel = AddDesktopHelperViewModel(
            helperSourceRepository = fakeSourceRepo,
            helperRepository = fakeHelperRepo,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `pairAndSaveWithCode emits error when code is blank`() = runTest(testDispatcher) {
        viewModel.updateHost("192.168.1.42:5788")
        viewModel.updateName("Test Helper")
        advanceUntilIdle()

        var errorMessage: String? = null
        val job = CoroutineScope(testDispatcher).launch {
            viewModel.events.collect { event ->
                if (event is AddDesktopHelperEvent.ShowError) {
                    errorMessage = event.message
                }
            }
        }
        advanceUntilIdle()

        viewModel.pairAndSaveWithCode("   ")
        advanceUntilIdle()
        job.cancel()

        assertNotNull(errorMessage)
        assertTrue(errorMessage!!.contains("pairing code", ignoreCase = true))
    }

    @Test
    fun `pairAndSaveWithCode emits error when host is blank`() = runTest(testDispatcher) {
        viewModel.updateName("Test Helper")
        advanceUntilIdle()

        var errorMessage: String? = null
        val job = kotlinx.coroutines.CoroutineScope(testDispatcher).launch {
            viewModel.events.collect { event ->
                if (event is AddDesktopHelperEvent.ShowError) {
                    errorMessage = event.message
                }
            }
        }
        advanceUntilIdle()

        viewModel.pairAndSaveWithCode("123456")
        advanceUntilIdle()
        job.cancel()

        assertNotNull(errorMessage)
        assertTrue(errorMessage!!.contains("host", ignoreCase = true))
    }

    @Test
    fun `pairAndSaveWithCode emits error when name is blank`() = runTest(testDispatcher) {
        viewModel.updateHost("192.168.1.42:5788")
        advanceUntilIdle()

        var errorMessage: String? = null
        val job = kotlinx.coroutines.CoroutineScope(testDispatcher).launch {
            viewModel.events.collect { event ->
                if (event is AddDesktopHelperEvent.ShowError) {
                    errorMessage = event.message
                }
            }
        }
        advanceUntilIdle()

        viewModel.pairAndSaveWithCode("123456")
        advanceUntilIdle()
        job.cancel()

        assertNotNull(errorMessage)
        assertTrue(errorMessage!!.contains("name", ignoreCase = true))
    }

    @Test
    fun `pairAndSaveWithCode uses explicit code and auto-fetches challenge when none exists`() = runTest(testDispatcher) {
        viewModel.updateHost("192.168.1.42:5788")
        viewModel.updateName("Test Helper")
        viewModel.updateScheme("http")
        advanceUntilIdle()

        fakeHelperRepo.startPairingResult = DesktopHelperPairStartResponse(
            challengeId = "challenge-abc",
            expiresAt = Instant.now().plusSeconds(3600).toString(),
        )
        fakeHelperRepo.completePairingResult = PairingResult(
            deviceId = "device-1",
            deviceName = "Test Helper",
            helperCredential = "cred-token",
            expiresAt = Instant.now().plusSeconds(86400).toString(),
        )

        var savedHelper: DesktopHelperSource? = null
        fakeSourceRepo.onAddHelper = { helper -> savedHelper = helper }

        viewModel.pairAndSaveWithCode("123456")
        advanceUntilIdle()

        assertTrue(fakeHelperRepo.startPairingCalled)
        assertTrue(fakeHelperRepo.completePairingCalled)
        assertEquals("challenge-abc", fakeHelperRepo.lastCompletePairingChallengeId)
        assertEquals("123456", fakeHelperRepo.lastCompletePairingCode)
        assertNotNull(savedHelper)
    }

    @Test
    fun `pairAndSaveWithCode emits error when startPairing fails`() = runTest(testDispatcher) {
        viewModel.updateHost("192.168.1.42:5788")
        viewModel.updateName("Test Helper")
        viewModel.updateScheme("http")
        advanceUntilIdle()

        fakeHelperRepo.startPairingException = RuntimeException("Network error")

        var errorMessage: String? = null
        val job = kotlinx.coroutines.CoroutineScope(testDispatcher).launch {
            viewModel.events.collect { event ->
                if (event is AddDesktopHelperEvent.ShowError) {
                    errorMessage = event.message
                }
            }
        }
        advanceUntilIdle()

        viewModel.pairAndSaveWithCode("123456")
        advanceUntilIdle()
        job.cancel()

        assertNotNull(errorMessage)
        assertTrue(errorMessage!!.contains("pairing", ignoreCase = true))
        assertTrue(!fakeHelperRepo.completePairingCalled)
    }

    @Test
    fun `pairAndSaveWithCode emits error when completePairing fails`() = runTest(testDispatcher) {
        viewModel.updateHost("192.168.1.42:5788")
        viewModel.updateName("Test Helper")
        viewModel.updateScheme("http")
        advanceUntilIdle()

        fakeHelperRepo.startPairingResult = DesktopHelperPairStartResponse(
            challengeId = "challenge-abc",
            expiresAt = Instant.now().plusSeconds(3600).toString(),
        )
        fakeHelperRepo.completePairingException = RuntimeException("Invalid code")

        var errorMessage: String? = null
        val job = kotlinx.coroutines.CoroutineScope(testDispatcher).launch {
            viewModel.events.collect { event ->
                if (event is AddDesktopHelperEvent.ShowError) {
                    errorMessage = event.message
                }
            }
        }
        advanceUntilIdle()

        viewModel.pairAndSaveWithCode("wrong")
        advanceUntilIdle()
        job.cancel()

        assertNotNull(errorMessage)
        assertTrue(errorMessage!!.contains("complete pairing", ignoreCase = true))
    }

    @Test
    fun `pairAndSaveWithCode trims code before using`() = runTest(testDispatcher) {
        viewModel.updateHost("192.168.1.42:5788")
        viewModel.updateName("Test Helper")
        viewModel.updateScheme("http")
        advanceUntilIdle()

        fakeHelperRepo.startPairingResult = DesktopHelperPairStartResponse(
            challengeId = "challenge-abc",
            expiresAt = Instant.now().plusSeconds(3600).toString(),
        )
        fakeHelperRepo.completePairingResult = PairingResult(
            deviceId = "device-1",
            deviceName = "Test Helper",
            helperCredential = "cred-token",
            expiresAt = Instant.now().plusSeconds(86400).toString(),
        )

        var savedHelper: DesktopHelperSource? = null
        fakeSourceRepo.onAddHelper = { helper -> savedHelper = helper }

        viewModel.pairAndSaveWithCode("  123456  ")
        advanceUntilIdle()

        assertEquals("123456", fakeHelperRepo.lastCompletePairingCode)
        assertNotNull(savedHelper)
    }
}

private class FakeDesktopHelperRepository : DesktopHelperRepository {
    var startPairingCalled = false
    var startPairingResult: DesktopHelperPairStartResponse? = null
    var startPairingException: RuntimeException? = null
    var completePairingCalled = false
    var lastCompletePairingChallengeId: String? = null
    var lastCompletePairingCode: String? = null
    var completePairingResult: PairingResult? = null
    var completePairingException: RuntimeException? = null

    override suspend fun fetchStatus(scheme: String, host: String) =
        DesktopHelperStatus("Fake", "1.0.0", DesktopHelperRemoteStatus(false))

    override suspend fun startPairing(scheme: String, host: String): DesktopHelperPairStartResponse {
        startPairingCalled = true
        if (startPairingException != null) throw startPairingException!!
        return startPairingResult!!
    }

    override suspend fun getPairingStatus(
        scheme: String,
        host: String,
        challengeId: String,
    ): com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperPairStatusResponse =
        throw NotImplementedError()

    override suspend fun fetchAgents(
        scheme: String,
        host: String,
        helperCredential: String,
    ): List<com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperAgent> = emptyList()

    override suspend fun startAgent(
        scheme: String,
        host: String,
        helperCredential: String,
        agentId: String,
    ) = throw NotImplementedError()

    override suspend fun connectRuntime(
        scheme: String,
        host: String,
        helperCredential: String,
        runtimeId: String,
    ) = throw NotImplementedError()

    override suspend fun restartRuntime(
        scheme: String,
        host: String,
        helperCredential: String,
        runtimeId: String,
        envVars: Map<String, String>,
    ) = throw NotImplementedError()

    override suspend fun fetchRuntimeLogs(
        scheme: String,
        host: String,
        helperCredential: String,
        runtimeId: String,
    ): List<com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperLogEntry> = emptyList()

    override suspend fun completePairing(
        scheme: String,
        host: String,
        challengeId: String,
        code: String,
        deviceName: String,
    ): PairingResult {
        completePairingCalled = true
        lastCompletePairingChallengeId = challengeId
        lastCompletePairingCode = code
        if (completePairingException != null) throw completePairingException!!
        return completePairingResult!!
    }

    override suspend fun refreshCredential(scheme: String, host: String, helperCredential: String) =
        throw NotImplementedError()
}

private class FakeDesktopHelperSourceRepository : DesktopHelperSourceRepository {
    var onAddHelper: ((DesktopHelperSource) -> Unit)? = null

    override fun getHelpers(): Flow<List<DesktopHelperSource>> = flowOf(emptyList())
    override suspend fun addHelper(helper: DesktopHelperSource) { onAddHelper?.invoke(helper) }
    override suspend fun updateHelper(helper: DesktopHelperSource) {}
    override suspend fun deleteHelper(id: String) {}
    override suspend fun getHelper(id: String): DesktopHelperSource? = null
}
