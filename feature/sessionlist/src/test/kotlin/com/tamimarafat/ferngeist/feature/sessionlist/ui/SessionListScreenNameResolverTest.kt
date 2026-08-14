package com.tamimarafat.ferngeist.feature.sessionlist.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionListScreenNameResolverTest {
    @Test
    fun `resolveServerDisplayName returns loadedName when it is non-blank`() {
        assertEquals("My Server", resolveServerDisplayName(navArgName = "from arg", loadedName = "My Server"))
        assertEquals("Single", resolveServerDisplayName(navArgName = null, loadedName = "Single"))
        assertEquals("  Whitespace  ", resolveServerDisplayName(navArgName = "", loadedName = "  Whitespace  "))
    }

    @Test
    fun `resolveServerDisplayName returns navArgName when loadedName is blank but navArgName is non-blank`() {
        assertEquals("From Arg", resolveServerDisplayName(navArgName = "From Arg", loadedName = ""))
        assertEquals("From Arg 2", resolveServerDisplayName(navArgName = "From Arg 2", loadedName = "  "))
        assertEquals("From Arg 3", resolveServerDisplayName(navArgName = "From Arg 3", loadedName = null))
    }

    @Test
    fun `resolveServerDisplayName returns Sessions when both names are blank or null`() {
        assertEquals("Sessions", resolveServerDisplayName(navArgName = null, loadedName = null))
        assertEquals("Sessions", resolveServerDisplayName(navArgName = "", loadedName = ""))
        assertEquals("Sessions", resolveServerDisplayName(navArgName = "  ", loadedName = null))
        assertEquals("Sessions", resolveServerDisplayName(navArgName = null, loadedName = "  "))
    }
}
