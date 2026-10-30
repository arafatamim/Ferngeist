package com.tamimarafat.ferngeist.feature.chat

/** One-shot effects emitted to the UI layer (snackbar, navigation, etc.). */
sealed interface ChatEffect {
    data class ShowError(
        val message: String,
    ) : ChatEffect

    data class ShowMessage(
        val message: String,
    ) : ChatEffect

    data class SwitchSession(
        val session: SwitcherSession,
    ) : ChatEffect

    data object NavigateBack : ChatEffect
}
