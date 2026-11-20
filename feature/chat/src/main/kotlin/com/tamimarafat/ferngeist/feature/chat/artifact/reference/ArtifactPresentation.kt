package com.tamimarafat.ferngeist.feature.chat.artifact.reference

import com.tamimarafat.ferngeist.core.model.artifact.ChatArtifactStat
import com.tamimarafat.ferngeist.feature.chat.markdown.image.MAX_IMAGE_BYTES

/** What a referenced file is, by its name alone. */
internal enum class ArtifactKind { TEXT, IMAGE, VIDEO, AUDIO, HTML, FILE }

/** Where a referenced file shows up beside its paragraph. */
internal enum class ArtifactPresentation {
    /** Workspace text: the existing inline chip and file sheet, no attachment. */
    SOURCE_CHIP,

    /** A workspace Markdown image, which the paragraph already draws in place. */
    INLINE_IMAGE,

    /** A square attachment card below the paragraph. */
    CARD,
}

private val htmlExtensions = setOf("html", "htm", "xhtml")
private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "avif", "heic", "heif", "ico")
private val videoExtensions = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp")
private val audioExtensions = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac")

// shortcut: an unlisted binary type reads as text and its chip opens the sheet's binary notice; extend on report.
private val binaryExtensions =
    "pdf zip gz tgz tar 7z xz bz2 rar docx xlsx pptx epub apk aab aar jar so exe dmg iso ttf otf woff woff2 db sqlite"
        .split(' ')
        .toSet()

/**
 * Extensions a bare name with no workspace or turn evidence may carry and still count as a file.
 * Anything else (`example.com`, `e.g.`, `ASP.NET`) stays prose.
 */
internal val bareArtifactExtensions =
    htmlExtensions + imageExtensions + videoExtensions + audioExtensions + binaryExtensions

// A dotfile's leading dot starts its name, not an extension: `.gitignore` has none.
internal fun extensionOf(path: String): String =
    path
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .drop(1)
        .substringAfterLast('.', "")
        .lowercase()

/** Anything not HTML, media or a known binary is text, which the file sheet shows highlighted or plain. */
internal fun artifactKind(path: String): ArtifactKind =
    when (extensionOf(path)) {
        in htmlExtensions -> ArtifactKind.HTML
        in imageExtensions -> ArtifactKind.IMAGE
        in videoExtensions -> ArtifactKind.VIDEO
        in audioExtensions -> ArtifactKind.AUDIO
        in binaryExtensions -> ArtifactKind.FILE
        else -> ArtifactKind.TEXT
    }

/** Only workspace text and workspace Markdown images escape the card; HTML is a card everywhere. */
internal fun presentationOf(
    kind: ArtifactKind,
    inWorkspace: Boolean,
    source: ReferenceSource,
): ArtifactPresentation =
    when {
        !inWorkspace -> ArtifactPresentation.CARD
        kind == ArtifactKind.TEXT -> ArtifactPresentation.SOURCE_CHIP
        kind == ArtifactKind.IMAGE && source == ReferenceSource.IMAGE -> ArtifactPresentation.INLINE_IMAGE
        else -> ArtifactPresentation.CARD
    }

/**
 * Whether [path]'s card fetches the file to show it before a tap: an image the host reports
 * readable and within the transcript image cap. An unknown size never fetches, since the read
 * pulls the whole file.
 */
internal fun showsPreview(
    path: String,
    stat: ChatArtifactStat?,
): Boolean =
    artifactKind(path) == ArtifactKind.IMAGE &&
        stat?.error == null &&
        (stat?.size ?: Long.MAX_VALUE) <= MAX_IMAGE_BYTES
