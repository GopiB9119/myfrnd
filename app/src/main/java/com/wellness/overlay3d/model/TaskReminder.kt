package com.wellness.overlay3d.model

import org.json.JSONObject
import java.util.UUID

/**
 * TaskReminder: Data model for work and task reminders.
 */
data class TaskReminder(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val timestampMillis: Long,
    var isTriggered: Boolean = false
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("title", title)
            put("timestampMillis", timestampMillis)
            put("isTriggered", isTriggered)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): TaskReminder {
            return TaskReminder(
                id = json.optString("id", UUID.randomUUID().toString()),
                title = json.optString("title", "Work Reminder"),
                timestampMillis = json.optLong("timestampMillis", System.currentTimeMillis()),
                isTriggered = json.optBoolean("isTriggered", false)
            )
        }
    }
}
