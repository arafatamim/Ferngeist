package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TwoRowsTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.core.common.ui.ConnectionStatusPill
import com.tamimarafat.ferngeist.core.common.ui.sessionTitleSharedBounds

// region: ChatTopBar

/**
 * Wraps [TwoRowsTopAppBar] with a gradient surface-fade background.
 *
 * The background gradient goes from opaque surface (top) to transparent (bottom).
 * As [scrollBehavior.state.collapsedFraction] approaches 1.0, the base surface
 * fades out completely, leaving only the pill elements visible.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
internal fun ChatTopBar(
    sessionId: String,
    sessionTitle: String,
    activeModel: String?,
    connectionState: ChatConnectionState,
    totalTokens: Int?,
    contextWindowTokens: Int?,
    costAmount: Double?,
    costCurrency: String?,
    scrollBehavior: TopAppBarScrollBehavior,
    onNavigateBack: () -> Unit,
    onConnectionStatusClick: () -> Unit,
    onTitleClick: () -> Unit,
    isSearchActive: Boolean = false,
    searchQuery: String = "",
    searchMatchCount: Int = 0,
    currentMatchIndex: Int = 0,
    onToggleSearch: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onNextMatch: () -> Unit = {},
    onPreviousMatch: () -> Unit = {},
    onCloseSearch: () -> Unit = {},
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: androidx.compose.animation.AnimatedContentScope,
) {
    val collapsedFraction = scrollBehavior.state.collapsedFraction.coerceIn(0f, 1f)
    val expandedBackground = MaterialTheme.colorScheme.surface.copy(alpha = 1f - collapsedFraction)
    val topShadow = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    val middleShadow = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
    val bottomShadow = Color.Transparent
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            searchFocusRequester.requestFocus()
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(expandedBackground)
                .background(
                    brush =
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    topShadow,
                                    middleShadow,
                                    bottomShadow,
                                ),
                        ),
                ),
    ) {
        Column {
            TwoRowsTopAppBar(
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = onNavigateBack,
                        shape = RoundedCornerShape(percent = 50),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.chat_back_desc),
                        )
                    }
                },
                title = { expanded ->
                    ChatTopBarTitle(
                        expanded = expanded,
                        collapsedFraction = collapsedFraction,
                        sessionId = sessionId,
                        sessionTitle = sessionTitle,
                        onTitleClick = onTitleClick,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedContentScope = animatedContentScope,
                    )
                },
                subtitle =
                    activeModel?.takeIf { it.isNotBlank() }?.let { model ->
                        { expanded ->
                            if (expanded) {
                                Text(
                                    text = model,
                                    style =
                                        MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(bottom = 12.dp),
                                )
                            }
                        }
                    },
                actions = {
                    // Search toggle icon (shown when search is not active)
                    if (!isSearchActive) {
                        IconButton(onClick = onToggleSearch) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = stringResource(R.string.chat_search_toggle_desc),
                            )
                        }
                    }
                    ConnectionStatusPill(
                        connectionState = connectionState,
                        totalTokens = totalTokens,
                        contextWindowTokens = contextWindowTokens,
                        costAmount = costAmount,
                        costCurrency = costCurrency,
                        onClick = onConnectionStatusClick,
                    )
                },
                collapsedHeight = TopAppBarDefaults.LargeAppBarCollapsedHeight,
                expandedHeight =
                    if (activeModel.isNullOrBlank()) {
                        TopAppBarDefaults.MediumFlexibleAppBarWithoutSubtitleExpandedHeight + 24.dp
                    } else {
                        TopAppBarDefaults.MediumFlexibleAppBarWithSubtitleExpandedHeight + 24.dp
                    },
                scrollBehavior = scrollBehavior,
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
            )

            // Search bar — slides in with a spring when activated
            AnimatedVisibility(
                visible = isSearchActive,
                enter =
                    fadeIn(spring(stiffness = Spring.StiffnessMedium)) +
                        slideInVertically(spring(stiffness = Spring.StiffnessMedium)) { -it },
                exit =
                    fadeOut(spring(stiffness = Spring.StiffnessMedium)) +
                        slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { -it },
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier =
                            Modifier
                                .weight(1f)
                                .focusRequester(searchFocusRequester),
                        singleLine = true,
                        placeholder = {
                            Text(stringResource(R.string.chat_search_input_desc))
                        },
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions =
                            KeyboardActions(
                                onSearch = { /* handled by onValueChange */ },
                            ),
                    )

                    // Match counter
                    if (searchQuery.isNotBlank() && searchMatchCount > 0) {
                        Text(
                            text = "${currentMatchIndex + 1} / $searchMatchCount",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (searchQuery.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.chat_search_no_matches),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Previous match
                    IconButton(
                        onClick = onPreviousMatch,
                        enabled = searchMatchCount > 0,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.chat_search_prev_desc),
                        )
                    }

                    // Next match
                    IconButton(
                        onClick = onNextMatch,
                        enabled = searchMatchCount > 0,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.chat_search_next_desc),
                        )
                    }

                    // Close search
                    IconButton(
                        onClick = onCloseSearch,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.chat_search_close_desc),
                        )
                    }
                }
            }
        }
    }
}
// endregion

// region: ChatTopBarTitle

/**
 * Renders the session title in either expanded (plain large text) or collapsed
 * (Surface pill) form, depending on [expanded] and [collapsedFraction].
 *
 * The shared-bounds transition for the session title is assigned to whichever
 * visual form owns the majority of the crossfade — ownership flips at 50%.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun ChatTopBarTitle(
    expanded: Boolean,
    collapsedFraction: Float,
    sessionId: String,
    sessionTitle: String,
    onTitleClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: androidx.compose.animation.AnimatedContentScope,
) {
    // Both expanded and collapsed forms render, but only one owns the
    // shared-bounds transition at a time. Ownership flips at the 50% mark
    // to avoid both forms trying to drive the same shared element.
    val ownsSharedTitleBounds =
        if (expanded) {
            collapsedFraction < 0.5f
        } else {
            collapsedFraction >= 0.5f
        }

    if (expanded) {
        with(sharedTransitionScope) {
            Text(
                text = sessionTitle,
                style =
                    MaterialTheme.typography.titleLarge.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.MiddleEllipsis,
                modifier =
                    Modifier
                        .then(
                            if (ownsSharedTitleBounds) {
                                Modifier.sessionTitleSharedBounds(sessionId, sharedTransitionScope, animatedContentScope)
                            } else {
                                Modifier
                            },
                        ).clickable(onClick = onTitleClick)
                        .semantics {
                            contentDescription = sessionTitle
                        },
            )
        }
    } else {
        Surface(
            shape = RoundedCornerShape(percent = 50),
            tonalElevation = 0.dp,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClick = onTitleClick)
                    .semantics {
                        contentDescription = sessionTitle
                    },
        ) {
            with(sharedTransitionScope) {
                Text(
                    text = sessionTitle,
                    style =
                        MaterialTheme.typography.titleSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier =
                        Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .then(
                                if (ownsSharedTitleBounds) {
                                    Modifier.sessionTitleSharedBounds(sessionId, sharedTransitionScope, animatedContentScope)
                                } else {
                                    Modifier
                                },
                            ),
                )
            }
        }
    }
}
// endregion
