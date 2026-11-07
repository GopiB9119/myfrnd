package com.wellness.overlay3d.util

import com.wellness.overlay3d.util.NotificationPermissionFlow.Action.OPEN_APP_SETTINGS
import com.wellness.overlay3d.util.NotificationPermissionFlow.Action.SHOW_SYSTEM_PROMPT
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPermissionFlowTest {
    private fun action(
        granted: Boolean = false,
        requested: Boolean = false,
        rationale: Boolean = false
    ) = NotificationPermissionFlow.nextAction(granted, requested, rationale)

    @Test fun firstEverTapShowsTheSystemPrompt() {
        // Regression: Android reports no rationale before the first denial, which used to skip the prompt.
        assertEquals(SHOW_SYSTEM_PROMPT, action(requested = false, rationale = false))
    }

    @Test fun afterOneDenialWithRationaleAsksAgain() {
        assertEquals(SHOW_SYSTEM_PROMPT, action(requested = true, rationale = true))
    }

    @Test fun afterPermanentDenialOpensSettings() {
        assertEquals(OPEN_APP_SETTINGS, action(requested = true, rationale = false))
    }

    @Test fun grantedPermissionOpensSettingsToManageIt() {
        assertEquals(OPEN_APP_SETTINGS, action(granted = true, requested = true))
        assertEquals(OPEN_APP_SETTINGS, action(granted = true, requested = false))
    }
}
