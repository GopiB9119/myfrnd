package com.wellness.overlay3d.util

/**
 * Decides what the "Manage notifications" button does on Android 13+, where notifications are a runtime permission.
 *
 * Android only reports a permission rationale after a previous denial, so a first-ever tap must be
 * recognised separately or the system prompt is never shown and the user lands in Settings instead.
 */
object NotificationPermissionFlow {
    enum class Action { SHOW_SYSTEM_PROMPT, OPEN_APP_SETTINGS }

    fun nextAction(
        permissionGranted: Boolean,
        alreadyRequested: Boolean,
        shouldShowRationale: Boolean
    ): Action {
        val canAskSystem = !permissionGranted && (!alreadyRequested || shouldShowRationale)
        return if (canAskSystem) Action.SHOW_SYSTEM_PROMPT else Action.OPEN_APP_SETTINGS
    }
}
