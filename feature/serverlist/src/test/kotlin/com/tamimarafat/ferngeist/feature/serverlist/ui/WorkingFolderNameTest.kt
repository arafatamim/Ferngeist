package com.tamimarafat.ferngeist.feature.serverlist.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkingFolderNameTest {
    @Test
    fun `returns the leaf directory of a posix path`() {
        assertEquals("Ferngeist", workingFolderName("/home/tamim/src/Ferngeist"))
    }

    @Test
    fun `returns the leaf directory of a windows path`() {
        assertEquals("Ferngeist", workingFolderName("""C:\Users\tamim\src\Ferngeist"""))
    }

    @Test
    fun `ignores trailing separators`() {
        assertEquals("Ferngeist", workingFolderName("/home/tamim/src/Ferngeist/"))
        assertEquals("Ferngeist", workingFolderName("""C:\Users\tamim\src\Ferngeist\"""))
    }

    @Test
    fun `keeps a single segment path intact`() {
        assertEquals("Ferngeist", workingFolderName("Ferngeist"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("Ferngeist", workingFolderName("  /home/tamim/Ferngeist  "))
    }

    @Test
    fun `returns null when there is no cwd`() {
        assertNull(workingFolderName(null))
    }

    @Test
    fun `returns null for a blank cwd`() {
        assertNull(workingFolderName("   "))
    }

    @Test
    fun `returns null for a root path because it names no folder`() {
        assertNull(workingFolderName("/"))
        assertNull(workingFolderName("""C:\"""))
    }
}
