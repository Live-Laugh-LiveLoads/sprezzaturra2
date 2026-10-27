package com.ideaforge.ui

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import com.ideaforge.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Holds the decisions [AppRoot] must make once per process: start destination and first-launch prompts. */
@HiltViewModel
class AppRootViewModel @Inject constructor(
    private val settings: SettingsRepository,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val prefs = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    /** Fixed when the ViewModel is created, so finishing onboarding does not rebuild the graph. */
    val startRoute: String = if (settings.current.onboardingDone) Routes.HOME else ONBOARDING_ROUTE

    /** True only the first time it is asked; the notification permission prompt is shown once. */
    fun consumeNotificationPrompt(): Boolean {
        if (prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)) return false
        prefs.edit { putBoolean(KEY_NOTIFICATION_ASKED, true) }
        return true
    }

    /** Safety net in case the onboarding screen finishes without persisting the flag itself. */
    fun markOnboardingDone() {
        if (!settings.current.onboardingDone) settings.update { it.copy(onboardingDone = true) }
    }

    companion object {
        const val ONBOARDING_ROUTE = "onboarding"
        private const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"
    }
}
