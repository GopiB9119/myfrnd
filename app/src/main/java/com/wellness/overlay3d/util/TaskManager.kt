package com.wellness.overlay3d.util

import android.content.Context
import com.wellness.overlay3d.model.TaskReminder
import org.json.JSONArray
import org.json.JSONObject

/**
 * TaskManager: Stores and retrieves scheduled work reminders.
 */
object TaskManager {
    private const val PREFS_NAME = "myfrnd_task_prefs"
    private const val KEY_REMINDERS = "key_scheduled_reminders"

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAllReminders(context: Context): List<TaskReminder> {
        val jsonString = getPrefs(context).getString(KEY_REMINDERS, null) ?: return emptyList()
        val list = mutableListOf<TaskReminder>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(TaskReminder.fromJson(obj))
            }
        } catch (_: Exception) {}
        return list.sortedBy { it.timestampMillis }
    }

    fun getPendingReminders(context: Context): List<TaskReminder> {
        return getAllReminders(context).filter { !it.isTriggered && it.timestampMillis >= System.currentTimeMillis() - 60_000L }
    }

    fun addReminder(context: Context, reminder: TaskReminder) {
        val current = getAllReminders(context).toMutableList()
        current.add(reminder)
        saveReminders(context, current)
    }

    fun removeReminder(context: Context, reminderId: String) {
        val current = getAllReminders(context).filter { it.id != reminderId }
        saveReminders(context, current)
    }

    fun checkAndPopDueReminders(context: Context): List<TaskReminder> {
        val now = System.currentTimeMillis()
        val all = getAllReminders(context)
        val due = mutableListOf<TaskReminder>()
        val updated = mutableListOf<TaskReminder>()

        for (item in all) {
            if (!item.isTriggered && item.timestampMillis <= now) {
                item.isTriggered = true
                due.add(item)
            }
            // Keep untriggered reminders and recently triggered ones
            if (!item.isTriggered || (now - item.timestampMillis < 24 * 60 * 60 * 1000L)) {
                updated.add(item)
            }
        }

        if (due.isNotEmpty()) {
            saveReminders(context, updated)
        }
        return due
    }

    private fun saveReminders(context: Context, list: List<TaskReminder>) {
        val jsonArray = JSONArray()
        for (item in list) {
            jsonArray.put(item.toJson())
        }
        getPrefs(context).edit().putString(KEY_REMINDERS, jsonArray.toString()).apply()
    }
}
