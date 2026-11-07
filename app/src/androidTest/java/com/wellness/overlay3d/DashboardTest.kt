package com.wellness.overlay3d

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wellness.overlay3d.service.CompanionRuntime
import com.wellness.overlay3d.service.OverlayService
import com.wellness.overlay3d.ui.MainActivity
import com.wellness.overlay3d.util.CompanionPreferences
import com.wellness.overlay3d.util.TaskManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun launch() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { OverlayService.pause(context) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        CompanionPreferences.getPrefs(context).edit().clear().commit()
        TaskManager.preferences(context).edit().clear().commit()
        CompanionPreferences.setReducedMotion(context, true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun close() { scenario.close() }

    @Test fun startsPausedWithoutRequestingPermissions() {
        onView(withId(R.id.switchEnableCompanion)).check(matches(isNotChecked()))
        assertFalse(CompanionPreferences.isCompanionEnabled(context))
        assertFalse(CompanionRuntime.status.value.running)
    }

    @Test fun characterArtworkIsNotUpscaledByScreenDensity() {
        listOf(R.drawable.img_companion_fullbody, R.drawable.img_companion_portrait).forEach { resource ->
            val bitmap = BitmapFactory.decodeResource(context.resources, resource)
            assertEquals(896, bitmap.width)
            assertEquals(1200, bitmap.height)
            assertTrue(bitmap.allocationByteCount <= 4_300_800)
            bitmap.recycle()
        }
    }

    @Test fun heroArtworkKeepsItsCollarButHasNoLeftoverBackgroundMatte() {
        val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.img_companion_fullbody)
        try {
            // Between the legs, between arm and torso, and the floor glare beside the foot used to be opaque
            // white/grey from the source image, which showed as bright patches on dark backgrounds.
            listOf(460 to 800, 350 to 450, 342 to 1092).forEach { (x, y) ->
                assertEquals("pixel $x,$y must be transparent", 0, Color.alpha(bitmap.getPixel(x, y)))
            }
            // The white T-shirt collar is real artwork and the trousers are the body; both must stay opaque.
            val collar = bitmap.getPixel(470, 290)
            assertEquals(255, Color.alpha(collar))
            assertTrue(Color.red(collar) > 200 && Color.green(collar) > 200 && Color.blue(collar) > 200)
            assertEquals(255, Color.alpha(bitmap.getPixel(400, 800)))
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun changingIntervalsWhilePausedDoesNotStartService() {
        onView(withId(R.id.chipEye20)).perform(scrollTo(), click())
        onView(withId(R.id.chipWater45)).perform(scrollTo(), click())
        assertEquals(20, CompanionPreferences.getEyeStrainMinutes(context))
        assertEquals(45, CompanionPreferences.getWaterIntervalMinutes(context))
        assertFalse(CompanionRuntime.status.value.running)
        scenario.recreate()
        onView(withId(R.id.chipEye20)).check(matches(isSelected()))
        onView(withId(R.id.chipWater45)).check(matches(isSelected()))
    }

    @Test fun customCompanionRespondsToRealTap() {
        onView(withId(R.id.liveDashboardCompanion)).perform(scrollTo(), click(), click())
        onView(withId(R.id.tvLiveCompanionSpeech)).check(matches(withText(R.string.water_message)))
    }

    @Test fun blankTaskShowsValidationAndKeepsEditorOpen() {
        onView(withId(R.id.btnAddNewTask)).perform(scrollTo(), click())
        onView(withText(R.string.save)).perform(click())
        onView(withId(R.id.etTaskTitle)).check(matches(hasErrorText(context.getString(R.string.empty_title_error))))
        assertTrue(TaskManager.getPendingReminders(context).isEmpty())
    }

    @Test fun reminderCrudPersistsAndDeleteRequiresConfirmation() {
        onView(withId(R.id.btnAddNewTask)).perform(scrollTo(), click())
        onView(withId(R.id.etTaskTitle)).perform(replaceText("Finish the report"), closeSoftKeyboard())
        onView(withText(R.string.save)).perform(click())
        val id = TaskManager.getPendingReminders(context).single().id
        onView(withId(R.id.btnEditTask)).perform(scrollTo(), click())
        onView(withId(R.id.etTaskTitle)).perform(replaceText("Review the report"), closeSoftKeyboard())
        onView(withText(R.string.save)).perform(click())
        assertEquals(id, TaskManager.getPendingReminders(context).single().id)
        scenario.recreate()
        onView(withId(R.id.tvTaskTitle)).check(matches(withText("Review the report")))
        onView(withId(R.id.btnDeleteTask)).perform(scrollTo(), click())
        onView(withText(R.string.delete_confirmation)).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
        assertEquals(1, TaskManager.getPendingReminders(context).size)
        onView(withId(R.id.btnDeleteTask)).perform(click())
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        assertTrue(TaskManager.getPendingReminders(context).isEmpty())
    }

    @Test fun fifteenMinutePresetSchedulesTheMinuteItShowsAndStaysSelected() {
        onView(withId(R.id.btnAddNewTask)).perform(scrollTo(), click())
        onView(withId(R.id.pillIn1Hour)).check(matches(isSelected()))
        onView(withId(R.id.etTaskTitle)).perform(replaceText("Stand up and stretch"), closeSoftKeyboard())
        val before = System.currentTimeMillis()
        onView(withId(R.id.pillIn15Min)).perform(scrollTo(), click())
        val after = System.currentTimeMillis()
        onView(withId(R.id.pillIn15Min)).check(matches(isSelected()))
        listOf(R.id.pillIn1Hour, R.id.pillTodayEvening, R.id.pillTomorrowMorning).forEach {
            onView(withId(it)).check(matches(isNotSelected()))
        }
        onView(withText(R.string.save)).perform(click())
        val scheduled = TaskManager.getPendingReminders(context).single().timestampMillis
        assertEquals("due time must be a whole minute, like its label", 0L, scheduled % 60_000L)
        assertTrue(scheduled in (before + 840_000L)..(after + 900_000L))
    }

    @Test fun taskDraftSurvivesActivityRecreation() {
        onView(withId(R.id.btnAddNewTask)).perform(scrollTo(), click())
        onView(withId(R.id.etTaskTitle)).perform(replaceText("Keep my draft"), closeSoftKeyboard())
        onView(withId(R.id.pillTomorrowMorning)).perform(scrollTo(), click())
        var dateText = ""
        onView(withId(R.id.tvSelectedDateTime)).check { view, _ -> dateText = (view as TextView).text.toString() }
        scenario.recreate()
        onView(withId(R.id.etTaskTitle)).check(matches(withText("Keep my draft")))
        onView(withId(R.id.tvSelectedDateTime)).check(matches(withText(dateText)))
        onView(withId(R.id.pillTomorrowMorning)).check(matches(isSelected()))
        onView(withId(R.id.pillIn1Hour)).check(matches(isNotSelected()))
    }

    @Test fun corruptDataHasVisibleErrorRatherThanEmptySuccess() {
        scenario.onActivity {
            TaskManager.preferences(context).edit().putString("key_scheduled_reminders", "broken").apply()
        }
        onView(withId(R.id.tvTaskError)).perform(scrollTo()).check(matches(withText(R.string.task_storage_error)))
        onView(withId(R.id.btnAddNewTask)).check(matches(isNotEnabled()))
    }
}
