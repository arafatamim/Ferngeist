package com.tamimarafat.ferngeist.onboarding

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnboardingPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val isCompletedFlow = MutableStateFlow(sharedPreferences.getBoolean(KEY_COMPLETED, false))

    val isCompleted: StateFlow<Boolean> = isCompletedFlow.asStateFlow()

    fun markCompleted() {
        if (isCompletedFlow.value) return
        sharedPreferences.edit().putBoolean(KEY_COMPLETED, true).apply()
        isCompletedFlow.value = true
    }

    companion object {
        private const val PREFS_NAME = "ferngeist_onboarding"
        private const val KEY_COMPLETED = "completed"
    }
}
