package com.wellness.overlay3d.util

import android.content.Context
import com.wellness.overlay3d.model.TaskReminder
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskManagerTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        TaskManager.preferences(context).edit().clear().commit()
    }

    @Test fun simultaneousRemindersRemainPendingUntilIndividuallyDelivered() {
        val first = TaskReminder("first", "First", 200L)
        val second = TaskReminder("second", "Second", 200L)
        TaskManager.addReminder(context, first, 100L)
        TaskManager.addReminder(context, second, 100L)
        assertEquals(2, TaskManager.getPendingReminders(context).size)
        TaskManager.markDelivered(context, first.id, 300L)
        assertEquals(listOf(second), TaskManager.getPendingReminders(context))
        TaskManager.markDelivered(context, second.id, 300L)
        assertTrue(TaskManager.getPendingReminders(context).isEmpty())
    }

    @Test fun overdueRemindersStayVisibleEvenAfterMoreThanOneMinute() {
        TaskManager.addReminder(context, TaskReminder("late", "Still here", 200L), 100L)
        assertEquals("late", TaskManager.getPendingReminders(context).single().id)
    }

    @Test fun readingDueItemsDoesNotConsumeThem() {
        TaskManager.addReminder(context, TaskReminder("one", "Not lost", 200L), 100L)
        repeat(3) { assertEquals(1, TaskManager.getPendingReminders(context).size) }
    }

    @Test fun remindersAreSortedByTime() {
        TaskManager.addReminder(context, TaskReminder("later", "Later", 300L), 100L)
        TaskManager.addReminder(context, TaskReminder("earlier", "Earlier", 200L), 100L)
        assertEquals(listOf("earlier", "later"), TaskManager.getPendingReminders(context).map { it.id })
    }

    @Test fun editingPreservesIdentityAndDoesNotDuplicate() {
        TaskManager.addReminder(context, TaskReminder("one", "Original", 200L), 100L)
        assertTrue(TaskManager.updateReminder(context, TaskReminder("one", "Edited", 400L), 100L))
        assertEquals(TaskReminder("one", "Edited", 400L), TaskManager.getPendingReminders(context).single())
    }

    @Test fun editingAnAlreadyDeliveredTaskDoesNotResurrectIt() {
        TaskManager.addReminder(context, TaskReminder("one", "Original", 200L), 100L)
        TaskManager.markDelivered(context, "one", 250L)
        assertFalse(TaskManager.updateReminder(context, TaskReminder("one", "Edited", 400L), 300L))
        assertTrue(TaskManager.getPendingReminders(context).isEmpty())
    }

    @Test fun deletionOnlyRemovesSelectedTask() {
        TaskManager.addReminder(context, TaskReminder("one", "First", 200L), 100L)
        TaskManager.addReminder(context, TaskReminder("two", "Second", 200L), 100L)
        TaskManager.removeReminder(context, "one")
        assertEquals("two", TaskManager.getPendingReminders(context).single().id)
    }

    @Test fun pastAndPresentDatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TaskManager.addReminder(context, TaskReminder(title = "Past", timestampMillis = 100L), 100L)
        }
        assertTrue(TaskManager.getPendingReminders(context).isEmpty())
    }

    @Test fun corruptStorageIsReportedAndNeverOverwrittenByAdding() {
        val prefs = TaskManager.preferences(context)
        prefs.edit().putString("key_scheduled_reminders", "not-json").commit()
        assertThrows(TaskStorageException::class.java) { TaskManager.getAllReminders(context) }
        assertThrows(TaskStorageException::class.java) {
            TaskManager.addReminder(context, TaskReminder(title = "New", timestampMillis = 200L), 100L)
        }
        assertEquals("not-json", prefs.getString("key_scheduled_reminders", null))
    }

    @Test fun malformedItemDoesNotSilentlyDropOtherItems() {
        TaskManager.preferences(context).edit().putString("key_scheduled_reminders",
            """[{"id":"one","title":"Valid","timestampMillis":200},{"title":"Broken"}]""").commit()
        assertThrows(TaskStorageException::class.java) { TaskManager.getAllReminders(context) }
    }

    @Test fun invalidTitlesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TaskReminder(title = " ", timestampMillis = 200L) }
        assertThrows(IllegalArgumentException::class.java) { TaskReminder(title = "x".repeat(161), timestampMillis = 200L) }
    }
}
