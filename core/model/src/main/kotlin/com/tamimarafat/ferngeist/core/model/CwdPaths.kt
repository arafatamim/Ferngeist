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
