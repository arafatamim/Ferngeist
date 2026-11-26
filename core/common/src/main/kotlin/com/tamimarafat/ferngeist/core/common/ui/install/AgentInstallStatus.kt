package com.tamimarafat.ferngeist.core.common.ui.install

import android.text.format.Formatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.R
import com.tamimarafat.ferngeist.core.model.AgentInstallProgress

@Composable
fun AgentInstallStatus(progress: AgentInstallProgress) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AgentInstallIndicator(progress, Modifier.size(48.dp))
    }
}

@Composable
fun AgentInstallIndicator(
    progress: AgentInstallProgress,
    modifier: Modifier = Modifier,
) {
    val fraction = progress.bytesTotal?.let { (progress.bytesReceived.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    val description =
        if (fraction == null) {
            stringResource(
                R.string.common_installing_agent_bytes,
                Formatter.formatShortFileSize(LocalContext.current, progress.bytesReceived),
            )
        } else {
            stringResource(R.string.common_installing_agent)
        }
    Box(
        modifier.semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (fraction == null) {
            CircularProgressIndicator(modifier = Modifier.fillMaxSize(), strokeWidth = 2.dp)
        } else {
            CircularProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxSize(), strokeWidth = 2.dp)
        }
        Icon(
            imageVector = Icons.Rounded.Download,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 320)
@Composable
private fun AgentInstallStatusPreview() {
    MaterialTheme {
        AgentInstallStatus(AgentInstallProgress(bytesReceived = 24_000_000, bytesTotal = 40_000_000))
    }
}
