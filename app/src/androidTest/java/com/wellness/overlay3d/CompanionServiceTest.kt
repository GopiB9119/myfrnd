package com.wellness.overlay3d

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wellness.overlay3d.model.TaskReminder
import com.wellness.overlay3d.receiver.BootReceiver
import com.wellness.overlay3d.service.CompanionRuntime
import com.wellness.overlay3d.service.OverlayService
import com.wellness.overlay3d.ui.MainActivity
import com.wellness.overlay3d.util.CompanionPreferences
import com.wellness.overlay3d.util.TaskManager
import android.os.ParcelFileDescriptor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompanionServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun prepare() {
        instrumentation.runOnMainSync { OverlayService.pause(context) }
        instrumentation.waitForIdleSync()
        CompanionPreferences.getPrefs(context).edit().clear().commit()
        TaskManager.preferences(context).edit().clear().commit()
        CompanionPreferences.setReducedMotion(context, true)
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("input keyevent 224")
        shell("input keyevent 82")
        // Launch like the home screen does: its MAIN/LAUNCHER base intent differs from the notification's explicit one.
        scenario = ActivityScenario.launch(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(context, MainActivity::class.java.name)
        )
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync { OverlayService.pause(context) }
        instrumentation.waitForIdleSync()
        scenario.close()
        shell("input keyevent 224")
        shell("input keyevent 82")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
    }

    private fun start() {
        scenario.onActivity {
            CompanionPreferences.setCompanionEnabled(it, true)
            assertTrue(OverlayService.updateConfiguration(it))
        }
        waitUntil { CompanionRuntime.status.value.running && CompanionRuntime.status.value.screenActive }
    }

    @Test fun allSimultaneousTasksAreActuallyDisplayedSequentially() {
        start()
        scenario.onActivity {
            // One shared due time; the margin keeps all three adds in the future on a loaded device.
            val due = System.currentTimeMillis() + 5_000L
            repeat(3) { index ->
                TaskManager.addReminder(it, TaskReminder("task-$index", "Task $index", due))
            }
        }
        waitUntil(20_000L) { TaskManager.getPendingReminders(context).size == 2 }
        assertEquals(listOf("task-1", "task-2"), TaskManager.getPendingReminders(context).map { it.id })
        // The original implementation marked every due task triggered before showing only the first.
        SystemClock.sleep(500L)
        assertEquals(2, TaskManager.getPendingReminders(context).size)
        waitUntil(20_000L) { TaskManager.getPendingReminders(context).size == 1 }
        waitUntil(20_000L) { TaskManager.getPendingReminders(context).isEmpty() }
    }

    @Test fun reopeningAndUnrelatedConfigurationDoNotResetTimers() {
        start()
        val original = CompanionRuntime.status.value
        scenario.onActivity {
            CompanionPreferences.setPositionMode(it, CompanionPreferences.POSITION_TOP_RIGHT)
            OverlayService.updateConfiguration(it)
        }
        scenario.recreate()
        instrumentation.waitForIdleSync()
        val current = CompanionRuntime.status.value
        assertEquals(original.nextWater, current.nextWater)
        assertEquals(original.nextEye, current.nextEye)
    }

    @Test fun liveCountdownTextActuallyDecreases() {
        start()
        var before = ""
        scenario.onActivity { before = it.findViewById<TextView>(R.id.tvWaterCountdown).text.toString() }
        SystemClock.sleep(2_100L)
        var after = ""
        scenario.onActivity { after = it.findViewById<TextView>(R.id.tvWaterCountdown).text.toString() }
        fun seconds(text: String): Int {
            val parts = text.split(":").map(String::toInt)
            return parts[0] * 60 + parts[1]
        }
        assertTrue("Countdown must decrease: $before -> $after", seconds(after) < seconds(before))
    }

    @Test fun backgroundPauseIsNotUndoneByRestoredSwitchState() {
        start()
        scenario.moveToState(Lifecycle.State.CREATED)
        instrumentation.runOnMainSync { OverlayService.pause(context) }
        waitUntil { !CompanionRuntime.status.value.running }
        scenario.recreate()
        scenario.moveToState(Lifecycle.State.RESUMED)
        instrumentation.waitForIdleSync()
        assertFalse("Restoring the old switch must not enable reminders", CompanionPreferences.isCompanionEnabled(context))
        assertFalse(CompanionRuntime.status.value.running)
    }

    @Test fun notificationPauseSurvivesBootAndConfigurationChanges() {
        start()
        scenario.onActivity {
            it.startService(Intent(it, OverlayService::class.java).setAction(OverlayService.ACTION_STOP_SERVICE))
        }
        waitUntil { !CompanionPreferences.isCompanionEnabled(context) && !CompanionRuntime.status.value.running }
        scenario.onActivity {
            CompanionPreferences.setWaterIntervalMinutes(it, 45)
            BootReceiver().onReceive(it, Intent(Intent.ACTION_BOOT_COMPLETED))
        }
        instrumentation.waitForIdleSync()
        assertFalse(CompanionPreferences.isCompanionEnabled(context))
        assertFalse(CompanionRuntime.status.value.running)
    }

    @Test fun lockedPhoneDefersTasksInsteadOfConsumingThem() {
        start()
        scenario.onActivity {
            TaskManager.addReminder(it, TaskReminder("locked", "Wait for unlock", System.currentTimeMillis() + 2_000L))
        }
        shell("input keyevent 223")
        waitUntil { !CompanionRuntime.status.value.screenActive }
        SystemClock.sleep(2_500L)
        assertEquals("locked", TaskManager.getPendingReminders(context).single().id)
        shell("input keyevent 224")
        shell("input keyevent 82")
        waitUntil { TaskManager.getPendingReminders(context).isEmpty() }
    }

    @Test fun tappingTheNotificationReusesTheOpenDashboard() {
        start()
        val notifications = context.getSystemService(NotificationManager::class.java)
        waitUntil { notifications.activeNotifications.any { it.id == OverlayService.NOTIFICATION_ID } }
        val open = notifications.activeNotifications.first { it.id == OverlayService.NOTIFICATION_ID }.notification.contentIntent
        assertEquals(1, activitiesInAppTasks())
        repeat(3) {
            open.send()
            SystemClock.sleep(1_500L)
        }
        // Without singleTask every tap stacked one more dashboard, so Back had to be pressed once per tap.
        assertEquals("Tapping the notification must not stack another dashboard", 1, activitiesInAppTasks())
    }

    private fun activitiesInAppTasks() =
        context.getSystemService(ActivityManager::class.java).appTasks.sumOf { it.taskInfo.numActivities }

    private fun waitUntil(timeout: Long = 10_000L, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100L)
        }
        assertTrue("Condition not reached within ${timeout}ms", condition())
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }
}
