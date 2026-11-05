package com.wellness.overlay3d.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import com.wellness.overlay3d.R
import com.wellness.overlay3d.filament.FilamentRenderer
import com.wellness.overlay3d.util.CompanionPreferences
import java.util.Locale
import kotlin.random.Random

/**
 * OverlayWindowManager: Coordinates transparent overlay window physics, entrance styles,
 * animations (Thread Drop, Left-Right, Right-Left, Corner Peek), TextToSpeech, and cleanup.
 */
class OverlayWindowManager(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "OverlayWindowManager"
        private const val SLIDE_ANIMATION_DURATION_MS = 1350L
        private const val SPEECH_UTTERANCE_ID = "MYFRND_SPEECH_UTTERANCE"
    }

    enum class OverlayReason {
        WATER_REMINDER,
        EYE_STRAIN,
        WORK_TASK_REMINDER,
        GENERAL_TEST
    }

    enum class EntranceStyle {
        THREAD_DROP,
        LEFT_TO_RIGHT,
        RIGHT_TO_LEFT,
        TOP_RIGHT_PEEK,
        BOTTOM_SLIDE
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayRootView: View? = null
    private var surfaceView: SurfaceView? = null
    private var devCompanionCanvasView: DevCompanionCanvasView? = null
    private var speechBubbleView: View? = null
    private var speechTextView: TextView? = null

    private var filamentRenderer: FilamentRenderer? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null

    private var isOverlayActive = false
    private var activeEntranceStyle = EntranceStyle.THREAD_DROP
    private var textToSpeech: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isTtsReady = false

    private val density = context.resources.displayMetrics.density
    private val screenWidthPixels = context.resources.displayMetrics.widthPixels

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let { tts ->
                val result = tts.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.language = Locale.getDefault()
                }
                tts.setPitch(1.06f)
                tts.setSpeechRate(0.96f)
                isTtsReady = true
                setupUtteranceListener()
            }
        } else {
            Log.w(TAG, "TTS initialization failed with code: $status")
        }
    }

    private fun setupUtteranceListener() {
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post { setTalkingState(true) }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.postDelayed({ slideOutAndDestroy() }, 700L)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post { slideOutAndDestroy() }
            }
        })
    }

    fun showOverlay(
        reason: OverlayReason,
        spokenText: String,
        taskTitle: String? = null,
        forcedStyle: EntranceStyle? = null
    ) {
        if (isOverlayActive) return
        isOverlayActive = true

        val modeSetting = CompanionPreferences.getPositionMode(context)
        activeEntranceStyle = forcedStyle ?: when (modeSetting) {
            CompanionPreferences.POSITION_THREAD_DROP -> EntranceStyle.THREAD_DROP
            CompanionPreferences.POSITION_LEFT_RIGHT -> EntranceStyle.LEFT_TO_RIGHT
            CompanionPreferences.POSITION_RIGHT_LEFT -> EntranceStyle.RIGHT_TO_LEFT
            CompanionPreferences.POSITION_TOP_RIGHT -> EntranceStyle.TOP_RIGHT_PEEK
            CompanionPreferences.POSITION_BOTTOM -> EntranceStyle.BOTTOM_SLIDE
            else -> {
                // RANDOM SURPRISE: Choose delightfully each time!
                when (Random.nextInt(4)) {
                    0 -> EntranceStyle.THREAD_DROP
                    1 -> EntranceStyle.LEFT_TO_RIGHT
                    2 -> EntranceStyle.RIGHT_TO_LEFT
                    else -> EntranceStyle.TOP_RIGHT_PEEK
                }
            }
        }

        mainHandler.post {
            try {
                inflateAndAttachOverlayView(reason)
                bindSurfaceAndFilament()
                slideInCharacter {
                    speakWarning(reason, spokenText, taskTitle)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error displaying overlay: ${t.message}", t)
                destroyOverlayImmediately()
            }
        }
    }

    private fun inflateAndAttachOverlayView(reason: OverlayReason) {
        val inflater = LayoutInflater.from(context)
        val root = inflater.inflate(R.layout.view_overlay_character, null)
        overlayRootView = root

        surfaceView = root.findViewById(R.id.filamentSurfaceView)
        devCompanionCanvasView = root.findViewById(R.id.devCompanionCanvasView)
        speechBubbleView = root.findViewById(R.id.speechBubbleContainer)
        speechTextView = root.findViewById(R.id.speechText)

        speechBubbleView?.visibility = View.INVISIBLE

        // Tap bubble or character to dismiss smoothly
        speechBubbleView?.setOnClickListener {
            slideOutAndDestroy()
        }
        devCompanionCanvasView?.onCompanionTappedListener = {
            mainHandler.postDelayed({ slideOutAndDestroy() }, 450L)
        }

        // Configure expression based on context
        devCompanionCanvasView?.currentExpression = when (reason) {
            OverlayReason.EYE_STRAIN -> DevCompanionCanvasView.Expression.TIRED_EYES
            OverlayReason.WATER_REMINDER -> DevCompanionCanvasView.Expression.HAPPY_WATER
            OverlayReason.WORK_TASK_REMINDER -> DevCompanionCanvasView.Expression.TASK_REMINDER
            OverlayReason.GENERAL_TEST -> {
                if (activeEntranceStyle == EntranceStyle.THREAD_DROP)
                    DevCompanionCanvasView.Expression.WINK_PLAYFUL
                else
                    DevCompanionCanvasView.Expression.NORMAL
            }
        }

        val overlayWidthPx: Int
        val overlayHeightPx: Int
        val layoutGravity: Int
        var startX = 0
        var startY = 0

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val windowFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        when (activeEntranceStyle) {
            EntranceStyle.THREAD_DROP -> {
                // Drop from very top suspended on thread
                devCompanionCanvasView?.displayMode = DevCompanionCanvasView.DisplayMode.THREAD_DROP
                overlayWidthPx = (270 * density).toInt()
                overlayHeightPx = (360 * density).toInt()
                layoutGravity = Gravity.TOP or Gravity.START
                startX = (screenWidthPixels - overlayWidthPx) / 2
                startY = -overlayHeightPx - (40 * density).toInt()
            }
            EntranceStyle.TOP_RIGHT_PEEK -> {
                // Peeking in from top-right corner looking at user
                devCompanionCanvasView?.displayMode = DevCompanionCanvasView.DisplayMode.TOP_RIGHT_PEEK
                overlayWidthPx = (240 * density).toInt()
                overlayHeightPx = (270 * density).toInt()
                layoutGravity = Gravity.TOP or Gravity.END
                startX = (16 * density).toInt()
                startY = -overlayHeightPx - (20 * density).toInt()
            }
            EntranceStyle.LEFT_TO_RIGHT -> {
                // Walk in from left edge
                devCompanionCanvasView?.displayMode = DevCompanionCanvasView.DisplayMode.FULL_BODY
                devCompanionCanvasView?.walkDirection = 1
                overlayWidthPx = (260 * density).toInt()
                overlayHeightPx = (340 * density).toInt()
                layoutGravity = Gravity.BOTTOM or Gravity.START
                startX = -overlayWidthPx - 60
                startY = (36 * density).toInt()
            }
            EntranceStyle.RIGHT_TO_LEFT, EntranceStyle.BOTTOM_SLIDE -> {
                // Walk in from right edge
                devCompanionCanvasView?.displayMode = DevCompanionCanvasView.DisplayMode.FULL_BODY
                devCompanionCanvasView?.walkDirection = -1
                overlayWidthPx = (260 * density).toInt()
                overlayHeightPx = (340 * density).toInt()
                layoutGravity = Gravity.BOTTOM or Gravity.START
                startX = screenWidthPixels + 60
                startY = (36 * density).toInt()
            }
        }

        val layoutParams = WindowManager.LayoutParams(
            overlayWidthPx,
            overlayHeightPx,
            windowType,
            windowFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = layoutGravity
            x = startX
            y = startY
        }

        surfaceView?.apply {
            setZOrderOnTop(true)
            holder.setFormat(PixelFormat.TRANSLUCENT)
        }

        windowLayoutParams = layoutParams
        windowManager.addView(root, layoutParams)
    }

    private fun bindSurfaceAndFilament() {
        val targetSurface = surfaceView ?: return
        val hasGlb = FilamentRenderer.hasGlbAsset(context)

        if (!hasGlb) {
            targetSurface.visibility = View.GONE
            devCompanionCanvasView?.visibility = View.VISIBLE
            return
        }

        targetSurface.visibility = View.VISIBLE
        devCompanionCanvasView?.visibility = View.GONE

        filamentRenderer = FilamentRenderer(context)
        targetSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                filamentRenderer?.onSurfaceCreated(holder.surface, targetSurface.width, targetSurface.height)
                filamentRenderer?.playAnimation("walk_in")
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                filamentRenderer?.onSurfaceChanged(w, h)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                filamentRenderer?.onSurfaceDestroyed()
            }
        })
    }

    private fun slideInCharacter(onComplete: () -> Unit) {
        val params = windowLayoutParams ?: return
        val root = overlayRootView ?: return

        setWalkingState(isWalkIn = true)

        when (activeEntranceStyle) {
            EntranceStyle.THREAD_DROP -> {
                // Drop down from top with elastic overshoot bounce
                val overlayHeightPx = (360 * density).toInt()
                val startY = -overlayHeightPx - (40 * density).toInt()
                val targetY = (36 * density).toInt()

                val animator = ValueAnimator.ofInt(startY, targetY).apply {
                    duration = 1300L
                    interpolator = OvershootInterpolator(1.15f)
                    addUpdateListener { va ->
                        params.y = va.animatedValue as Int
                        if (root.isAttachedToWindow) {
                            try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
                        }
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) { onComplete() }
                    })
                }
                animator.start()
            }
            EntranceStyle.TOP_RIGHT_PEEK -> {
                val overlayHeightPx = (270 * density).toInt()
                val startY = -overlayHeightPx - (20 * density).toInt()
                val targetY = (24 * density).toInt()

                val animator = ValueAnimator.ofInt(startY, targetY).apply {
                    duration = SLIDE_ANIMATION_DURATION_MS
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { va ->
                        params.y = va.animatedValue as Int
                        if (root.isAttachedToWindow) {
                            try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
                        }
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) { onComplete() }
                    })
                }
                animator.start()
            }
            EntranceStyle.LEFT_TO_RIGHT -> {
                val overlayWidthPx = (260 * density).toInt()
                val startX = -overlayWidthPx - 60
                val targetX = (screenWidthPixels - overlayWidthPx) / 2

                val animator = ValueAnimator.ofInt(startX, targetX).apply {
                    duration = SLIDE_ANIMATION_DURATION_MS
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { va ->
                        params.x = va.animatedValue as Int
                        if (root.isAttachedToWindow) {
                            try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
                        }
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) { onComplete() }
                    })
                }
                animator.start()
            }
            EntranceStyle.RIGHT_TO_LEFT, EntranceStyle.BOTTOM_SLIDE -> {
                val overlayWidthPx = (260 * density).toInt()
                val startX = screenWidthPixels + 60
                val targetX = (screenWidthPixels - overlayWidthPx) / 2

                val animator = ValueAnimator.ofInt(startX, targetX).apply {
                    duration = SLIDE_ANIMATION_DURATION_MS
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { va ->
                        params.x = va.animatedValue as Int
                        if (root.isAttachedToWindow) {
                            try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
                        }
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) { onComplete() }
                    })
                }
                animator.start()
            }
        }
    }

    private fun speakWarning(reason: OverlayReason, spokenText: String, taskTitle: String?) {
        val bubbleTitle = when (reason) {
            OverlayReason.WATER_REMINDER -> "💧 Drink Water!"
            OverlayReason.EYE_STRAIN -> "👀 Warm Eyes Alert!"
            OverlayReason.WORK_TASK_REMINDER -> "📋 Task Reminder!"
            OverlayReason.GENERAL_TEST -> "✨ Hey friend!"
        }

        val displayText = if (taskTitle != null) "$bubbleTitle\n$taskTitle" else "$bubbleTitle\n$spokenText"
        speechTextView?.text = displayText
        speechBubbleView?.alpha = 0f
        speechBubbleView?.visibility = View.VISIBLE
        speechBubbleView?.animate()?.alpha(1f)?.setDuration(260L)?.start()

        setTalkingState(true)

        if (isTtsReady && textToSpeech != null) {
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, SPEECH_UTTERANCE_ID)
            }
            textToSpeech?.speak(spokenText, TextToSpeech.QUEUE_FLUSH, params, SPEECH_UTTERANCE_ID)
        } else {
            mainHandler.postDelayed({ slideOutAndDestroy() }, 3800L)
        }
    }

    private fun slideOutAndDestroy() {
        val params = windowLayoutParams ?: run { destroyOverlayImmediately(); return }
        val root = overlayRootView ?: run { destroyOverlayImmediately(); return }

        speechBubbleView?.animate()?.alpha(0f)?.setDuration(200L)?.start()
        setWalkingState(isWalkIn = false)

        when (activeEntranceStyle) {
            EntranceStyle.THREAD_DROP -> {
                // Zip back up into the ceiling pulling the thread!
                val currentY = params.y
                val overlayHeightPx = (360 * density).toInt()
                val targetExitY = -overlayHeightPx - (50 * density).toInt()

                val animator = ValueAnimator.ofInt(currentY, targetExitY).apply {
                    duration = 1100L
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { va ->
                        params.y = va.animatedValue as Int
                        if (root.isAttachedToWindow) {
                            try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
                        }
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) { destroyOverlayImmediately() }
                    })
                }
                animator.start()
            }
            EntranceStyle.TOP_RIGHT_PEEK -> {
                val currentY = params.y
                val overlayHeightPx = (270 * density).toInt()
                val targetExitY = -overlayHeightPx - (30 * density).toInt()

                val animator = ValueAnimator.ofInt(currentY, targetExitY).apply {
                    duration = SLIDE_ANIMATION_DURATION_MS
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { va ->
                        params.y = va.animatedValue as Int
                        if (root.isAttachedToWindow) {
                            try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
                        }
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) { destroyOverlayImmediately() }
                    })
                }
                animator.start()
            }
            EntranceStyle.LEFT_TO_RIGHT -> {
                // Exits off-screen to the right!
                val currentX = params.x
                val targetExitX = screenWidthPixels + 80

                val animator = ValueAnimator.ofInt(currentX, targetExitX).apply {
                    duration = SLIDE_ANIMATION_DURATION_MS
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { va ->
                        params.x = va.animatedValue as Int
                        if (root.isAttachedToWindow) {
                            try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
                        }
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) { destroyOverlayImmediately() }
                    })
                }
                animator.start()
            }
            EntranceStyle.RIGHT_TO_LEFT, EntranceStyle.BOTTOM_SLIDE -> {
                // Exits off-screen to the left!
                val currentX = params.x
                val overlayWidthPx = (260 * density).toInt()
                val targetExitX = -(overlayWidthPx + 90)

                val animator = ValueAnimator.ofInt(currentX, targetExitX).apply {
                    duration = SLIDE_ANIMATION_DURATION_MS
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { va ->
                        params.x = va.animatedValue as Int
                        if (root.isAttachedToWindow) {
                            try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
                        }
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) { destroyOverlayImmediately() }
                    })
                }
                animator.start()
            }
        }
    }

    private fun setWalkingState(isWalkIn: Boolean) {
        val phase = if (isWalkIn) "walk_in" else "walk_out"
        filamentRenderer?.playAnimation(phase)
        devCompanionCanvasView?.apply {
            animationPhase = phase
            isTalking = false
        }
    }

    private fun setTalkingState(talking: Boolean) {
        if (talking) {
            filamentRenderer?.playAnimation("talk")
            devCompanionCanvasView?.apply {
                animationPhase = "talk"
                isTalking = true
            }
        } else {
            devCompanionCanvasView?.isTalking = false
        }
    }

    fun destroyOverlayImmediately() {
        mainHandler.post {
            try {
                if (overlayRootView?.isAttachedToWindow == true) {
                    windowManager.removeViewImmediate(overlayRootView)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error removing overlay view: ${e.message}")
            }

            try { filamentRenderer?.destroy() } catch (_: Throwable) {}
            filamentRenderer = null
            overlayRootView = null
            surfaceView = null
            devCompanionCanvasView = null
            speechBubbleView = null
            speechTextView = null
            windowLayoutParams = null
            isOverlayActive = false

            System.gc()
        }
    }

    fun releaseTts() {
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (_: Exception) {}
        textToSpeech = null
        isTtsReady = false
    }
}
