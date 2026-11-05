package com.wellness.overlay3d.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.wellness.overlay3d.model.TaskReminder
import com.wellness.overlay3d.overlay.OverlayWindowManager
import com.wellness.overlay3d.util.CompanionPreferences
import com.wellness.overlay3d.util.TaskManager

/**
 * OverlayService: Background Foreground Engine for "myfrnd".
 *
 * Responsibilities:
 * 1. Persistent Foreground Service adhering to Android 14 (API 34) SPECIAL_USE policies.
 * 2. Precision screen-on duration tracking using SystemClock.elapsedRealtime().
 *    Fires an eye-strain warning when the display remains active continuously for the user-selected duration (default 15m).
 * 3. Schedules periodic water reminders.
 * 4. Checks and triggers user-scheduled work and task reminders.
 * 5. Supports dynamic testing triggers (Thread Drop, Walk, Corner Peek).
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        const val NOTIFICATION_CHANNEL_ID = "myfrnd_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_TRIGGER_WATER = "com.myfrnd.app.ACTION_TRIGGER_WATER"
        const val ACTION_TRIGGER_EYE_STRAIN = "com.myfrnd.app.ACTION_TRIGGER_EYE_STRAIN"
        const val ACTION_DEMO_NOW = "com.myfrnd.app.ACTION_DEMO_NOW"
        const val ACTION_TRIGGER_THREAD = "com.myfrnd.app.ACTION_TRIGGER_THREAD"
        const val ACTION_TRIGGER_WALK = "com.myfrnd.app.ACTION_TRIGGER_WALK"
        const val ACTION_TRIGGER_PEEK = "com.myfrnd.app.ACTION_TRIGGER_PEEK"
        const val ACTION_STOP_SERVICE = "com.myfrnd.app.ACTION_STOP_SERVICE"
        const val ACTION_UPDATE_CONFIG = "com.myfrnd.app.ACTION_UPDATE_CONFIG"

        private const val TICK_INTERVAL_MS: Long = 8_000L

        fun startWithInitialDemo(context: Context) {
            triggerAction(context, ACTION_DEMO_NOW)
        }

        fun triggerThreadDrop(context: Context) {
            triggerAction(context, ACTION_TRIGGER_THREAD)
        }

        fun triggerWalk(context: Context) {
            triggerAction(context, ACTION_TRIGGER_WALK)
        }

        fun triggerCornerPeek(context: Context) {
            triggerAction(context, ACTION_TRIGGER_PEEK)
        }

        fun triggerWaterTest(context: Context) {
            triggerAction(context, ACTION_TRIGGER_WATER)
        }

        fun triggerEyeTest(context: Context) {
            triggerAction(context, ACTION_TRIGGER_EYE_STRAIN)
        }

        fun updateConfiguration(context: Context) {
            triggerAction(context, ACTION_UPDATE_CONFIG)
        }

        private fun triggerAction(context: Context, actionStr: String) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = actionStr
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private lateinit var overlayWindowManager: OverlayWindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isScreenOn = true
    private var screenOnStartTimestamp: Long = SystemClock.elapsedRealtime()
    private var eyeStrainTriggeredForCurrentSession = false
    private var waterIntervalMs: Long = 30L * 60L * 1000L
    private var eyeStrainThresholdMs: Long = 15L * 60L * 1000L // 15 minutes default
    private var nextWaterReminderTimestamp: Long = System.currentTimeMillis() + waterIntervalMs

    private fun reloadPreferences() {
        val waterMin = CompanionPreferences.getWaterIntervalMinutes(this)
        val eyeMin = CompanionPreferences.getEyeStrainMinutes(this)
        waterIntervalMs = waterMin * 60L * 1000L
        eyeStrainThresholdMs = eyeMin * 60L * 1000L
        Log.i(TAG, "Config reloaded: Water = $waterMin min, Eye Strain = $eyeMin min")
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> handleScreenOn()
                Intent.ACTION_SCREEN_OFF -> handleScreenOff()
            }
        }
    }

    private val periodicTrackerRunnable = object : Runnable {
        override fun run() {
            evaluateScreenTime()
            evaluateWaterReminder()
            evaluateTaskReminders()
            mainHandler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        overlayWindowManager = OverlayWindowManager(this)
        reloadPreferences()
        startServiceInForeground()
        registerScreenStateReceiver()

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        isScreenOn = powerManager?.isInteractive ?: true
        screenOnStartTimestamp = SystemClock.elapsedRealtime()
        nextWaterReminderTimestamp = System.currentTimeMillis() + waterIntervalMs

        mainHandler.post(periodicTrackerRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE_CONFIG -> {
                reloadPreferences()
                nextWaterReminderTimestamp = System.currentTimeMillis() + waterIntervalMs
                eyeStrainTriggeredForCurrentSession = false
            }
            ACTION_TRIGGER_WATER -> {
                Log.i(TAG, "Triggering Water Reminder.")
                triggerWaterReminder()
            }
            ACTION_TRIGGER_EYE_STRAIN -> {
                Log.i(TAG, "Triggering Eye Strain Warning.")
                triggerEyeStrainWarning()
            }
            ACTION_TRIGGER_THREAD -> {
                Log.i(TAG, "Triggering Thread Drop Demo.")
                mainHandler.postDelayed({
                    overlayWindowManager.showOverlay(
                        OverlayWindowManager.OverlayReason.GENERAL_TEST,
                        "Whoa! Look who swung down on a thread! Here to keep you company!",
                        forcedStyle = OverlayWindowManager.EntranceStyle.THREAD_DROP
                    )
                }, 200L)
            }
            ACTION_TRIGGER_WALK -> {
                Log.i(TAG, "Triggering Walk Demo.")
                mainHandler.postDelayed({
                    overlayWindowManager.showOverlay(
                        OverlayWindowManager.OverlayReason.GENERAL_TEST,
                        "Hey friend! Stepping by to check in on you. Keep up the great work!",
                        forcedStyle = OverlayWindowManager.EntranceStyle.LEFT_TO_RIGHT
                    )
                }, 200L)
            }
            ACTION_TRIGGER_PEEK -> {
                Log.i(TAG, "Triggering Corner Peek Demo.")
                mainHandler.postDelayed({
                    overlayWindowManager.showOverlay(
                        OverlayWindowManager.OverlayReason.GENERAL_TEST,
                        "Psst! Peeking in from the top corner! Don't forget to take breaks!",
                        forcedStyle = OverlayWindowManager.EntranceStyle.TOP_RIGHT_PEEK
                    )
                }, 200L)
            }
            ACTION_DEMO_NOW -> {
                Log.i(TAG, "Scheduling initial verification demo in 1.5 seconds.")
                mainHandler.postDelayed({ triggerWaterReminder() }, 1500L)
            }
            ACTION_STOP_SERVICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startServiceInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "myfrnd Companion Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors eye strain, hydration & scheduled reminders."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val waterIntent = PendingIntent.getService(
            this, 101,
            Intent(this, OverlayService::class.java).apply { action = ACTION_TRIGGER_WATER },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val eyeIntent = PendingIntent.getService(
            this, 102,
            Intent(this, OverlayService::class.java).apply { action = ACTION_TRIGGER_EYE_STRAIN },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 103,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP_SERVICE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("myfrnd Active")
            .setContentText("Monitoring eye-strain, hydration & work tasks:")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_view, "💧 Drink", waterIntent)
            .addAction(android.R.drawable.ic_menu_compass, "👀 Eyes", eyeIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun registerScreenStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun handleScreenOn() {
        isScreenOn = true
        screenOnStartTimestamp = SystemClock.elapsedRealtime()
        eyeStrainTriggeredForCurrentSession = false
        Log.d(TAG, "Screen ON: Continuous timer started.")
    }

    private fun handleScreenOff() {
        isScreenOn = false
        eyeStrainTriggeredForCurrentSession = false
        Log.d(TAG, "Screen OFF.")
    }

    private fun evaluateScreenTime() {
        if (!isScreenOn) return
        val continuousActiveDuration = SystemClock.elapsedRealtime() - screenOnStartTimestamp
        if (continuousActiveDuration >= eyeStrainThresholdMs && !eyeStrainTriggeredForCurrentSession) {
            eyeStrainTriggeredForCurrentSession = true
            Log.i(TAG, "Continuous screen time threshold reached (${eyeStrainThresholdMs / 60000}m). Triggering eye strain warning.")
            triggerEyeStrainWarning()
        }
    }

    private fun evaluateWaterReminder() {
        val now = System.currentTimeMillis()
        if (now >= nextWaterReminderTimestamp) {
            nextWaterReminderTimestamp = now + waterIntervalMs
            Log.i(TAG, "Water timer elapsed. Triggering water reminder.")
            triggerWaterReminder()
        }
    }

    private fun evaluateTaskReminders() {
        val dueList = TaskManager.checkAndPopDueReminders(this)
        for (item in dueList) {
            triggerTaskReminder(item)
            break
        }
    }

    fun triggerEyeStrainWarning() {
        if (!Settings.canDrawOverlays(this)) return
        if (!CompanionPreferences.isCompanionEnabled(this)) return

        val min = eyeStrainThresholdMs / 60000
        overlayWindowManager.showOverlay(
            OverlayWindowManager.OverlayReason.EYE_STRAIN,
            "My eyes are burning! You've been scrolling for over $min minutes straight. Please stop and rest your eyes!"
        )
    }

    fun triggerWaterReminder() {
        if (!Settings.canDrawOverlays(this)) return
        if (!CompanionPreferences.isCompanionEnabled(this)) return

        overlayWindowManager.showOverlay(
            OverlayWindowManager.OverlayReason.WATER_REMINDER,
            "Hey, have you drank water yet? Take a sip to stay refreshed!"
        )
    }

    fun triggerTaskReminder(task: TaskReminder) {
        if (!Settings.canDrawOverlays(this)) return
        if (!CompanionPreferences.isCompanionEnabled(this)) return

        overlayWindowManager.showOverlay(
            OverlayWindowManager.OverlayReason.WORK_TASK_REMINDER,
            "Hey! Friendly reminder for your task: ${task.title}!",
            task.title
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(periodicTrackerRunnable)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        overlayWindowManager.destroyOverlayImmediately()
        overlayWindowManager.releaseTts()
    }
}
