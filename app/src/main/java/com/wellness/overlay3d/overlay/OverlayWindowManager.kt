package com.wellness.overlay3d.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.wellness.overlay3d.R
import com.wellness.overlay3d.util.CompanionPreferences
import java.util.Locale

class OverlayWindowManager(
    private val context: Context,
    private val onDismissed: () -> Unit = {}
) {
    enum class OverlayReason { WATER_REMINDER, EYE_STRAIN, WORK_TASK_REMINDER, GENERAL_TEST }
    enum class EntranceStyle { THREAD_DROP, LEFT_TO_RIGHT, RIGHT_TO_LEFT, TOP_RIGHT_PEEK, BOTTOM_SLIDE }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var character: DevCompanionCanvasView? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var closed = false
    private var dismissing = false
    private var generation = 0L
    private val timeout = Runnable { dismiss() }
    val isOverlayActive: Boolean get() = root != null

    fun refreshPreferences() {
        character?.reducedMotion = CompanionPreferences.isReducedMotion(context)
        if (!CompanionPreferences.isVoiceEnabled(context)) {
            tts?.stop()
            character?.isTalking = false
        } else {
            prepareVoice()
        }
    }

    private fun prepareVoice() {
        if (tts != null || closed || !CompanionPreferences.isVoiceEnabled(context)) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (closed) return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "Speech unavailable; reminders remain visible")
                return@TextToSpeech
            }
            val language = Locale.getDefault().language
            val localVoice = tts?.voices?.firstOrNull {
                !it.isNetworkConnectionRequired && it.locale.language == language
            }
            ttsReady = localVoice != null && tts?.setVoice(localVoice) == TextToSpeech.SUCCESS
            if (!ttsReady) Log.w(TAG, "No offline speech voice for the device language")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    handler.post {
                        if (utteranceId == generation.toString()) character?.isTalking = true
                    }
                }
                override fun onDone(utteranceId: String?) {
                    handler.post {
                        if (utteranceId == generation.toString()) character?.isTalking = false
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.w(TAG, "Speech playback failed; keeping the text reminder")
                    onDone(utteranceId)
                }
            })
        }
    }

    /** A task may only be acknowledged after its overlay was successfully attached. */
    fun showOverlay(
        reason: OverlayReason,
        spokenText: String,
        taskTitle: String? = null,
        forcedStyle: EntranceStyle? = null
    ): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || isOverlayActive) return false
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission is no longer available")
            return false
        }
        generation++
        dismissing = false
        val style = forcedStyle ?: when (CompanionPreferences.getPositionMode(context)) {
            CompanionPreferences.POSITION_THREAD_DROP -> EntranceStyle.THREAD_DROP
            CompanionPreferences.POSITION_LEFT_RIGHT -> EntranceStyle.LEFT_TO_RIGHT
            CompanionPreferences.POSITION_RIGHT_LEFT -> EntranceStyle.RIGHT_TO_LEFT
            CompanionPreferences.POSITION_TOP_RIGHT -> EntranceStyle.TOP_RIGHT_PEEK
            CompanionPreferences.POSITION_BOTTOM -> EntranceStyle.BOTTOM_SLIDE
            else -> EntranceStyle.entries.random()
        }
        val view = LayoutInflater.from(context).inflate(R.layout.view_overlay_character, null)
        val companion = view.findViewById<DevCompanionCanvasView>(R.id.devCompanionCanvasView)
        character = companion
        companion.apply {
            reducedMotion = CompanionPreferences.isReducedMotion(context)
            displayMode = when (style) {
                EntranceStyle.THREAD_DROP -> DevCompanionCanvasView.DisplayMode.THREAD_DROP
                EntranceStyle.TOP_RIGHT_PEEK -> DevCompanionCanvasView.DisplayMode.TOP_RIGHT_PEEK
                else -> DevCompanionCanvasView.DisplayMode.FULL_BODY
            }
            currentExpression = when (reason) {
                OverlayReason.WATER_REMINDER -> DevCompanionCanvasView.Expression.HAPPY_WATER
                OverlayReason.EYE_STRAIN -> DevCompanionCanvasView.Expression.TIRED_EYES
                OverlayReason.WORK_TASK_REMINDER -> DevCompanionCanvasView.Expression.TASK_REMINDER
                OverlayReason.GENERAL_TEST -> DevCompanionCanvasView.Expression.WINK_PLAYFUL
            }
            contentDescription = context.getString(R.string.dismiss_reminder)
            setOnClickListener { dismiss() }
        }
        val title = context.getString(when (reason) {
            OverlayReason.WATER_REMINDER -> R.string.water_title
            OverlayReason.EYE_STRAIN -> R.string.eye_title
            OverlayReason.WORK_TASK_REMINDER -> R.string.tasks_title
            OverlayReason.GENERAL_TEST -> R.string.companion_title
        })
        view.findViewById<TextView>(R.id.speechText).text =
            context.getString(R.string.overlay_message, title, taskTitle ?: spokenText)
        view.findViewById<View>(R.id.speechBubbleContainer).apply {
            visibility = View.VISIBLE
            contentDescription = context.getString(R.string.overlay_accessibility, title, taskTitle ?: spokenText)
            setOnClickListener { dismiss() }
        }
        val density = context.resources.displayMetrics.density
        val screen = context.resources.displayMetrics
        val width = (280 * density).toInt().coerceAtMost(screen.widthPixels)
        val height = (360 * density).toInt().coerceAtMost(screen.heightPixels - (48 * density).toInt())
        val params = WindowManager.LayoutParams(width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = when (style) {
                EntranceStyle.THREAD_DROP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                EntranceStyle.TOP_RIGHT_PEEK -> Gravity.TOP or Gravity.END
                else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            y = (24 * density).toInt()
        }
        try {
            windowManager.addView(view, params)
        } catch (error: WindowManager.BadTokenException) {
            return attachmentFailed(error)
        } catch (error: SecurityException) {
            return attachmentFailed(error)
        }
        root = view
        prepareVoice()
        val animate = ValueAnimator.areAnimatorsEnabled() && !companion.reducedMotion
        if (animate) {
            view.alpha = 0f
            when (style) {
                EntranceStyle.THREAD_DROP, EntranceStyle.TOP_RIGHT_PEEK -> view.translationY = -height * 0.6f
                EntranceStyle.BOTTOM_SLIDE -> view.translationY = height * 0.6f
                EntranceStyle.LEFT_TO_RIGHT -> view.translationX = -width.toFloat()
                EntranceStyle.RIGHT_TO_LEFT -> view.translationX = width.toFloat()
            }
            view.animate().alpha(1f).translationX(0f).translationY(0f)
                .setDuration(650L).setInterpolator(DecelerateInterpolator())
                .withEndAction { speak(spokenText) }.start()
        } else {
            speak(spokenText)
        }
        // Text remains readable even when TTS is absent, fails, or never calls back.
        handler.postDelayed(timeout, (spokenText.length * 65L + 3_000L).coerceIn(7_000L, 18_000L))
        return true
    }

    private fun attachmentFailed(error: RuntimeException): Boolean {
        Log.e(TAG, "Could not attach reminder window", error)
        character = null
        return false
    }

    private fun speak(text: String) {
        character?.animationPhase = "talk"
        if (ttsReady && CompanionPreferences.isVoiceEnabled(context)) {
            if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, generation.toString()) == TextToSpeech.ERROR) {
                Log.w(TAG, "Speech request rejected; keeping the text reminder")
            }
        }
    }

    private fun dismiss() {
        val view = root ?: return
        if (dismissing) return
        dismissing = true
        handler.removeCallbacks(timeout)
        view.animate().cancel()
        tts?.stop()
        if (!ValueAnimator.areAnimatorsEnabled() || CompanionPreferences.isReducedMotion(context)) {
            finishDismissal()
        } else {
            view.animate().alpha(0f).setDuration(220L).withEndAction { finishDismissal() }.start()
        }
    }

    private fun finishDismissal() {
        destroyOverlayImmediately()
        onDismissed()
    }

    fun destroyOverlayImmediately() {
        generation++
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        root?.let { view ->
            view.animate().withEndAction(null).cancel()
            if (view.isAttachedToWindow) windowManager.removeViewImmediate(view)
        }
        root = null
        character = null
        dismissing = false
    }

    fun releaseTts() {
        closed = true
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private companion object {
        const val TAG = "OverlayWindowManager"
    }
}
