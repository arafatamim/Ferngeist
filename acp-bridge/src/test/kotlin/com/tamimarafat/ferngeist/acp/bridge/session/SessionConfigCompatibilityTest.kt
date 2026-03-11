package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.acp.bridge.connection.AcpSessionUpdateMapper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionConfigCompatibilityTest {

    @Test
    fun `resolve infers mode category for native option matching legacy modes`() {
        val nativeOption = SessionConfigOption.Select(
            id = "approval_preset",
            name = "Approval Preset",
            currentValue = "ask",
            choices = listOf(
                SessionConfigChoice(id = "ask", label = "Ask", value = "ask"),
                SessionConfigChoice(id = "code", label = "Code", value = "code"),
            ),
        )

        val resolved = SessionConfigCompatibility.resolve(
            nativeOptions = listOf(nativeOption),
            legacyModes = LegacyModeState(
                modes = listOf(
                    SessionMode(id = "ask", name = "Ask"),
                    SessionMode(id = "code", name = "Code"),
                ),
                currentModeId = "ask",
            ),
            legacyModel = null,
        )

        val modeOption = resolved.single() as SessionConfigOption.Select
        assertTrue(modeOption.category is SessionConfigCategory.Mode)
        assertEquals(SessionConfigOrigin.NativeConfigOption, modeOption.origin)
    }

    @Test
    fun `resolve synthesizes legacy model option when native config options are absent`() {
        val resolved = SessionConfigCompatibility.resolve(
            nativeOptions = emptyList(),
            legacyModes = null,
            legacyModel = LegacyModelState(
                choices = listOf(
                    SessionConfigChoice(id = "fast", label = "Fast", value = "fast"),
                    SessionConfigChoice(id = "smart", label = "Smart", value = "smart"),
                ),
                currentModelId = "smart",
            ),
        )

        val modelOption = resolved.single() as SessionConfigOption.Select
        assertTrue(modelOption.category is SessionConfigCategory.Model)
        assertEquals(SessionConfigOrigin.LegacyModel, modelOption.origin)
        assertEquals("smart", modelOption.currentValue)
    }

    @Test
    fun `sdk boolean option mapping preserves typed current value`() {
        val mapped = AcpSessionUpdateMapper.mapSdkConfigOption(
            com.agentclientprotocol.model.SessionConfigOption.boolean(
                id = "verbose",
                name = "Verbose",
                currentValue = true,
            )
        )

        val booleanOption = mapped as SessionConfigOption.BooleanOption
        assertEquals(true, booleanOption.currentValue)
        assertEquals(SessionConfigOrigin.NativeConfigOption, booleanOption.origin)
    }

    @Test
    fun `bridge updates synthetic legacy mode through set config option`() = runTest {
        val bridge = SessionBridge("test_session", null)
        bridge.emitEvent(
            AppSessionEvent.ModesUpdated(
                modes = listOf(
                    SessionMode(id = "ask", name = "Ask"),
                    SessionMode(id = "code", name = "Code"),
                ),
                currentModeId = "ask",
            )
        )

        val modeOptionId = bridge.snapshot.value.configOptions.single().id
        bridge.setConfigOption(modeOptionId, SessionConfigValue.StringValue("code"))

        val updated = bridge.snapshot.value.configOptions.single() as SessionConfigOption.Select
        assertEquals("code", updated.currentValue)
    }
}
