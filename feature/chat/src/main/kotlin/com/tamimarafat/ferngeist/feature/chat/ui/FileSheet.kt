@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.dismissSheet
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.localGitSemanticColors
import com.tamimarafat.ferngeist.core.common.ui.localSyntaxColors
import com.tamimarafat.ferngeist.core.common.ui.onEscapeKey
import com.tamimarafat.ferngeist.core.common.ui.rememberEscapeFocusAnchor
import com.tamimarafat.ferngeist.feature.chat.FileView
import com.tamimarafat.ferngeist.feature.chat.IncrementalMarkdown
import com.tamimarafat.ferngeist.feature.chat.MarkdownRenderedDocument
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.markdown.MarkdownBlocksLazy
import com.tamimarafat.ferngeist.feature.chat.markdown.documentMarkdownTypography
import com.tamimarafat.ferngeist.feature.chat.syntax.SyntaxHighlighting
import com.tamimarafat.ferngeist.feature.chat.syntax.SyntaxLanguageResolver
import com.tamimarafat.ferngeist.feature.chat.ui.diff.diffLines
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A whole file a transcript chip opened, as it is in the working tree. Lines git counts as added
 * or changed since `HEAD` carry a green gutter bar; a red tick sits where lines were deleted.
 * The header's wrap toggle trades horizontal scrolling for wrapping long lines; a markdown file
 * adds a second toggle between its source and the rendered document.
 */
@Composable
internal fun FileSheet(
    file: FileView,
    softWrap: Boolean,
    onSoftWrapChange: (Boolean) -> Unit,
    markdownPreview: Boolean,
    onMarkdownPreviewChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    // Expanded only: the file lands after the sheet opens, as the tool call sheet's diff does.
    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
    val scope = rememberCoroutineScope()
    val escapeFocus = rememberEscapeFocusAnchor()
    val isMarkdown = remember(file.path) { SyntaxLanguageResolver.fromPath(file.path) == "markdown" }
    // Both projections stay alive while the sheet is open. Toggling is then a branch change, not
    // a re-tokenize and re-parse behind a spinner: the latter is what made the switch lurch.
    val lines = remember(file.text) { file.text.orEmpty().diffLines() }
    val language = remember(file.path) { SyntaxLanguageResolver.fromPath(file.path) }
    val syntaxColors = localSyntaxColors.current
    val annotatedLines by produceState<List<AnnotatedString>?>(null, file.text, language, syntaxColors) {
        value = SyntaxHighlighting.highlightLines(file.text.orEmpty(), language, syntaxColors)
    }
    val markdownDocument by produceState<MarkdownRenderedDocument?>(null, file.text, isMarkdown) {
        val text = file.text
        if (!isMarkdown || text.isNullOrEmpty()) return@produceState
        value =
            withContext(parseDispatcher) {
                IncrementalMarkdown().apply { update(text) }.document
            }
    }
    // One state per mode, so switching back lands where the reader left off.
    val rawScroll = remember(file.path) { LazyListState() }
    val previewScroll = remember(file.path) { LazyListState() }
    val horizontalScroll = remember(file.path) { ScrollState(0) }
    val previewRequested = isMarkdown && markdownPreview
    val showingRendered = previewRequested && markdownDocument != null
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // The body's height differs between source and rendered, and wrapping reflows the
        // source; let the sheet follow instead of jumping.
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .animateContentSize(
                        animationSpec = tween(durationMillis = MODE_CROSSFADE_MS, easing = FastOutSlowInEasing),
                    ).padding(horizontal = 24.dp, vertical = 12.dp)
                    .focusRequester(escapeFocus)
                    .focusTarget()
                    .onEscapeKey { scope.dismissSheet(sheetState, onDismiss) },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FileTitle(
                path = file.path,
                origin = fileOriginLabel(file.commit, file.compare),
                modifier = Modifier.fillMaxWidth(),
                filled = false,
                actions = {
                    FileModeButtonGroup(
                        softWrap = softWrap,
                        onSoftWrapChange = onSoftWrapChange,
                        wrapEnabled = !previewRequested,
                        isMarkdown = isMarkdown,
                        markdownPreview = markdownPreview,
                        onMarkdownPreviewChange = onMarkdownPreviewChange,
                    )
                },
            )
            FileSheetBody(
                file = file,
                softWrap = softWrap,
                document = markdownDocument,
                previewRequested = previewRequested,
                showRendered = showingRendered,
                lines = lines,
                annotatedLines = annotatedLines,
                rawScroll = rawScroll,
                previewScroll = previewScroll,
                horizontalScroll = horizontalScroll,
            )
        }
    }
}

/** The revision the sheet is showing, so its content is never read as the wrong one. */
@Composable
private fun fileOriginLabel(
    commit: String?,
    compare: String?,
): String =
    when {
        commit != null -> stringResource(R.string.chat_file_origin_commit, commit.take(SHORT_SHA))
        compare != null -> stringResource(R.string.chat_file_origin_compare, compare)
        else -> stringResource(R.string.chat_file_origin_working_tree)
    }

