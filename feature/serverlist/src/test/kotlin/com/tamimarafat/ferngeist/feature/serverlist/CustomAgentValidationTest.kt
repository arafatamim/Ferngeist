package com.tamimarafat.ferngeist.feature.serverlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomAgentValidationTest {
    private fun validate(
        name: String = "My Agent",
        command: String = "my-agent",
        args: List<String> = listOf("--acp"),
    ) = validateCustomAgent(name, command, args)

    @Test
    fun `accepts a bare path command`() = assertNull(validate())

    @Test
    fun `accepts an absolute posix path`() = assertNull(validate(command = "/usr/local/bin/agent"))

    @Test
    fun `accepts an absolute windows path`() = assertNull(validate(command = """C:\tools\agent.exe"""))

    @Test
    fun `accepts a windows path containing spaces`() = assertNull(validate(command = """C:\Program Files\agent.exe"""))

    @Test
    fun `rejects a blank name`() =
        assertEquals(R.string.serverlist_custom_agent_error_name_required, validate(name = "  "))

    @Test
    fun `rejects an oversized name`() =
        assertEquals(
            R.string.serverlist_custom_agent_error_name_too_long,
            validate(name = "a".repeat(81)),
        )

    @Test
    fun `rejects a blank command`() =
        assertEquals(R.string.serverlist_custom_agent_error_command_required, validate(command = " "))

    @Test
    fun `rejects an oversized command`() =
        assertEquals(
            R.string.serverlist_custom_agent_error_command_too_long,
            validate(command = "a".repeat(1025)),
        )

    @Test
    fun `rejects a bare name containing a space`() =
        assertEquals(
            R.string.serverlist_custom_agent_error_command_single_name,
            validate(command = "my agent"),
        )

    @Test
    fun `rejects a relative path`() =
        assertEquals(
            R.string.serverlist_custom_agent_error_command_absolute_or_name,
            validate(command = "tools/my-agent"),
        )

    @Test
    fun `rejects too many arguments`() =
        assertEquals(
            R.string.serverlist_custom_agent_error_too_many_args,
            validate(args = List(21) { "--flag" }),
        )

    @Test
    fun `rejects an oversized argument`() =
        assertEquals(
            R.string.serverlist_custom_agent_error_arg_too_long,
            validate(args = listOf("a".repeat(1025))),
        )

    @Test
    fun `checks arguments even when the command is an absolute path`() =
        assertEquals(
            R.string.serverlist_custom_agent_error_too_many_args,
            validate(command = "/usr/local/bin/agent", args = List(21) { "--flag" }),
        )

    @Test
    fun `splits arguments on whitespace and drops empties`() {
        assertEquals(listOf("--acp", "--verbose"), splitCustomAgentArguments("--acp   --verbose"))
        assertEquals(emptyList<String>(), splitCustomAgentArguments("   "))
    }
}
