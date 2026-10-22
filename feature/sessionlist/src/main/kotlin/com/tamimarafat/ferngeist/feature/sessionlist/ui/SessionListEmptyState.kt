package com.tamimarafat.ferngeist.feature.sessionlist.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.ErrorStateCard
import com.tamimarafat.ferngeist.feature.sessionlist.R

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SessionListEmptyContent(
    padding: PaddingValues,
    supportsSessionList: Boolean,
    scopeName: String?,
    onCreateSession: () -> Unit,
) {
    EmptySessionList(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding),
        supportsSessionList = supportsSessionList,
        scopeName = scopeName,
        onCreateSession = onCreateSession,
    )
}

/**
 * Shown when there are no sessions.
 * Text adapts based on whether the agent supports session listing.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptySessionList(
    modifier: Modifier = Modifier,
    supportsSessionList: Boolean,
    scopeName: String? = null,
    onCreateSession: () -> Unit,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        ErrorStateCard(
            headline =
                if (scopeName != null) {
                    stringResource(R.string.sessionlist_scope_empty, scopeName)
                } else {
                    stringResource(R.string.sessionlist_empty_title)
                },
            body =
                if (supportsSessionList) {
                    stringResource(
                        R.string.sessionlist_empty_subtitle_listed,
                    )
                } else {
                    stringResource(
                        R.string.sessionlist_empty_subtitle_offline,
                    )
                },
            icon = Icons.Rounded.Forum,
            medallionContainer = MaterialTheme.colorScheme.primaryContainer,
            medallionContent = MaterialTheme.colorScheme.onPrimaryContainer,
            medallionShape = MaterialShapes.Cookie9Sided.toShape(),
            ctaLabel = stringResource(R.string.sessionlist_empty_action),
            onCta = onCreateSession,
            ctaIsHero = true,
            modifier = Modifier.padding(24.dp),
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Empty session list", showBackground = true)
@Composable
private fun EmptySessionListPreview() {
    EmptySessionList(
        supportsSessionList = true,
        onCreateSession = {},
    )
}
