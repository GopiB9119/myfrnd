package com.wellness.overlay3d.ui

import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.fragment.app.DialogFragment
import com.wellness.overlay3d.R
import com.wellness.overlay3d.databinding.DialogAddTaskBinding
import com.wellness.overlay3d.model.ReminderPresets
import com.wellness.overlay3d.model.ReminderPresets.Preset
import com.wellness.overlay3d.model.TaskReminder
import com.wellness.overlay3d.util.TaskManager
import com.wellness.overlay3d.util.TaskStorageException
import java.util.Calendar
import java.util.Date

class TaskEditorDialog : DialogFragment() {
    private var binding: DialogAddTaskBinding? = null
    private var timestamp = 0L
    private var preset: Preset? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val editor = DialogAddTaskBinding.inflate(layoutInflater)
        binding = editor
        val id = arguments?.getString("id")
        val existingTime = arguments?.takeIf { it.containsKey("time") }?.getLong("time")
        preset = when {
            savedInstanceState != null -> savedInstanceState.getString("preset")?.let { Preset.valueOf(it) }
            existingTime == null -> Preset.IN_1_HOUR
            else -> null
        }
        timestamp = savedInstanceState?.getLong("time") ?: existingTime
            ?: ReminderPresets.dueTime(Preset.IN_1_HOUR, System.currentTimeMillis())
        editor.etTaskTitle.setText(savedInstanceState?.getString("title") ?: arguments?.getString("title").orEmpty())
        updateDateLabel()
        presetPills().forEach { (pill, choice) ->
            pill.setOnClickListener {
                timestamp = ReminderPresets.dueTime(choice, System.currentTimeMillis())
                preset = choice
                updateDateLabel()
            }
        }
        editor.btnPickCustomDateTime.setOnClickListener {
            val selected = Calendar.getInstance().apply { timeInMillis = timestamp }
            DatePickerDialog(context, { _, year, month, day ->
                TimePickerDialog(context, { _, hour, minute ->
                    timestamp = Calendar.getInstance().apply {
                        set(year, month, day, hour, minute, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    preset = null
                    updateDateLabel()
                }, selected.get(Calendar.HOUR_OF_DAY), selected.get(Calendar.MINUTE),
                    DateFormat.is24HourFormat(context)).show()
            }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH))
                .apply { datePicker.minDate = System.currentTimeMillis() }.show()
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (id == null) R.string.add_task_title else R.string.edit_task_title)
            .setView(editor.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = editor.etTaskTitle.text.toString().trim()
                if (title.isBlank()) {
                    editor.etTaskTitle.error = getString(R.string.empty_title_error)
                    editor.etTaskTitle.requestFocus()
                    return@setOnClickListener
                }
                if (timestamp <= System.currentTimeMillis()) {
                    editor.tvSelectedDateTime.error = getString(R.string.past_time_error)
                    editor.tvSelectedDateTime.text = getString(R.string.past_time_error)
                    return@setOnClickListener
                }
                try {
                    if (id == null) {
                        TaskManager.addReminder(context, TaskReminder(title = title, timestampMillis = timestamp))
                    } else if (!TaskManager.updateReminder(context, TaskReminder(id, title, timestamp))) {
                        editor.tvSelectedDateTime.text = getString(R.string.task_no_longer_pending)
                        return@setOnClickListener
                    }
                    Toast.makeText(context, R.string.reminder_saved, Toast.LENGTH_SHORT).show()
                    dismiss()
                } catch (error: TaskStorageException) {
                    Log.e("TaskEditorDialog", "Could not save reminder", error)
                    editor.tvSelectedDateTime.text = getString(R.string.task_storage_error)
                } catch (error: IllegalArgumentException) {
                    Log.w("TaskEditorDialog", "Reminder no longer passes validation", error)
                    editor.tvSelectedDateTime.text = getString(R.string.past_time_error)
                }
            }
        }
        return dialog
    }

    private fun updateDateLabel() {
        val context = requireContext()
        val date = Date(timestamp)
        val formatted = "${DateFormat.getMediumDateFormat(context).format(date)}, ${DateFormat.getTimeFormat(context).format(date)}"
        binding?.tvSelectedDateTime?.apply {
            error = null
            text = getString(R.string.scheduled_for, formatted)
        }
        presetPills().forEach { (pill, choice) ->
            pill.isSelected = choice == preset
            ViewCompat.setStateDescription(pill, getString(if (pill.isSelected) R.string.selected else R.string.not_selected))
        }
    }

    private fun presetPills(): List<Pair<Button, Preset>> = binding?.run {
        listOf(pillIn15Min to Preset.IN_15_MIN, pillIn1Hour to Preset.IN_1_HOUR,
            pillTodayEvening to Preset.NEXT_EVENING, pillTomorrowMorning to Preset.TOMORROW_MORNING)
    }.orEmpty()

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("time", timestamp)
        outState.putString("preset", preset?.name)
        outState.putString("title", binding?.etTaskTitle?.text?.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        fun create(task: TaskReminder? = null) = TaskEditorDialog().apply {
            if (task != null) arguments = Bundle().apply {
                putString("id", task.id)
                putString("title", task.title)
                putLong("time", task.timestampMillis)
            }
        }
    }
}
