package com.wellness.overlay3d.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.descendants
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wellness.overlay3d.R
import com.wellness.overlay3d.databinding.ActivityMainBinding
import com.wellness.overlay3d.databinding.ItemTaskReminderBinding
import com.wellness.overlay3d.overlay.DevCompanionCanvasView
import com.wellness.overlay3d.service.CompanionRuntime
import com.wellness.overlay3d.service.CompanionStatus
import com.wellness.overlay3d.service.OverlayService
import com.wellness.overlay3d.util.CompanionPreferences
import com.wellness.overlay3d.util.NotificationPermissionFlow
import com.wellness.overlay3d.util.TaskManager
import com.wellness.overlay3d.util.TaskStorageException
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var syncing = false
    private var pendingEnable = false
    private var tapIndex = 0
    private val stopTalking = Runnable { binding.liveDashboardCompanion.isTalking = false }
    private val taskListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> renderTasks() }
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> loadSettings() }
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingEnable && Settings.canDrawOverlays(this)) enableCompanion()
        pendingEnable = false
        loadSettings()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.dashboardContent.descendants.filter { it.tag == "heading" }
            .forEach { ViewCompat.setAccessibilityHeading(it, true) }
        pendingEnable = savedInstanceState?.getBoolean("pendingEnable") ?: false
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val maxWidth = (720 * resources.displayMetrics.density).toInt()
        // Same rounding as the layout's @dimen padding, so phones never re-pad during layout.
        val padding = resources.getDimensionPixelSize(R.dimen.dashboard_padding)
        binding.root.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val side = maxOf(padding, (view.width - maxWidth) / 2)
            if (binding.dashboardContent.paddingLeft != side) {
                binding.dashboardContent.setPadding(side, padding, side, padding)
            }
        }
        setupListeners()
        loadSettings()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { CompanionRuntime.status.collect { renderStatus(it) } }
                launch {
                    while (isActive) {
                        renderCountdowns(CompanionRuntime.status.value)
                        delay(1_000L)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TaskManager.preferences(this).registerOnSharedPreferenceChangeListener(taskListener)
        CompanionPreferences.getPrefs(this).registerOnSharedPreferenceChangeListener(settingsListener)
        renderTasks()
    }

    override fun onResume() {
        super.onResume()
        binding.liveDashboardCompanion.animationEnabled = true
        loadSettings()
        if (CompanionPreferences.isCompanionEnabled(this) && Settings.canDrawOverlays(this) &&
            !CompanionRuntime.status.value.running) OverlayService.updateConfiguration(this)
    }

    override fun onPause() {
        binding.liveDashboardCompanion.animationEnabled = false
        binding.liveDashboardCompanion.removeCallbacks(stopTalking)
        binding.liveDashboardCompanion.isTalking = false
        super.onPause()
    }

    override fun onStop() {
        TaskManager.preferences(this).unregisterOnSharedPreferenceChangeListener(taskListener)
        CompanionPreferences.getPrefs(this).unregisterOnSharedPreferenceChangeListener(settingsListener)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pendingEnable", pendingEnable)
        super.onSaveInstanceState(outState)
    }

    private fun setupListeners() = with(binding) {
        btnMandatoryGrant.setOnClickListener { requestOverlay() }
        btnNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                notificationAction() == NotificationPermissionFlow.Action.SHOW_SYSTEM_PROMPT) {
                requestNotificationPermission()
            } else {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
        switchEnableCompanion.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            if (!checked) OverlayService.pause(this@MainActivity)
            else if (!Settings.canDrawOverlays(this@MainActivity)) {
                pendingEnable = true
                requestOverlay()
            } else enableCompanion()
            loadSettings()
        }
        btnTestCompanionNow.setOnClickListener {
            if (!Settings.canDrawOverlays(this@MainActivity)) requestOverlay()
            else if (!CompanionPreferences.isCompanionEnabled(this@MainActivity)) {
                Toast.makeText(this@MainActivity, R.string.preview_requires_enable, Toast.LENGTH_SHORT).show()
            } else OverlayService.startWithInitialDemo(this@MainActivity)
        }
        eyeChoices().forEach { (button, value) ->
            button.setOnClickListener { CompanionPreferences.setEyeStrainMinutes(this@MainActivity, value) }
        }
        waterChoices().forEach { (button, value) ->
            button.setOnClickListener { CompanionPreferences.setWaterIntervalMinutes(this@MainActivity, value) }
        }
        positionChoices().forEach { (button, value) ->
            button.setOnClickListener { CompanionPreferences.setPositionMode(this@MainActivity, value) }
        }
        switchVoice.setOnCheckedChangeListener { _, checked ->
            if (!syncing) CompanionPreferences.setVoiceEnabled(this@MainActivity, checked)
        }
        switchReducedMotion.setOnCheckedChangeListener { _, checked ->
            if (!syncing) CompanionPreferences.setReducedMotion(this@MainActivity, checked)
        }
        val poses = listOf(
            Triple(btnPoseHappy, R.string.demo_message, DevCompanionCanvasView.Expression.WINK_PLAYFUL),
            Triple(btnPoseWater, R.string.water_message, DevCompanionCanvasView.Expression.HAPPY_WATER),
            Triple(btnPoseEyes, R.string.eye_message, DevCompanionCanvasView.Expression.TIRED_EYES),
            Triple(btnPoseTask, R.string.tasks_description, DevCompanionCanvasView.Expression.TASK_REMINDER),
            Triple(btnPoseThread, R.string.demo_message, DevCompanionCanvasView.Expression.NORMAL)
        )
        poses.forEach { (button, text, expression) ->
            button.setOnClickListener {
                liveDashboardCompanion.displayMode = if (button == btnPoseThread)
                    DevCompanionCanvasView.DisplayMode.THREAD_DROP else DevCompanionCanvasView.DisplayMode.FULL_BODY
                liveDashboardCompanion.currentExpression = expression
                liveDashboardCompanion.isTalking = true
                liveDashboardCompanion.triggerTapReaction()
                liveDashboardCompanion.removeCallbacks(stopTalking)
                liveDashboardCompanion.postDelayed(stopTalking, 2_600L)
                tvLiveCompanionSpeech.setText(text)
                poses.forEach { select(it.first, it.first == button) }
            }
        }
        liveDashboardCompanion.setOnClickListener {
            poses[tapIndex++ % poses.size].first.performClick()
        }
        btnAddNewTask.setOnClickListener {
            TaskEditorDialog.create().show(supportFragmentManager, "task-editor")
        }
    }

    private fun requestOverlay() {
        try {
            overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (error: ActivityNotFoundException) {
            pendingEnable = false
            Log.e("MainActivity", "Overlay settings unavailable", error)
            Toast.makeText(this, R.string.permission_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun enableCompanion() {
        CompanionPreferences.setCompanionEnabled(this, true)
        OverlayService.updateConfiguration(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermission()
        }
    }

    @RequiresApi(33)
    private fun notificationAction() = NotificationPermissionFlow.nextAction(
        permissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED,
        alreadyRequested = CompanionPreferences.wasNotificationPermissionRequested(this),
        shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
    )

    @RequiresApi(33)
    private fun requestNotificationPermission() {
        CompanionPreferences.markNotificationPermissionRequested(this)
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun eyeChoices() = with(binding) { listOf(chipEye15 to 15, chipEye20 to 20, chipEye30 to 30, chipEye45 to 45) }
    private fun waterChoices() = with(binding) { listOf(chipWater15 to 15, chipWater30 to 30, chipWater45 to 45, chipWater60 to 60) }
    private fun positionChoices() = with(binding) {
        listOf(chipPosAuto to CompanionPreferences.POSITION_AUTO, chipPosThread to CompanionPreferences.POSITION_THREAD_DROP,
            chipPosTopRight to CompanionPreferences.POSITION_TOP_RIGHT, chipPosBottom to CompanionPreferences.POSITION_BOTTOM)
    }

    private fun select(button: Button, selected: Boolean) {
        button.isSelected = selected
        ViewCompat.setStateDescription(button, getString(if (selected) R.string.selected else R.string.not_selected))
    }

    private fun loadSettings() = with(binding) {
        syncing = true
        switchEnableCompanion.isChecked = CompanionPreferences.isCompanionEnabled(this@MainActivity)
        switchVoice.isChecked = CompanionPreferences.isVoiceEnabled(this@MainActivity)
        switchReducedMotion.isChecked = CompanionPreferences.isReducedMotion(this@MainActivity)
        liveDashboardCompanion.reducedMotion = switchReducedMotion.isChecked
        syncing = false
        eyeChoices().forEach { select(it.first, it.second == CompanionPreferences.getEyeStrainMinutes(this@MainActivity)) }
        waterChoices().forEach { select(it.first, it.second == CompanionPreferences.getWaterIntervalMinutes(this@MainActivity)) }
        positionChoices().forEach { select(it.first, it.second == CompanionPreferences.getPositionMode(this@MainActivity)) }
        refreshPermissions()
        renderStatus(CompanionRuntime.status.value)
    }

    private fun refreshPermissions() {
        binding.llMandatoryOverlayBanner.isVisible = !Settings.canDrawOverlays(this)
        binding.notificationBanner.isVisible = !NotificationManagerCompat.from(this).areNotificationsEnabled()
        val asksSystem = Build.VERSION.SDK_INT >= 33 &&
            notificationAction() == NotificationPermissionFlow.Action.SHOW_SYSTEM_PROMPT
        binding.btnNotifications.setText(if (asksSystem) R.string.allow_notifications else R.string.open_notification_settings)
    }

    private fun renderStatus(status: CompanionStatus) = with(binding) {
        val enabled = CompanionPreferences.isCompanionEnabled(this@MainActivity)
        tvEngineStatusBadge.setText(when {
            !Settings.canDrawOverlays(this@MainActivity) -> R.string.setup_required
            !enabled -> R.string.status_paused
            status.error != null -> R.string.status_problem
            status.running -> R.string.status_active
            else -> R.string.status_starting
        })
        tvHeroSubtitle.text = if (enabled && status.running) getString(R.string.active_description,
            CompanionPreferences.getEyeStrainMinutes(this@MainActivity),
            CompanionPreferences.getWaterIntervalMinutes(this@MainActivity)) else getString(R.string.paused_description)
        tvRuntimeError.isVisible = status.error != null
        status.error?.let { tvRuntimeError.setText(it) }
        renderCountdowns(status)
    }

    private fun renderCountdowns(status: CompanionStatus) {
        fun render(view: TextView, progress: ProgressBar, deadline: Long?, minutes: Int, label: Int) {
            val remaining = ((deadline ?: 0L) - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            val seconds = (remaining + 999) / 1000
            val text = when {
                !status.running -> getString(R.string.countdown_paused)
                !status.screenActive -> getString(R.string.countdown_unlock)
                seconds == 0L -> getString(R.string.countdown_waiting)
                else -> getString(R.string.countdown, seconds / 60, seconds % 60)
            }
            if (view.text != text) {
                view.text = text
                view.contentDescription = getString(R.string.countdown_accessibility, getString(label), text)
            }
            progress.progress = if (!status.running || deadline == null) 0
                else (1000 - remaining * 1000 / (minutes * 60_000L)).toInt().coerceIn(0, 1000)
        }
        render(binding.tvWaterCountdown, binding.waterProgress, status.nextWater,
            CompanionPreferences.getWaterIntervalMinutes(this), R.string.water_title)
        render(binding.tvEyeCountdown, binding.eyeProgress, status.nextEye,
            CompanionPreferences.getEyeStrainMinutes(this), R.string.eye_title)
    }

    private fun renderTasks() {
        val tasks = try { TaskManager.getPendingReminders(this) } catch (error: TaskStorageException) {
            Log.e("MainActivity", "Could not display saved reminders", error)
            binding.tvTaskError.setText(R.string.task_storage_error)
            binding.tvTaskError.isVisible = true
            binding.tvEmptyTasks.isVisible = false
            binding.btnAddNewTask.isEnabled = false
            return
        }
        binding.tvTaskError.isVisible = false
        binding.btnAddNewTask.isEnabled = true
        binding.tvEmptyTasks.isVisible = tasks.isEmpty()
        binding.tvTaskCount.text = getString(R.string.task_count, tasks.size)
        binding.llTasksContainer.removeAllViews()
        tasks.forEach { task ->
            val item = ItemTaskReminderBinding.inflate(layoutInflater, binding.llTasksContainer, false)
            item.tvTaskTitle.text = task.title
            val date = Date(task.timestampMillis)
            val time = "${DateFormat.getMediumDateFormat(this).format(date)}, ${DateFormat.getTimeFormat(this).format(date)}"
            item.tvTaskTime.text = if (task.timestampMillis <= System.currentTimeMillis()) getString(R.string.task_overdue, time) else time
            item.btnEditTask.contentDescription = getString(R.string.edit_task, task.title)
            item.btnEditTask.setOnClickListener { TaskEditorDialog.create(task).show(supportFragmentManager, "task-editor") }
            item.btnDeleteTask.contentDescription = getString(R.string.delete_task, task.title)
            item.btnDeleteTask.setOnClickListener {
                AlertDialog.Builder(this).setTitle(R.string.delete_confirmation).setMessage(task.title)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        try {
                            TaskManager.removeReminder(this, task.id)
                            Toast.makeText(this, R.string.reminder_deleted, Toast.LENGTH_SHORT).show()
                        } catch (error: TaskStorageException) {
                            Log.e("MainActivity", "Could not delete reminder", error)
                            renderTasks()
                        }
                    }.show()
            }
            binding.llTasksContainer.addView(item.root)
        }
    }
}
