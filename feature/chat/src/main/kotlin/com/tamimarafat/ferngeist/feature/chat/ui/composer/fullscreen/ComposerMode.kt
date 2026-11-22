package com.tamimarafat.ferngeist.feature.chat.ui.composer.fullscreen

internal enum class ComposerMode {
    COLLAPSED,
    EXPANDED,
    FULLSCREEN,
    ;

    val isExpanded: Boolean get() = this != COLLAPSED
}
