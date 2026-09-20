package com.tamimarafat.ferngeist.feature.sessionlist.cwd

import com.tamimarafat.ferngeist.core.model.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class CwdFilterTest {
    private val sessions =
        listOf(
            session("a", "/work/api"),
            session("b", "/work/app"),
            session("c", "/work/api"),
            session("d", null),
        )

    @Test
    fun noFilter_keepsEverySession() {
        assertEquals(sessions, filterSessionsByCwd(sessions, null))
        assertEquals(sessions, filterSessionsByCwd(sessions, ""))
        assertEquals(sessions, filterSessionsByCwd(sessions, "   "))
    }

    @Test
    fun filter_keepsOnlySessionsInThatDirectory() {
        val visible = filterSessionsByCwd(sessions, "/work/api")

        assertEquals(listOf("a", "c"), visible.map { it.id })
    }

    @Test
    fun filter_matchesHandTypedVariantsOfTheGatewayPath() {
        val windows = listOf(session("w", "C:\\Projects\\Ferngeist"))

        assertEquals(
            listOf("w"),
            filterSessionsByCwd(windows, "c:\\projects\\ferngeist\\").map { it.id },
        )
        assertEquals(
            listOf("w"),
            filterSessionsByCwd(windows, " C:\\Projects\\Ferngeist ").map { it.id },
        )
    }

    @Test
    fun filter_hidesSessionsWithUnknownDirectory() {
        assertEquals(emptyList<String>(), filterSessionsByCwd(listOf(session("d", null)), "/work/api").map { it.id })
    }

    private fun session(
        id: String,
        cwd: String?,
    ) = SessionSummary(id = id, title = null, cwd = cwd, updatedAt = null, serverId = "server-1")
}
