package com.tamimarafat.ferngeist.core.model

/** [cwd] without surrounding whitespace or trailing separators. */
fun normalizeCwd(cwd: String): String = cwd.trim().trimEnd('\\', '/')

/** True when [a] and [b] name the same directory, whatever their separators or case. */
fun isSameCwd(
    a: String,
    b: String,
): Boolean = cwdKey(a) == cwdKey(b)

/** [cwd] reduced to what [isSameCwd] compares, for keying lookups by directory. */
fun cwdKey(cwd: String): String = normalizeCwd(cwd.replace('\\', '/')).lowercase()

/** The folder names of [path], split on either separator; empty for a blank path or a bare root. */
fun pathSegments(path: String): List<String> = path.split('/', '\\').filter { it.isNotBlank() }

/** [path] as `home › tamim › work`, or [path] itself when it has no folder names. */
fun pathBreadcrumb(
    path: String,
    separator: String = " › ",
): String = pathSegments(path).joinToString(separator).ifEmpty { path.trim() }
