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
    val isTriggered: Boolean = false
) {
    init {
        require(id.isNotBlank()) { "Reminder ID must not be blank" }
        require(title.isNotBlank() && title.length <= MAX_TITLE_LENGTH) { "Invalid reminder title" }
        require(timestampMillis > 0) { "Invalid reminder date" }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("title", title)
            put("timestampMillis", timestampMillis)
            put("isTriggered", isTriggered)
        }
    }

    companion object {
        const val MAX_TITLE_LENGTH = 160

        fun fromJson(json: JSONObject): TaskReminder {
            return TaskReminder(
                id = json.getString("id"),
                title = json.getString("title"),
                timestampMillis = json.getLong("timestampMillis"),
                isTriggered = if (json.has("isTriggered")) json.getBoolean("isTriggered") else false
            )
        }
    }
}
