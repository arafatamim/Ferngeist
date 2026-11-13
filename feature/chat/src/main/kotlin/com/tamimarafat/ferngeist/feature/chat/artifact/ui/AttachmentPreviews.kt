package com.tamimarafat.ferngeist.feature.chat.artifact.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adamglin.compose.markdown.core.api.MarkdownEngine
import com.adamglin.compose.markdown.core.dialect.MarkdownDialect
import com.tamimarafat.ferngeist.core.model.artifact.ChatArtifactStat
import com.tamimarafat.ferngeist.feature.chat.MarkdownRenderedDocument
import com.tamimarafat.ferngeist.feature.chat.ui.RenderedMarkdown
import com.tamimarafat.ferngeist.feature.chat.ui.localWorkspaceRoot

private val previewHost =
    ArtifactHost(
        open = {},
        download = { _, _ -> },
        stat = { paths -> paths.map { ChatArtifactStat(size = 3_400_000L, error = null) } },
    )

private const val TRANSCRIPT =
    "I recorded the full-history run as " +
        "[Recorded test](/tmp/ferngeist-elicitation-probe/live-full-history-test.mp4) " +
        "and saved the logs to /tmp/ferngeist-elicitation-probe/session.log.\n\n" +
        "The coverage report is at [report/index.html](report/index.html), and the fix itself lives in " +
        "src/main/kotlin/App.kt. The summary went to /home/u/Downloads/Quarterly results 2026.pdf " +
        "and \"/home/u/Downloads/Quarterly results 2026.pdf\" is also attached."

@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    val dark = LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface {
            CompositionLocalProvider(
                localArtifactHost provides previewHost,
                localWorkspaceRoot provides "/home/u/app",
                content = content,
            )
        }
    }
}

@Composable
private fun TranscriptExcerpt() {
    val blocks =
        MarkdownEngine(MarkdownDialect.GfmCompat)
            .append(TRANSCRIPT)
            .snapshot.document.blocks
    RenderedMarkdown(TRANSCRIPT, MarkdownRenderedDocument(blocks), Modifier.padding(16.dp))
}

@Preview(name = "Transcript", widthDp = 400)
@Composable
internal fun AttachmentTranscriptPreview() = PreviewSurface { TranscriptExcerpt() }

@Preview(name = "Transcript, dark", widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun AttachmentTranscriptDarkPreview() = PreviewSurface { TranscriptExcerpt() }

@Preview(name = "Narrow column", widthDp = 120)
@Composable
internal fun AttachmentNarrowPreview() =
    PreviewSurface {
        val markdown = "Saved /tmp/out/a-very-long-recording-name-from-the-agent.mp4"
        val blocks =
            MarkdownEngine(MarkdownDialect.GfmCompat)
                .append(markdown)
                .snapshot.document.blocks
        RenderedMarkdown(markdown, MarkdownRenderedDocument(blocks), Modifier.padding(8.dp))
    }

@Preview(name = "Card states", widthDp = 440)
@Composable
internal fun AttachmentCardStatesPreview() =
    PreviewSurface {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val card = Modifier.size(128.dp)
            AttachmentCard("/tmp/clip.mp4", "clip.mp4", "12 MB", loading = false, failed = false, {}, {}, card)
            AttachmentCard("/tmp/report.pdf", "report.pdf", "840 kB", loading = true, failed = false, {}, {}, card)
            AttachmentCard("/tmp/gone.zip", "gone.zip", null, loading = false, failed = true, {}, {}, card)
        }
    }
