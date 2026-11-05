package com.wellness.overlay3d.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.wellness.overlay3d.R
import com.wellness.overlay3d.model.TaskReminder
import com.wellness.overlay3d.overlay.DevCompanionCanvasView
import com.wellness.overlay3d.service.OverlayService
import com.wellness.overlay3d.util.CompanionPreferences
import com.wellness.overlay3d.util.TaskManager
import java.text.SimpleDateFormat
import java.util.*

/**
 * MainActivity: Clean, Minimalist UI/UX for "myfrnd".
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvEngineStatusBadge: TextView
    private lateinit var llMandatoryOverlayBanner: View
    private lateinit var btnMandatoryGrant: Button

    private lateinit var tvHeroSubtitle: TextView
    private lateinit var switchEnableCompanion: SwitchCompat
    private lateinit var btnTestCompanionNow: Button

    private lateinit var liveDashboardCompanion: DevCompanionCanvasView
    private lateinit var tvLiveCompanionSpeech: TextView
    private var tapCycleIndex = 0

    private lateinit var chipEye15: TextView
    private lateinit var chipEye20: TextView
    private lateinit var chipEye30: TextView
    private lateinit var chipEye45: TextView

    private lateinit var chipWater15: TextView
    private lateinit var chipWater30: TextView
    private lateinit var chipWater45: TextView
    private lateinit var chipWater60: TextView

    private lateinit var chipPosAuto: TextView
    private lateinit var chipPosThread: TextView
    private lateinit var chipPosTopRight: TextView
    private lateinit var chipPosBottom: TextView

    private lateinit var btnPoseHappy: TextView
    private lateinit var btnPoseWater: TextView
    private lateinit var btnPoseEyes: TextView
    private lateinit var btnPoseTask: TextView
    private lateinit var btnPoseThread: TextView

    private lateinit var btnAddNewTask: Button
    private lateinit var llTasksContainer: LinearLayout
    private lateinit var tvEmptyTasks: TextView

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionStatuses()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        updatePermissionStatuses()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupListeners()
        loadPreferencesIntoUi()
        renderScheduledTasks()

        // Auto request notification on Android 13+ if not granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotification = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotification) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatuses()
        renderScheduledTasks()
    }

    private fun bindViews() {
        tvEngineStatusBadge = findViewById(R.id.tvEngineStatusBadge)
        llMandatoryOverlayBanner = findViewById(R.id.llMandatoryOverlayBanner)
        btnMandatoryGrant = findViewById(R.id.btnMandatoryGrant)

        tvHeroSubtitle = findViewById(R.id.tvHeroSubtitle)
        switchEnableCompanion = findViewById(R.id.switchEnableCompanion)
        btnTestCompanionNow = findViewById(R.id.btnTestCompanionNow)

        liveDashboardCompanion = findViewById(R.id.liveDashboardCompanion)
        tvLiveCompanionSpeech = findViewById(R.id.tvLiveCompanionSpeech)

        chipEye15 = findViewById(R.id.chipEye15)
        chipEye20 = findViewById(R.id.chipEye20)
        chipEye30 = findViewById(R.id.chipEye30)
        chipEye45 = findViewById(R.id.chipEye45)

        chipWater15 = findViewById(R.id.chipWater15)
        chipWater30 = findViewById(R.id.chipWater30)
        chipWater45 = findViewById(R.id.chipWater45)
        chipWater60 = findViewById(R.id.chipWater60)

        chipPosAuto = findViewById(R.id.chipPosAuto)
        chipPosThread = findViewById(R.id.chipPosThread)
        chipPosTopRight = findViewById(R.id.chipPosTopRight)
        chipPosBottom = findViewById(R.id.chipPosBottom)

        btnPoseHappy = findViewById(R.id.btnPoseHappy)
        btnPoseWater = findViewById(R.id.btnPoseWater)
        btnPoseEyes = findViewById(R.id.btnPoseEyes)
        btnPoseTask = findViewById(R.id.btnPoseTask)
        btnPoseThread = findViewById(R.id.btnPoseThread)

        btnAddNewTask = findViewById(R.id.btnAddNewTask)
        llTasksContainer = findViewById(R.id.llTasksContainer)
        tvEmptyTasks = findViewById(R.id.tvEmptyTasks)
    }

    private fun setupListeners() {
        val launchOverlaySettings = {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }

        btnMandatoryGrant.setOnClickListener { launchOverlaySettings() }

        // Interactive Live Dashboard Companion Tap
        val tapQuotes = listOf(
            "💧 Remember to drink water regularly!" to DevCompanionCanvasView.Expression.HAPPY_WATER,
            "👀 Rest your eyes: look 20 ft away for 20s!" to DevCompanionCanvasView.Expression.TIRED_EYES,
            "✨ You're doing amazing today, keep going!" to DevCompanionCanvasView.Expression.WINK_PLAYFUL,
            "📋 Stay organized: check your work reminders!" to DevCompanionCanvasView.Expression.TASK_REMINDER,
            "🪢 I'll drop in whenever you need me!" to DevCompanionCanvasView.Expression.NORMAL
        )

        liveDashboardCompanion.setOnClickListener {
            liveDashboardCompanion.triggerTapReaction()
            val (quote, expr) = tapQuotes[tapCycleIndex % tapQuotes.size]
            tapCycleIndex++
            liveDashboardCompanion.currentExpression = expr
            liveDashboardCompanion.isTalking = true
            tvLiveCompanionSpeech.text = quote
            tvLiveCompanionSpeech.alpha = 0f
            tvLiveCompanionSpeech.animate().alpha(1f).setDuration(220L).start()

            liveDashboardCompanion.postDelayed({
                liveDashboardCompanion.isTalking = false
            }, 2600L)
        }

        // Eye Strain Timing
        chipEye15.setOnClickListener { selectEyeInterval(15) }
        chipEye20.setOnClickListener { selectEyeInterval(20) }
        chipEye30.setOnClickListener { selectEyeInterval(30) }
        chipEye45.setOnClickListener { selectEyeInterval(45) }

        // Water Timing
        chipWater15.setOnClickListener { selectWaterInterval(15) }
        chipWater30.setOnClickListener { selectWaterInterval(30) }
        chipWater45.setOnClickListener { selectWaterInterval(45) }
        chipWater60.setOnClickListener { selectWaterInterval(60) }

        // Position Mode
        chipPosAuto.setOnClickListener { selectPositionMode(CompanionPreferences.POSITION_AUTO) }
        chipPosThread.setOnClickListener { selectPositionMode(CompanionPreferences.POSITION_THREAD_DROP) }
        chipPosTopRight.setOnClickListener { selectPositionMode(CompanionPreferences.POSITION_TOP_RIGHT) }
        chipPosBottom.setOnClickListener { selectPositionMode(CompanionPreferences.POSITION_BOTTOM) }

        val checkOverlayAndRun = { action: () -> Unit ->
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Enable 'Display over other apps' first!", Toast.LENGTH_SHORT).show()
                launchOverlaySettings()
            } else {
                action()
            }
        }

        // Interactive Companion Pose Previews
        val speakPose = { quote: String, expr: DevCompanionCanvasView.Expression, mode: DevCompanionCanvasView.DisplayMode ->
            liveDashboardCompanion.displayMode = mode
            liveDashboardCompanion.currentExpression = expr
            liveDashboardCompanion.isTalking = true
            liveDashboardCompanion.triggerTapReaction()
            tvLiveCompanionSpeech.text = quote
            tvLiveCompanionSpeech.alpha = 0f
            tvLiveCompanionSpeech.animate().alpha(1f).setDuration(220L).start()
            liveDashboardCompanion.postDelayed({
                liveDashboardCompanion.isTalking = false
            }, 2600L)
        }

        btnPoseHappy.setOnClickListener {
            speakPose("✨ You're awesome! Stay energized and happy!", DevCompanionCanvasView.Expression.WINK_PLAYFUL, DevCompanionCanvasView.DisplayMode.FULL_BODY)
        }

        btnPoseWater.setOnClickListener {
            speakPose("💧 Drink a refreshing glass of water now!", DevCompanionCanvasView.Expression.HAPPY_WATER, DevCompanionCanvasView.DisplayMode.FULL_BODY)
        }

        btnPoseEyes.setOnClickListener {
            speakPose("👀 Rest your eyes: blink and look 20 ft away!", DevCompanionCanvasView.Expression.TIRED_EYES, DevCompanionCanvasView.DisplayMode.FULL_BODY)
        }

        btnPoseTask.setOnClickListener {
            speakPose("📋 Focus time! Check your work reminders!", DevCompanionCanvasView.Expression.TASK_REMINDER, DevCompanionCanvasView.DisplayMode.FULL_BODY)
        }

        btnPoseThread.setOnClickListener {
            speakPose("🪢 Dropping down to say hi!", DevCompanionCanvasView.Expression.NORMAL, DevCompanionCanvasView.DisplayMode.THREAD_DROP)
        }

        btnTestCompanionNow.setOnClickListener {
            checkOverlayAndRun {
                OverlayService.startWithInitialDemo(this)
                Toast.makeText(this, "✨ Companion appearing on your screen!", Toast.LENGTH_SHORT).show()
            }
        }

        // Add Task Reminder
        btnAddNewTask.setOnClickListener { showAddTaskDialog() }

        // Test Companion Now
        btnTestCompanionNow.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Enable 'Display over other apps' first!", Toast.LENGTH_SHORT).show()
                launchOverlaySettings()
                return@setOnClickListener
            }
            OverlayService.startWithInitialDemo(this)
            Toast.makeText(this, "✨ myfrnd is appearing now!", Toast.LENGTH_SHORT).show()
        }

        switchEnableCompanion.setOnCheckedChangeListener { _, isChecked ->
            CompanionPreferences.setCompanionEnabled(this, isChecked)
            if (isChecked) {
                if (Settings.canDrawOverlays(this)) {
                    OverlayService.updateConfiguration(this)
                }
            } else {
                val stopIntent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_STOP_SERVICE
                }
                startService(stopIntent)
            }
            updateHeroSubtitle()
            updateEngineStatusBadge()
        }
    }

    private fun loadPreferencesIntoUi() {
        val eyeMin = CompanionPreferences.getEyeStrainMinutes(this)
        selectEyeInterval(eyeMin, persist = false)

        val waterMin = CompanionPreferences.getWaterIntervalMinutes(this)
        selectWaterInterval(waterMin, persist = false)

        val posMode = CompanionPreferences.getPositionMode(this)
        selectPositionMode(posMode, persist = false)

        switchEnableCompanion.isChecked = CompanionPreferences.isCompanionEnabled(this)
        updateHeroSubtitle()
    }

    private fun updateHeroSubtitle() {
        val isEnabled = CompanionPreferences.isCompanionEnabled(this)
        if (!isEnabled) {
            tvHeroSubtitle.text = "Paused"
            tvHeroSubtitle.setTextColor(Color.parseColor("#64748B"))
        } else {
            val eyeMin = CompanionPreferences.getEyeStrainMinutes(this)
            val waterMin = CompanionPreferences.getWaterIntervalMinutes(this)
            tvHeroSubtitle.text = "Active • Eye break at ${eyeMin}m, Water at ${waterMin}m"
            tvHeroSubtitle.setTextColor(Color.parseColor("#38BDF8"))
        }
    }

    private fun selectEyeInterval(minutes: Int, persist: Boolean = true) {
        if (persist) {
            CompanionPreferences.setEyeStrainMinutes(this, minutes)
            OverlayService.updateConfiguration(this)
        }
        updateHeroSubtitle()

        val chips = listOf(chipEye15 to 15, chipEye20 to 20, chipEye30 to 30, chipEye45 to 45)
        for ((chip, min) in chips) {
            if (min == minutes) {
                chip.setBackgroundResource(R.drawable.bg_pill_active)
                chip.setTextColor(Color.WHITE)
            } else {
                chip.setBackgroundResource(R.drawable.bg_pill_inactive)
                chip.setTextColor(Color.parseColor("#475569"))
            }
        }
    }

    private fun selectWaterInterval(minutes: Int, persist: Boolean = true) {
        if (persist) {
            CompanionPreferences.setWaterIntervalMinutes(this, minutes)
            OverlayService.updateConfiguration(this)
        }
        updateHeroSubtitle()

        val chips = listOf(chipWater15 to 15, chipWater30 to 30, chipWater45 to 45, chipWater60 to 60)
        for ((chip, min) in chips) {
            if (min == minutes) {
                chip.setBackgroundResource(R.drawable.bg_pill_active)
                chip.setTextColor(Color.WHITE)
            } else {
                chip.setBackgroundResource(R.drawable.bg_pill_inactive)
                chip.setTextColor(Color.parseColor("#475569"))
            }
        }
    }

    private fun selectPositionMode(mode: String, persist: Boolean = true) {
        if (persist) {
            CompanionPreferences.setPositionMode(this, mode)
            OverlayService.updateConfiguration(this)
        }

        val chips = listOf(
            chipPosAuto to CompanionPreferences.POSITION_AUTO,
            chipPosThread to CompanionPreferences.POSITION_THREAD_DROP,
            chipPosTopRight to CompanionPreferences.POSITION_TOP_RIGHT,
            chipPosBottom to CompanionPreferences.POSITION_BOTTOM
        )
        for ((chip, m) in chips) {
            if (m == mode) {
                chip.setBackgroundResource(R.drawable.bg_pill_active)
                chip.setTextColor(Color.WHITE)
            } else {
                chip.setBackgroundResource(R.drawable.bg_pill_inactive)
                chip.setTextColor(Color.parseColor("#475569"))
            }
        }
    }

    private fun updatePermissionStatuses() {
        val hasOverlay = Settings.canDrawOverlays(this)

        if (hasOverlay) {
            llMandatoryOverlayBanner.visibility = View.GONE
            btnTestCompanionNow.isEnabled = true
            btnTestCompanionNow.alpha = 1.0f
            switchEnableCompanion.isEnabled = true
        } else {
            llMandatoryOverlayBanner.visibility = View.VISIBLE
            btnTestCompanionNow.isEnabled = false
            btnTestCompanionNow.alpha = 0.5f
            switchEnableCompanion.isEnabled = false
        }

        updateEngineStatusBadge()

        if (hasOverlay && CompanionPreferences.isCompanionEnabled(this)) {
            val startIntent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE_CONFIG
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startIntent)
            } else {
                startService(startIntent)
            }
        }
    }

    private fun updateEngineStatusBadge() {
        val isEnabled = CompanionPreferences.isCompanionEnabled(this)
        val hasOverlay = Settings.canDrawOverlays(this)

        if (isEnabled && hasOverlay) {
            tvEngineStatusBadge.text = "● Active"
            tvEngineStatusBadge.setTextColor(Color.parseColor("#34D399"))
        } else if (!hasOverlay) {
            tvEngineStatusBadge.text = "⚠ Setup Needed"
            tvEngineStatusBadge.setTextColor(Color.parseColor("#F87171"))
        } else {
            tvEngineStatusBadge.text = "○ Off"
            tvEngineStatusBadge.setTextColor(Color.parseColor("#94A3B8"))
        }
    }

    private fun showAddTaskDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_add_task)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val etTaskTitle = dialog.findViewById<EditText>(R.id.etTaskTitle)
        val pillIn1Hour = dialog.findViewById<TextView>(R.id.pillIn1Hour)
        val pillTodayEvening = dialog.findViewById<TextView>(R.id.pillTodayEvening)
        val pillTomorrowMorning = dialog.findViewById<TextView>(R.id.pillTomorrowMorning)
        val btnPickCustom = dialog.findViewById<Button>(R.id.btnPickCustomDateTime)
        val tvSelectedDateTime = dialog.findViewById<TextView>(R.id.tvSelectedDateTime)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelDialog)
        val btnSave = dialog.findViewById<Button>(R.id.btnSaveReminder)

        var chosenTimestamp = System.currentTimeMillis() + 60 * 60 * 1000L
        val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

        fun updateSelectedLabel() {
            tvSelectedDateTime.text = "Scheduled: ${dateFormat.format(Date(chosenTimestamp))}"
        }
        updateSelectedLabel()

        pillIn1Hour.setOnClickListener {
            chosenTimestamp = System.currentTimeMillis() + 60 * 60 * 1000L
            updateSelectedLabel()
        }

        pillTodayEvening.setOnClickListener {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 20)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            chosenTimestamp = cal.timeInMillis
            updateSelectedLabel()
        }

        pillTomorrowMorning.setOnClickListener {
            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }
            chosenTimestamp = cal.timeInMillis
            updateSelectedLabel()
        }

        btnPickCustom.setOnClickListener {
            val nowCal = Calendar.getInstance()
            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    TimePickerDialog(
                        this,
                        { _, hourOfDay, minute ->
                            val chosenCal = Calendar.getInstance().apply {
                                set(year, month, dayOfMonth, hourOfDay, minute, 0)
                            }
                            chosenTimestamp = chosenCal.timeInMillis
                            updateSelectedLabel()
                        },
                        nowCal.get(Calendar.HOUR_OF_DAY),
                        nowCal.get(Calendar.MINUTE),
                        false
                    ).show()
                },
                nowCal.get(Calendar.YEAR),
                nowCal.get(Calendar.MONTH),
                nowCal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val title = etTaskTitle.text.toString().trim()
            if (title.isEmpty()) {
                Toast.makeText(this, "Please enter a task!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val reminder = TaskReminder(title = title, timestampMillis = chosenTimestamp)
            TaskManager.addReminder(this, reminder)
            dialog.dismiss()
            renderScheduledTasks()
            Toast.makeText(this, "✓ Reminder saved for ${dateFormat.format(Date(chosenTimestamp))}", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun renderScheduledTasks() {
        val tasks = TaskManager.getPendingReminders(this)
        llTasksContainer.removeAllViews()

        if (tasks.isEmpty()) {
            tvEmptyTasks.visibility = View.VISIBLE
            return
        }
        tvEmptyTasks.visibility = View.GONE

        val dateFormat = SimpleDateFormat("EEE, MMM d • h:mm a", Locale.getDefault())
        val inflater = LayoutInflater.from(this)

        for (task in tasks) {
            val itemView = inflater.inflate(R.layout.item_task_reminder, llTasksContainer, false)
            val tvTitle = itemView.findViewById<TextView>(R.id.tvTaskTitle)
            val tvTime = itemView.findViewById<TextView>(R.id.tvTaskTime)
            val btnDelete = itemView.findViewById<TextView>(R.id.btnDeleteTask)

            tvTitle.text = task.title
            tvTime.text = dateFormat.format(Date(task.timestampMillis))

            btnDelete.setOnClickListener {
                TaskManager.removeReminder(this, task.id)
                renderScheduledTasks()
                Toast.makeText(this, "Reminder removed.", Toast.LENGTH_SHORT).show()
            }

            llTasksContainer.addView(itemView)
        }
    }
}
