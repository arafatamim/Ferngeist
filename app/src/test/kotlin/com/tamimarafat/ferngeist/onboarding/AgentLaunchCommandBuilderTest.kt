package com.tamimarafat.ferngeist.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLaunchCommandBuilderTest {
    @Test
    fun build_wrapsNpxAgentsWithStdioToWs() {
        val instructions =
            AgentLaunchCommandBuilder.build(
                agent =
                    RegistryAgent(
                        id = "codex-acp",
                        name = "Codex CLI",
                        distribution =
                            AgentDistribution(
                                npx = PackageDistribution(`package` = "@zed-industries/codex-acp@0.9.5"),
                            ),
                    ),
                platform = PcPlatform.Windows,
                port = 6000,
            )

        assertEquals(
            "npx -y stdio-to-ws \"npx -y @zed-industries/codex-acp@0.9.5\" --port 6000",
            instructions.command,
        )
    }

    @Test
    fun build_keepsEnvironmentVariablesSeparate() {
        val instructions =
            AgentLaunchCommandBuilder.build(
                agent =
                    RegistryAgent(
                        id = "auggie",
                        name = "Auggie CLI",
                        distribution =
                            AgentDistribution(
                                npx =
                                    PackageDistribution(
                                        `package` = "@augmentcode/auggie@0.18.1",
                                        args = listOf("--acp"),
                                        env = mapOf("AUGMENT_DISABLE_AUTO_UPDATE" to "1"),
                                    ),
                            ),
                    ),
                platform = PcPlatform.Windows,
                port = 7000,
            )

        assertEquals(mapOf("AUGMENT_DISABLE_AUTO_UPDATE" to "1"), instructions.environment)
        assertEquals(
            "npx -y stdio-to-ws \"npx -y @augmentcode/auggie@0.18.1 --acp\" --port 7000",
            instructions.command,
        )
    }

    @Test
    fun build_binaryAgentIncludesDownloadNote() {
        val instructions =
            AgentLaunchCommandBuilder.build(
                agent =
                    RegistryAgent(
                        id = "opencode",
                        name = "OpenCode",
                        distribution =
                            AgentDistribution(
                                binary =
                                    mapOf(
                                        "windows-x86_64" to
                                            BinaryDistribution(
                                                archive = "https://example.com/opencode.zip",
                                                cmd = "./opencode.exe",
                                                args = listOf("acp"),
                                            ),
                                    ),
                            ),
                    ),
                platform = PcPlatform.Windows,
                port = 6000,
            )

        assertEquals(
            "npx -y stdio-to-ws \"./opencode.exe acp\" --port 6000",
            instructions.command,
        )
        assertTrue(instructions.notes.any { it.contains("https://example.com/opencode.zip") })
    }
}
