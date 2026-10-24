package com.tamimarafat.ferngeist.feature.chat.syntax

/**
 * Maps a markdown fence info string or a file path to a SyntaxMP language label.
 *
 * Unknown labels return null; the highlighter then leaves the text plain. Fence labels are not
 * validated here: aliases (`ts`, `kt`, `yml`) are resolved by the tokenizer, and an unknown word
 * costs one cache lookup rather than a parse.
 */
internal object SyntaxLanguageResolver {
    fun fromFence(infoString: String?): String? =
        infoString
            ?.trim()
            ?.substringBefore(' ')
            ?.substringBefore(',')
            ?.substringBefore(';')
            ?.lowercase()
            ?.trim('{', '}', '.')
            ?.takeIf { it.isNotEmpty() }

    fun fromPath(path: String?): String? {
        val name = path?.replace('\\', '/')?.substringAfterLast('/')?.lowercase() ?: return null
        return when {
            name == "dockerfile" || name.startsWith("dockerfile.") -> "dockerfile"
            name == "containerfile" || name.startsWith("containerfile.") -> "dockerfile"
            name == "makefile" -> "makefile"
            name == ".env" -> "dotenv"
            else -> eXTENSIONS[name.substringAfterLast('.', "")]
        }
    }
}

/** Canonical SyntaxMP language ids; the tokenizer also accepts the usual aliases. */
private val eXTENSIONS =
    mapOf(
        "kt" to "kotlin",
        "kts" to "kotlin",
        "java" to "java",
        "py" to "python",
        "pyi" to "python",
        "js" to "javascript",
        "mjs" to "javascript",
        "cjs" to "javascript",
        "jsx" to "jsx",
        "ts" to "typescript",
        "mts" to "typescript",
        "cts" to "typescript",
        "tsx" to "tsx",
        "json" to "json",
        "jsonc" to "json",
        "yaml" to "yaml",
        "yml" to "yaml",
        "toml" to "toml",
        "xml" to "xml",
        "xsl" to "xml",
        "xslt" to "xml",
        "svg" to "xml",
        "html" to "html",
        "htm" to "html",
        "css" to "css",
        // SyntaxMP has no SCSS/Sass/Less tokenizer; the CSS one is the closest approximation.
        "scss" to "css",
        "sass" to "css",
        "less" to "css",
        "sql" to "sql",
        "psql" to "postgresql",
        "sqlite" to "sqlite",
        "sh" to "shell",
        "bash" to "bash",
        "zsh" to "zsh",
        "ps1" to "powershell",
        "psm1" to "powershell",
        "psd1" to "powershell",
        "rs" to "rust",
        "go" to "go",
        "rb" to "ruby",
        "php" to "php",
        "dart" to "dart",
        "cs" to "csharp",
        "c" to "c",
        "h" to "c",
        "cpp" to "cpp",
        "cc" to "cpp",
        "cxx" to "cpp",
        "hpp" to "cpp",
        "hh" to "cpp",
        "hxx" to "cpp",
        // No Objective-C tokenizer; C++ is the closest available.
        "m" to "cpp",
        "mm" to "cpp",
        "swift" to "swift",
        "md" to "markdown",
        "markdown" to "markdown",
        "diff" to "diff",
        "patch" to "diff",
        "ini" to "ini",
        "cfg" to "ini",
        "properties" to "properties",
        "env" to "dotenv",
        "graphql" to "graphql",
        "gql" to "graphql",
        "proto" to "protobuf",
        "csv" to "csv",
    )
