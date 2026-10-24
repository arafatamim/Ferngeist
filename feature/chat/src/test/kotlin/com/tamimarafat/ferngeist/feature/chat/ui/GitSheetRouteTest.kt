package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitSheetRouteTest {
    @Test
    fun everyRoute_survivesSaveAndRestore() {
        val routes =
            listOf(
                GitSheetRoute.Changes,
                GitSheetRoute.History,
                GitSheetRoute.Commit("abc"),
                GitSheetRoute.FileDiff("b.kt", null, null),
                GitSheetRoute.FileDiff("b.kt", "abc", "a.kt"),
            )
        val scope = SaverScope { true }

        routes.forEach { route ->
            val saved = with(gitSheetRouteSaver) { scope.save(route) }!!
            assertEquals(route, gitSheetRouteSaver.restore(saved))
        }
    }

    @Test
    fun back_stepsFromAFileToItsCommitOrTheChangesTab_andFromACommitToHistory() {
        assertEquals(GitSheetRoute.Commit("abc"), GitSheetRoute.FileDiff("b.kt", "abc", null).parent)
        assertEquals(GitSheetRoute.Changes, GitSheetRoute.FileDiff("b.kt", null, null).parent)
        assertEquals(GitSheetRoute.History, GitSheetRoute.Commit("abc").parent)
        assertNull(GitSheetRoute.History.parent)
    }
}
