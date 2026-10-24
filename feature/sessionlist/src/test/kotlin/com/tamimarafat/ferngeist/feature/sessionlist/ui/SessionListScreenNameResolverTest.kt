package com.tamimarafat.ferngeist.feature.sessionlist.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionListScreenNameResolverTest {
    @Test
    fun `resolveServerDisplayName returns loadedName when it is non-blank`() {
        assertEquals(
            "My Server",
            resolveServerDisplayName(navArgName = "from arg", loadedName = "My Server", defaultName = "Sessions"),
        )
        assertEquals(
            "Single",
            resolveServerDisplayName(navArgName = null, loadedName = "Single", defaultName = "Sessions"),
        )
        assertEquals(
            "  Whitespace  ",
            resolveServerDisplayName(navArgName = "", loadedName = "  Whitespace  ", defaultName = "Sessions"),
        )
    }

    @Test
    fun `resolveServerDisplayName returns navArgName when loadedName is blank but navArgName is non-blank`() {
        assertEquals(
            "From Arg",
            resolveServerDisplayName(navArgName = "From Arg", loadedName = "", defaultName = "Sessions"),
        )
        assertEquals(
            "From Arg 2",
            resolveServerDisplayName(navArgName = "From Arg 2", loadedName = "  ", defaultName = "Sessions"),
        )
        assertEquals(
            "From Arg 3",
            resolveServerDisplayName(navArgName = "From Arg 3", loadedName = null, defaultName = "Sessions"),
        )
    }

    @Test
    fun `resolveServerDisplayName returns the caller's default when both names are blank or null`() {
        assertEquals(
            "Sessions",
            resolveServerDisplayName(navArgName = null, loadedName = null, defaultName = "Sessions"),
        )
        assertEquals(
            "Sessions",
            resolveServerDisplayName(navArgName = "", loadedName = "", defaultName = "Sessions"),
        )
        assertEquals(
            "Sessions",
            resolveServerDisplayName(navArgName = "  ", loadedName = null, defaultName = "Sessions"),
        )
        assertEquals(
            "Sessions",
            resolveServerDisplayName(navArgName = null, loadedName = "  ", defaultName = "Sessions"),
        )
    }
}