/** The files a name several share could mean; tapping one opens it. */
@Composable
internal fun FilePickerSheet(
    candidates: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
    val scope = rememberCoroutineScope()
    val escapeFocus = rememberEscapeFocusAnchor()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(escapeFocus)
                    .focusTarget()
                    .onEscapeKey { scope.dismissSheet(sheetState, onDismiss) },
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            items(candidates) { path ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .handCursor()
                            .clickable { onPick(path) }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    FileTitle(path, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * A file's name over its directory, badged with the revision it was read at. The name gets its
 * own row so it can use the full width and wrap; everything else shares the row below.
 */
@Composable
private fun FileTitle(
    path: String,
    modifier: Modifier = Modifier,
    origin: String? = null,
    filled: Boolean = true,
    actions: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (filled) {
            // Picker rows keep the chip; the sheet's own title is plain text.
            FileChip(
                path,
                textStyle = MaterialTheme.typography.bodyMedium,
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Clip,
            )
        } else {
            // No ellipsis here: a truncated file name is a worse answer than a taller header.
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileTypeIcon(path, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = path.fileName(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip,
                )
            }
        }
        val directory = path.replace('\\', '/').substringBeforeLast('/', "")
        if (directory.isNotEmpty()) {
            Text(
                text = directory,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (origin != null || actions != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                origin?.let { FileOriginBadge(it) }
                Spacer(Modifier.weight(1f))
                actions?.invoke()
            }
        }
    }
}

@Composable
private fun FileOriginBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * The reading controls as one connected expressive button group. The wrap toggle is disabled
 * while the rendered document is showing: it only changes the source view.
 */
@Composable
private fun FileModeButtonGroup(
    softWrap: Boolean,
    onSoftWrapChange: (Boolean) -> Unit,
    wrapEnabled: Boolean,
    isMarkdown: Boolean,
    markdownPreview: Boolean,
    onMarkdownPreviewChange: (Boolean) -> Unit,
) {
    val wrapInteraction = remember { MutableInteractionSource() }
    val previewInteraction = remember { MutableInteractionSource() }
    ButtonGroup(
        overflowIndicator = { menuState ->
            ButtonGroupDefaults.OverflowIndicator(menuState = menuState)
        },
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        customItem(
            buttonGroupContent = {
                val contentPadding = ButtonDefaults.ButtonWithIconContentPadding
                val layoutDirection = LocalLayoutDirection.current
                ToggleButton(
                    checked = softWrap,
                    onCheckedChange = onSoftWrapChange,
                    enabled = wrapEnabled,
                    shapes =
                        if (isMarkdown) {
                            ButtonGroupDefaults.connectedLeadingButtonShapes()
                        } else {
                            ToggleButtonDefaults.shapesFor(ToggleButtonDefaults.size)
                        },
                    contentPadding = contentPadding,
                    interactionSource = wrapInteraction,
                    modifier =
                        Modifier
                            .handCursor()
                            .animateWidth(
                                interactionSource = wrapInteraction,
                                compressionLimit = contentPadding.calculateEndPadding(layoutDirection),
                            ),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.WrapText, contentDescription = null)
                    Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                    Text(
                        text = stringResource(R.string.chat_file_soft_wrap),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                    )
                }
            },
            menuContent = { state ->
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.WrapText, contentDescription = null) },
                    text = { Text(stringResource(R.string.chat_file_soft_wrap)) },
                    enabled = wrapEnabled,
                    onClick = {
                        onSoftWrapChange(!softWrap)
                        state.dismiss()
                    },
                )
            },
        )
        if (isMarkdown) {
            customItem(
                buttonGroupContent = {
                    val contentPadding = ButtonDefaults.ButtonWithIconContentPadding
                    val layoutDirection = LocalLayoutDirection.current
                    ToggleButton(
                        checked = markdownPreview,
                        onCheckedChange = onMarkdownPreviewChange,
                        shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                        contentPadding = contentPadding,
                        interactionSource = previewInteraction,
                        modifier =
                            Modifier
                                .handCursor()
                                .animateWidth(
                                    interactionSource = previewInteraction,
                                    compressionLimit = contentPadding.calculateEndPadding(layoutDirection),
                                ),
                    ) {
                        Icon(Icons.Rounded.Visibility, contentDescription = null)
                        Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                        Text(
                            text = stringResource(R.string.chat_file_markdown_preview),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible,
                        )
                    }
                },
                menuContent = { state ->
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Rounded.Visibility, contentDescription = null) },
                        text = { Text(stringResource(R.string.chat_file_markdown_preview)) },
                        onClick = {
                            onMarkdownPreviewChange(!markdownPreview)
                            state.dismiss()
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun FileSheetBody(
    file: FileView,
    softWrap: Boolean,
    document: MarkdownRenderedDocument?,
    previewRequested: Boolean,
    showRendered: Boolean,
    lines: List<String>,
    annotatedLines: List<AnnotatedString>?,
    rawScroll: LazyListState,
    previewScroll: LazyListState,
    horizontalScroll: ScrollState,
) {
    val notice =
        when {
            file.error != null -> file.error
            file.deleted -> stringResource(R.string.chat_file_deleted)
            file.binary -> stringResource(R.string.chat_file_binary)
            file.text?.isEmpty() == true -> stringResource(R.string.chat_file_empty)
            else -> null
        }
    when {
        file.isLoading -> FileBodyLoading()
        notice != null ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (file.error !=
                        null
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        previewRequested && document == null -> FileBodyLoading()
        else ->
            Crossfade(
                targetState = showRendered,
                animationSpec = tween(durationMillis = MODE_CROSSFADE_MS, easing = FastOutSlowInEasing),
                label = "file-mode",
            ) { rendered ->
                if (rendered) {
                    if (document != null) {
                        MarkdownFileBody(document, file.truncated, previewScroll)
                    } else {
                        FileBodyLoading()
                    }
                } else {
                    FileLines(file, lines, annotatedLines, softWrap, rawScroll, horizontalScroll)
                }
            }
    }
}

/** Fills the sheet, so neither the file arriving nor a mode switch resizes it. */
@Composable
private fun FileBodyLoading() {
    Box(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        CircularWavyProgressIndicator(modifier = Modifier.size(48.dp))
    }
}

/**
 * The file parsed as markdown, in a lazy list so a long README does not compose every block.
 * The document is parsed while the sheet opens, so switching modes only swaps which list draws.
 */
@Composable
private fun MarkdownFileBody(
    document: MarkdownRenderedDocument,
    truncated: Boolean,
    listState: LazyListState,
) {
    if (document.blocks.isEmpty()) {
        Text(
            text = stringResource(R.string.chat_file_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val footer: (@Composable () -> Unit)? =
            if (truncated) {
                {
                    Text(
                        text = stringResource(R.string.chat_file_truncated),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                null
            }
        MarkdownBlocksLazy(
            blocks = document.blocks,
            typography = documentMarkdownTypography(),
            state = listState,
            footer = footer,
        )
    }
}

@Composable
private fun FileLines(
    file: FileView,
    lines: List<String>,
    annotatedLines: List<AnnotatedString>?,
    softWrap: Boolean,
    listState: LazyListState,
    horizontalScroll: ScrollState,
) {
    val colors = localGitSemanticColors.current
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val numberColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    // Measured, not guessed: the widest number at this style and font scale, so none wraps.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val numberWidth =
        remember(lines.size, style, density) {
            val widest = "9".repeat(lines.size.toString().length)
            with(density) {
                measurer
                    .measure(widest, style, maxLines = 1)
                    .size.width
                    .toDp()
            }
        }
    SelectionContainer {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // Wrapped lines need the sheet's width; unwrapped ones need room to run.
                    .then(if (softWrap) Modifier else Modifier.horizontalScroll(horizontalScroll)),
            state = listState,
        ) {
            itemsIndexed(lines) { index, line ->
                // Intrinsic height, so the gutter bar spans the line it marks.
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    Gutter(
                        changed = index in file.changedLines,
                        deletedAbove = index in file.deletedAbove,
                        // Lines deleted at the end sit below the last line.
                        deletedBelow = index == lines.lastIndex && lines.size in file.deletedAbove,
                        added = colors.added,
                        deleted = colors.deleted,
                    )
                    // The gutter is a reading aid, not code: copying a selection must not
                    // pick up the line numbers.
                    DisableSelection {
                        Text(
                            text = "${index + 1}",
                            style = style,
                            color = numberColor,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(end = 8.dp).width(numberWidth),
                        )
                    }
                    Text(
                        text = annotatedLines?.getOrNull(index) ?: AnnotatedString(line),
                        style = style,
                        softWrap = softWrap,
                        // Weighted only when wrapping: it both fixes the wrapped height the
                        // intrinsic gutter measures and would cap the line if scrolling instead.
                        modifier = if (softWrap) Modifier.weight(1f) else Modifier,
                    )
                }
            }
            if (file.truncated) {
                item {
                    Text(
                        text = stringResource(R.string.chat_file_truncated),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Gutter(
    changed: Boolean,
    deletedAbove: Boolean,
    deletedBelow: Boolean,
    added: Color,
    deleted: Color,
) {
    Box(
        modifier =
            Modifier
                .width(gutterWidth)
                .fillMaxHeight()
                .padding(end = 4.dp)
                .background(if (changed) added else Color.Transparent)
                .drawBehind {
                    val tick = 2.dp.toPx()
                    // Longer than the bar is wide, so a deletion reads apart from a change.
                    val length = deletionTickLength.toPx()
                    if (deletedAbove) drawLine(deleted, Offset(0f, 0f), Offset(length, 0f), tick)
                    if (deletedBelow) drawLine(deleted, Offset(0f, size.height), Offset(length, size.height), tick)
                },
    )
}

private val gutterWidth = 7.dp
private val deletionTickLength = 9.dp
private const val SHORT_SHA = 7

// The mode change is a fade, not a slide: two long documents moving past each other reads as a
// flicker.
private const val MODE_CROSSFADE_MS = 200
