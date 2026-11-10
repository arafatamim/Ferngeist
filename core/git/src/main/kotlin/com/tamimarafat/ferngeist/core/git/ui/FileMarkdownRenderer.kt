package com.tamimarafat.ferngeist.core.git.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineDispatcher

/** The host supplies document rendering so Git never depends on the chat transcript. */
interface FileMarkdownRenderer {
    @Composable
    fun prepare(
        text: String?,
        truncated: Boolean,
        dispatcher: CoroutineDispatcher,
    ): FileMarkdownContent?
}

class FileMarkdownContent(
    val draw: @Composable (LazyListState) -> Unit,
)

val localFileMarkdownRenderer =
    staticCompositionLocalOf<FileMarkdownRenderer> {
        error("The application must provide a file markdown renderer")
    }
