package com.tamimarafat.ferngeist.feature.chat.syntax

import com.tamimarafat.ferngeist.core.git.syntax.SyntaxLanguageResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyntaxLanguageResolverTest {
    @Test
    fun `fence keeps only the first token and strips braces`() {
        assertEquals("kotlin", SyntaxLanguageResolver.fromFence("Kotlin title=Main.kt"))
        assertEquals("ts", SyntaxLanguageResolver.fromFence("{.ts}"))
        assertEquals("c++", SyntaxLanguageResolver.fromFence("c++"))
        assertNull(SyntaxLanguageResolver.fromFence("   "))
        assertNull(SyntaxLanguageResolver.fromFence(null))
    }

    @Test
    fun `special filenames resolve before extensions`() {
        assertEquals("dockerfile", SyntaxLanguageResolver.fromPath("ops/Dockerfile"))
        assertEquals("dockerfile", SyntaxLanguageResolver.fromPath("Containerfile"))
        assertEquals("makefile", SyntaxLanguageResolver.fromPath("Makefile"))
        assertEquals("dotenv", SyntaxLanguageResolver.fromPath("app/.env"))
    }

    @Test
    fun `extensions resolve case insensitively across separators`() {
        assertEquals("kotlin", SyntaxLanguageResolver.fromPath("src/Main.KT"))
        assertEquals("tsx", SyntaxLanguageResolver.fromPath("web\\app.tsx"))
        assertEquals("typescript", SyntaxLanguageResolver.fromPath("web/app.ts"))
        assertNull(SyntaxLanguageResolver.fromPath("README"))
        assertNull(SyntaxLanguageResolver.fromPath(null))
    }
}
