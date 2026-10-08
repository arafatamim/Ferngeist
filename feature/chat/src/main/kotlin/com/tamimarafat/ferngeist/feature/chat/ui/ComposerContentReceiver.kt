package com.tamimarafat.ferngeist.feature.chat.ui

import android.content.ClipData
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri

/**
 * Whether content of [mimeType] can become an attachment on this session. Mirrors the branch
 * `processPickedUris` takes, so paste and drop accept exactly what the document picker would: an
 * image needs image support, but a session that takes files still gets it as a file.
 */
internal fun acceptsAttachmentMimeType(
    mimeType: String?,
    canSendImages: Boolean,
    canSendFiles: Boolean,
): Boolean {
    val isImage = mimeType?.startsWith("image/") == true
    return (isImage && canSendImages) || canSendFiles
}

/**
 * Takes content URIs out of pasted or dropped content and hands them to [onUris] as attachments,
 * reports whether a drag is currently over this node through [onDragHoverChange], and reports
 * content that carried nothing attachable through [onNothingToAttach].
 *
 * Applied to the chat pane rather than to the text field. That widens the drop area to the whole
 * conversation, and — because a text field looks up the receiver on its ancestors — it also routes
 * paste (Ctrl+V and the selection toolbar) through here. Only the items we accept are consumed, so
 * text travelling in the same clip still reaches the field and is inserted normally.
 *
 * The media type of each item has to be known before it can be accepted, and the receiver contract
 * decides that synchronously, so this classifies with [android.content.ContentResolver.getType].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.composerContentReceiver(
    canSendImages: Boolean,
    canSendFiles: Boolean,
    onUris: (List<Uri>) -> Unit,
    onDragHoverChange: (Boolean) -> Unit,
    onNothingToAttach: () -> Unit,
): Modifier {
    val contentResolver = LocalContext.current.contentResolver
    // Depth, not a flag: the text field inside this node is a drop target of its own and hands its
    // drag events to this listener, so moving onto the field reports an enter (from the field)
    // immediately followed by an exit (from this node, which no longer owns the pointer). The
    // pair nets out to still-hovering, which a flag cannot express.
    val hoverDepth = remember { mutableIntStateOf(0) }
    // The caller's callbacks change identity on most recompositions (a streaming delta recomposes
    // the whole screen), so they are read through the latest-value holders and the listener itself
    // is keyed only on the capabilities. Keying it on the lambdas would rebuild the listener — and
    // re-point the content-receiver node at it — on every recomposition.
    val currentOnUris by rememberUpdatedState(onUris)
    val currentOnDragHoverChange by rememberUpdatedState(onDragHoverChange)
    val currentOnNothingToAttach by rememberUpdatedState(onNothingToAttach)
    val listener =
        remember(contentResolver, canSendImages, canSendFiles) {
            object : ReceiveContentListener {
                override fun onDragEnter() {
                    hoverDepth.intValue++
                    currentOnDragHoverChange(true)
                }

                override fun onDragExit() {
                    hoverDepth.intValue = (hoverDepth.intValue - 1).coerceAtLeast(0)
                    currentOnDragHoverChange(hoverDepth.intValue > 0)
                }

                override fun onDragEnd() {
                    hoverDepth.intValue = 0
                    currentOnDragHoverChange(false)
                }

                override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                    val clipData = transferableContent.clipEntry.clipData
                    val uris = clipData.itemUris().ifEmpty { listOfNotNull(clipData.loneContentUriText()) }
                    val accepted =
                        uris.filter { uri ->
                            acceptsAttachmentMimeType(
                                mimeType = contentResolver.getType(uri),
                                canSendImages = canSendImages,
                                canSendFiles = canSendFiles,
                            )
                        }
                    if (accepted.isEmpty()) {
                        // Nothing for us. A clip with no text either leaves the paste a no-op,
                        // which without a word from us is indistinguishable from a broken paste.
                        if (!clipData.hasPlainText()) currentOnNothingToAttach()
                        return transferableContent
                    }

                    val acceptedText = accepted.mapTo(mutableSetOf()) { it.toString() }
                    currentOnUris(accepted)
                    return transferableContent.consume { item ->
                        val uri = item.contentUri()
                        if (uri != null) uri in accepted else item.text?.toString()?.trim() in acceptedText
                    }
                }
            }
        }
    return contentReceiver(listener)
}

/**
 * The URI an item carries as a URI: its own, or the data of an intent item — the platform grants
 * read access to both when a clip is read (see `ClipboardService.grantItemPermission`).
 */
private fun ClipData.Item.contentUri(): Uri? =
    uri ?: intent?.data?.takeIf { it.scheme == android.content.ContentResolver.SCHEME_CONTENT }

private fun ClipData.itemUris(): List<Uri> = (0 until itemCount).mapNotNull { getItemAt(it).contentUri() }

/**
 * A clip whose only content is a `content://` URI pasted as text: some apps copy a reference that
 * way rather than as a URI item. Deliberately narrow — one item, nothing but the URI — so an
 * ordinary text paste that happens to mention a URI is still just text.
 */
private fun ClipData.loneContentUriText(): Uri? {
    if (itemCount != 1) return null
    val text = getItemAt(0).text?.toString()?.trim() ?: return null
    if (!text.startsWith("content://") || text.any { it.isWhitespace() }) return null
    return text.toUri()
}

/** Whether the field would insert something, i.e. whether the paste is anything but a no-op. */
private fun ClipData.hasPlainText(): Boolean =
    (0 until itemCount).any { getItemAt(it).text?.isNotEmpty() == true || getItemAt(it).htmlText != null }
