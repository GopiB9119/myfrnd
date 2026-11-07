package com.wellness.overlay3d.service

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wellness.overlay3d.R
import com.wellness.overlay3d.model.ReminderSchedule
import com.wellness.overlay3d.overlay.OverlayWindowManager
import com.wellness.overlay3d.ui.MainActivity
import com.wellness.overlay3d.util.CompanionPreferences
import com.wellness.overlay3d.util.TaskManager
import com.wellness.overlay3d.util.TaskStorageException

class OverlayService : Service() {
    companion object {
        private const val TAG = "OverlayService"
        const val NOTIFICATION_CHANNEL_ID = "myfrnd_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_DEMO_NOW = "com.myfrnd.app.ACTION_DEMO_NOW"
        const val ACTION_STOP_SERVICE = "com.myfrnd.app.ACTION_STOP_SERVICE"
        const val ACTION_UPDATE_CONFIG = "com.myfrnd.app.ACTION_UPDATE_CONFIG"
        const val ACTION_TRIGGER_WATER = "com.myfrnd.app.ACTION_TRIGGER_WATER"
        const val ACTION_TRIGGER_EYE_STRAIN = "com.myfrnd.app.ACTION_TRIGGER_EYE_STRAIN"

        fun startWithInitialDemo(context: Context) = start(context, ACTION_DEMO_NOW)
        fun updateConfiguration(context: Context) = start(context, ACTION_UPDATE_CONFIG)

        fun pause(context: Context) {
            CompanionPreferences.setCompanionEnabled(context, false)
            context.stopService(Intent(context, OverlayService::class.java))
            CompanionRuntime.publish(CompanionStatus())
        }

        private fun start(context: Context, action: String): Boolean {
            if (!CompanionPreferences.isCompanionEnabled(context) || !Settings.canDrawOverlays(context)) {
                CompanionRuntime.publish(CompanionStatus(error = R.string.setup_required))
                return false
            }
            return try {
                ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java).setAction(action))
                true
            } catch (error: IllegalStateException) {
                Log.e(TAG, "Android rejected foreground service startup", error)
                CompanionRuntime.publish(CompanionStatus(error = R.string.service_start_error))
                false
            } catch (error: SecurityException) {
                Log.e(TAG, "Foreground service permission denied", error)
                CompanionRuntime.publish(CompanionStatus(error = R.string.service_start_error))
                false
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var overlays: OverlayWindowManager
    private lateinit var schedule: ReminderSchedule
    private var screenActive = false
    private var destroyed = false
    private var runtimeError: Int? = null
    private var previewRequested = false
    private val evaluate = Runnable { evaluateReminders() }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (!CompanionPreferences.isCompanionEnabled(this)) {
            stopSelf()
        } else {
            schedule.configure(SystemClock.elapsedRealtime(),
                CompanionPreferences.getWaterIntervalMinutes(this),
                CompanionPreferences.getEyeStrainMinutes(this))
            overlays.refreshPreferences()
            requestEvaluation()
        }
    }
    private val taskListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> requestEvaluation() }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenActive = isScreenAvailable()
            schedule.screenChanged(SystemClock.elapsedRealtime(), screenActive)
            if (!screenActive) overlays.destroyOverlayImmediately()
            requestEvaluation()
        }
    }

    override fun onCreate() {
        super.onCreate()
        startServiceInForeground()
        screenActive = isScreenAvailable()
        schedule = ReminderSchedule(SystemClock.elapsedRealtime(),
            CompanionPreferences.getWaterIntervalMinutes(this),
            CompanionPreferences.getEyeStrainMinutes(this), screenActive)
        overlays = OverlayWindowManager(this) { requestEvaluation(500L) }
        overlays.refreshPreferences()
        CompanionPreferences.getPrefs(this).registerOnSharedPreferenceChangeListener(preferenceListener)
        TaskManager.preferences(this).registerOnSharedPreferenceChangeListener(taskListener)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            pause(this)
            return START_NOT_STICKY
        }
        if (!CompanionPreferences.isCompanionEnabled(this) || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DEMO_NOW) previewRequested = true
        if (screenActive && !overlays.isOverlayActive) {
            when (intent?.action) {
                ACTION_TRIGGER_WATER -> overlays.showOverlay(OverlayWindowManager.OverlayReason.WATER_REMINDER, getString(R.string.water_message))
                ACTION_TRIGGER_EYE_STRAIN -> overlays.showOverlay(OverlayWindowManager.OverlayReason.EYE_STRAIN, getString(R.string.eye_message))
            }
        }
        requestEvaluation()
        return START_STICKY
    }

    private fun isScreenAvailable(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun requestEvaluation(delay: Long = 0L) {
        if (destroyed) return
        handler.removeCallbacks(evaluate)
        handler.postDelayed(evaluate, delay)
    }

    private fun publishStatus() {
        CompanionRuntime.publish(CompanionStatus(
            running = true, screenActive = screenActive,
            nextWater = schedule.nextWater, nextEye = schedule.nextEye, error = runtimeError
        ))
    }

    private fun evaluateReminders() {
        if (!CompanionPreferences.isCompanionEnabled(this) || !Settings.canDrawOverlays(this)) {
            runtimeError = if (!Settings.canDrawOverlays(this)) R.string.setup_required else null
            stopSelf()
            return
        }
        publishStatus()
        if (!screenActive || overlays.isOverlayActive) return
        val now = SystemClock.elapsedRealtime()
        val wallTime = System.currentTimeMillis()
        val pending = try {
            TaskManager.getPendingReminders(this)
        } catch (error: TaskStorageException) {
            Log.e(TAG, "Reminders could not be loaded; saved data was not modified", error)
            runtimeError = R.string.task_storage_error
            publishStatus()
            requestEvaluation(60_000L)
            return
        }
        runtimeError = null
        val task = pending.firstOrNull()
        val shown = when {
            task != null && task.timestampMillis <= wallTime -> {
                overlays.showOverlay(OverlayWindowManager.OverlayReason.WORK_TASK_REMINDER,
                    getString(R.string.task_message, task.title), task.title).also { attached ->
                    if (attached) TaskManager.markDelivered(this, task.id)
                }
            }
            previewRequested -> {
                overlays.showOverlay(OverlayWindowManager.OverlayReason.GENERAL_TEST,
                    getString(R.string.demo_message)).also { if (it) previewRequested = false }
            }
            now >= schedule.nextWater -> {
                overlays.showOverlay(OverlayWindowManager.OverlayReason.WATER_REMINDER,
                    getString(R.string.water_message)).also { if (it) schedule.waterDelivered(now) }
            }
            schedule.nextEye?.let { now >= it } == true -> {
                overlays.showOverlay(OverlayWindowManager.OverlayReason.EYE_STRAIN,
                    getString(R.string.eye_message)).also { if (it) schedule.eyeDelivered(now) }
            }
            else -> false
        }
        publishStatus()
        if (!shown) {
            // A failed attachment is retried, never acknowledged as delivered.
            requestEvaluation(schedule.nextDelay(now, task?.timestampMillis?.minus(wallTime)).coerceAtLeast(1_000L))
        }
    }

    private fun startServiceInForeground() {
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.notification_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val open = PendingIntent.getActivity(this, 100, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 103,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP_SERVICE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_description))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.pause), stop)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(screenReceiver)
        CompanionPreferences.getPrefs(this).unregisterOnSharedPreferenceChangeListener(preferenceListener)
        TaskManager.preferences(this).unregisterOnSharedPreferenceChangeListener(taskListener)
        overlays.destroyOverlayImmediately()
        overlays.releaseTts()
        stopForeground(STOP_FOREGROUND_REMOVE)
        CompanionRuntime.publish(CompanionStatus(error = runtimeError))
        super.onDestroy()
    }
}
