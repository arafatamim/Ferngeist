package com.tamimarafat.ferngeist.feature.chat.artifact.reference

import com.tamimarafat.ferngeist.core.git.syntax.SyntaxLanguageResolver

/** What a referenced file is, by its name alone. */
internal enum class ArtifactKind { SOURCE, IMAGE, VIDEO, AUDIO, HTML, FILE }

/** Where a referenced file shows up beside its paragraph. */
internal enum class ArtifactPresentation {
    /** Workspace source text: the existing inline chip and file sheet, no attachment. */
    SOURCE_CHIP,

    /** Workspace image, video or audio, played in place. */
    INLINE_MEDIA,

    /** A square attachment card below the paragraph. */
    CARD,
}

private val htmlExtensions = setOf("html", "htm", "xhtml")
private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "avif", "heic", "heif", "ico")
private val videoExtensions = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp")
private val audioExtensions = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac")

/**
 * Extensions a bare name with no workspace or turn evidence may carry and still count as a file.
 * Anything else (`example.com`, `e.g.`, `ASP.NET`) stays prose.
 */
internal val bareArtifactExtensions =
    htmlExtensions + imageExtensions + videoExtensions + audioExtensions +
        setOf("pdf", "txt", "log", "zip", "gz", "tgz", "tar", "7z", "docx", "xlsx", "pptx", "epub", "ipynb", "apk")

internal fun extensionOf(path: String): String =
    path
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .substringAfterLast('.', "")
        .lowercase()

/** HTML and media win over the source tokenizer, which also knows `.html` and `.svg`. */
internal fun artifactKind(path: String): ArtifactKind =
    when (extensionOf(path)) {
        in htmlExtensions -> ArtifactKind.HTML
        in imageExtensions -> ArtifactKind.IMAGE
        in videoExtensions -> ArtifactKind.VIDEO
        in audioExtensions -> ArtifactKind.AUDIO
        else -> if (SyntaxLanguageResolver.fromPath(path) != null) ArtifactKind.SOURCE else ArtifactKind.FILE
    }

/** Only workspace source and media escape the card; HTML is a card everywhere. */
internal fun presentationOf(
    kind: ArtifactKind,
    inWorkspace: Boolean,
): ArtifactPresentation =
    when {
        !inWorkspace || kind == ArtifactKind.HTML || kind == ArtifactKind.FILE -> ArtifactPresentation.CARD
        kind == ArtifactKind.SOURCE -> ArtifactPresentation.SOURCE_CHIP
        else -> ArtifactPresentation.INLINE_MEDIA
    }
