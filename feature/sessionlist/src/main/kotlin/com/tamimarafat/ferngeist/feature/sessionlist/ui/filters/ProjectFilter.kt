package com.tamimarafat.ferngeist.feature.sessionlist.ui.filters

/** The project the list is narrowed to, and how to drop that filter. */
internal class ProjectFilter(
    val name: String,
    val onClear: () -> Unit,
)
