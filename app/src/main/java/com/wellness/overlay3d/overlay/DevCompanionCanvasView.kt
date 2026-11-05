package com.wellness.overlay3d.overlay

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.wellness.overlay3d.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * DevCompanionCanvasView: Ultra-Lifelike 3D Character Renderer for "myfrnd".
 *
 * Upgrades:
 * 1. Lifelike Organic Eye Blinking: Natural blink cycles (every ~3.5s) with realistic skin-tone eyelids.
 * 2. Breathing Idle Physics: Smooth chest expansion/contraction anchored at character base.
 * 3. Talking Phoneme Cadence: Speech head nods and sound-wave sparkles when speaking.
 * 4. Reactive Touch Interactivity: Tap the companion to trigger a joyful bounce, wink & haptic vibration!
 * 5. Dynamic Hydration Particle Engine: Ascending translucent water bubbles with shimmer refractions.
 * 6. Warm Eye Strain Radiance: Pulsing amber-red optic halos with tired half-closed eyelid squint.
 * 7. Thread Drop Suspension Physics: Elastic tension cord waves, pendulum swing, and grip sparks.
 */
class DevCompanionCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Expression {
        NORMAL,
        TIRED_EYES,
        HAPPY_WATER,
        TASK_REMINDER,
        WINK_PLAYFUL
    }

    enum class DisplayMode {
        FULL_BODY,
        TOP_RIGHT_PEEK,
        THREAD_DROP
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }

    private val cordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f
        color = Color.parseColor("#38BDF8")
        strokeCap = Paint.Cap.ROUND
    }

    private val cordGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7.5f
        color = Color.argb(85, 56, 189, 248)
        strokeCap = Paint.Cap.ROUND
    }

    // Realistic Eyelid, Lip & Lash Paints
    private val eyelidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9C6850") // Skin tone matching character render
        style = Paint.Style.FILL
    }

    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7A3E30") // Warm natural lip tone
        style = Paint.Style.FILL
    }

    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(68, 244, 63, 94) // Soft rosy cheek warmth
        style = Paint.Style.FILL
    }

    private val lashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2B1810") // Dark eyelash accent
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
    }

    private val speechWavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        color = Color.argb(160, 56, 189, 248)
    }

    private val startTimeMs = System.currentTimeMillis()
    private var tapBounceStartTimeMs: Long = 0L

    var animationPhase: String = "walk_in" // "walk_in", "talk", "walk_out"
    var isTalking: Boolean = false
    var currentExpression: Expression = Expression.NORMAL
    var displayMode: DisplayMode = DisplayMode.FULL_BODY
    var walkDirection: Int = 1 // 1 = right-facing, -1 = left-facing

    private var fullBodyBitmap: Bitmap? = null
    private var portraitBitmap: Bitmap? = null

    // Warm Eye Glow Colors
    private val warmEyeGlowInner = Color.argb(170, 249, 115, 22) // Radiant amber
    private val warmEyeGlowMid = Color.argb(90, 239, 68, 68)     // Soft heat-wave red
    private val warmEyeGlowOuter = Color.TRANSPARENT

    var onCompanionTappedListener: (() -> Unit)? = null

    init {
        loadCharacterBitmaps()
    }

    private fun loadCharacterBitmaps() {
        try {
            val fullBodyDrawable = ContextCompat.getDrawable(context, R.drawable.img_companion_fullbody)
            fullBodyBitmap = (fullBodyDrawable as? android.graphics.drawable.BitmapDrawable)?.bitmap

            val portraitDrawable = ContextCompat.getDrawable(context, R.drawable.img_companion_portrait)
            portraitBitmap = (portraitDrawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        } catch (_: Exception) {}
    }

    fun triggerTapReaction() {
        tapBounceStartTimeMs = System.currentTimeMillis()
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(30L)
            }
        } catch (_: Exception) {}
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            triggerTapReaction()
            onCompanionTappedListener?.invoke()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val t = (System.currentTimeMillis() - startTimeMs) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()

        if (w <= 0 || h <= 0) return

        when (displayMode) {
            DisplayMode.THREAD_DROP -> drawThreadDropPhotorealistic(canvas, w, h, t)
            DisplayMode.TOP_RIGHT_PEEK -> drawCornerPeekPhotorealistic(canvas, w, h, t)
            DisplayMode.FULL_BODY -> drawFullBodyPhotorealistic(canvas, w, h, t)
        }

        // 60 FPS smooth render loop
        postInvalidateOnAnimation()
    }

    /**
     * THREAD DROP: Character drops down from top of the screen suspended on a glowing tension cord!
     */
    private fun drawThreadDropPhotorealistic(canvas: Canvas, w: Float, h: Float, t: Float) {
        val bmp = fullBodyBitmap
        if (bmp == null) {
            drawFallbackPlaceholder(canvas, w, h, t)
            return
        }

        // Pendulum swing & vertical bob physics
        val pendulumAngle = sin(t * 3.4f) * 4.8f
        val verticalBob = sin(t * 3.4f * 2f) * (h * 0.015f)

        // Tap bounce reaction offset
        val tapOffset = getTapBounceOffset()

        canvas.save()
        // Anchor pendulum at top center of view
        canvas.translate(w / 2f, 0f)
        canvas.rotate(pendulumAngle)
        canvas.translate(-w / 2f, verticalBob - tapOffset)

        // Draw character hanging from suspension cord
        val bmpAspect = bmp.width.toFloat() / bmp.height.toFloat()
        val targetHeight = h * 0.88f
        val targetWidth = targetHeight * bmpAspect

        val left = (w - targetWidth) / 2f
        val top = h * 0.12f
        val right = left + targetWidth
        val bottom = top + targetHeight

        // Hand holding cord point
        val handX = left + targetWidth * 0.32f
        val handY = top + targetHeight * 0.38f

        // Draw glowing suspension cord with harmonic wave vibration
        val cordWave = sin(t * 6.5f) * 2.2f
        val midX = (w / 2f + handX) / 2f + cordWave
        val midY = handY / 2f

        val cordPath = Path().apply {
            moveTo(w / 2f, 0f)
            quadTo(midX, midY, handX, handY)
        }
        canvas.drawPath(cordPath, cordGlowPaint)
        canvas.drawPath(cordPath, cordPaint)

        // Grip sparkle at hand contact
        val sparkGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((180 + sin(t * 8f) * 60).toInt().coerceIn(120, 255), 255, 255, 255)
        }
        canvas.drawCircle(handX, handY, 4.5f, sparkGlow)

        // Breathing micro-scale
        val breath = sin(t * 2.2f) * 0.012f
        canvas.save()
        canvas.scale(1.0f + breath * 0.35f, 1.0f + breath, left + targetWidth / 2f, bottom)

        // Draw 3D Character
        val dstRect = RectF(left, top, right, bottom)
        canvas.drawBitmap(bmp, null, dstRect, paint)

        // Draw Lifelike Eyelids & Expressions
        drawLifelikeEyelids(canvas, left, top, targetWidth, targetHeight, t, isPortrait = false)
        drawExpressionEffects(canvas, left, top, targetWidth, targetHeight, t, isPortrait = false)

        canvas.restore()
        canvas.restore()
    }

    private fun drawFullBodyPhotorealistic(canvas: Canvas, w: Float, h: Float, t: Float) {
        val bmp = fullBodyBitmap
        if (bmp == null) {
            drawFallbackPlaceholder(canvas, w, h, t)
            return
        }

        val isWalking = (animationPhase == "walk_in" || animationPhase == "walk_out")
        val strideRate = 7.2f

        val bounce = if (isWalking) abs(cos(t * strideRate)) * (h * 0.024f) else sin(t * 2.2f) * (h * 0.007f)
        val sway = if (isWalking) sin(t * strideRate) * (w * 0.014f) else sin(t * 1.8f) * (w * 0.004f)

        val (tapOffsetY, tapScaleX, tapScaleY) = getTapPhysics()

        // 1. Soft Dynamic Contact Floor Shadow (Warm ambient tone blending with #FCF5F5)
        val shadowW = w * 0.38f + (if (isWalking) sin(t * strideRate * 2f) * (w * 0.02f) else 0f)
        val shadowH = h * 0.04f
        val shadowY = h * 0.94f
        val shadowAlpha = if (isWalking) (65 - abs(cos(t * strideRate)) * 18).toInt() else 60

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w / 2f + sway, shadowY, shadowW,
                intArrayOf(Color.argb(shadowAlpha, 90, 60, 70), Color.argb(shadowAlpha / 2, 100, 70, 80), Color.TRANSPARENT),
                floatArrayOf(0.0f, 0.55f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawOval(RectF(w / 2f + sway - shadowW, shadowY - shadowH, w / 2f + sway + shadowW, shadowY + shadowH), shadowPaint)

        // 2. Draw 3D Full-Body Character Person
        canvas.save()
        if (walkDirection < 0) {
            canvas.scale(-1f, 1f, w / 2f, h / 2f)
        }

        val bmpAspect = bmp.width.toFloat() / bmp.height.toFloat()
        val targetHeight = h * 0.96f
        val targetWidth = targetHeight * bmpAspect

        val left = (w - targetWidth) / 2f + sway
        val top = (h - targetHeight) - bounce - tapOffsetY
        val right = left + targetWidth
        val bottom = top + targetHeight

        // Breathing idle physics with subtle micro-tilt and organic squash/stretch
        val breath = if (!isWalking) sin(t * 2.2f) * 0.012f else 0f
        val microTilt = if (!isWalking) sin(t * 1.5f) * 0.75f else sin(t * strideRate) * 1.4f
        canvas.save()
        canvas.rotate(microTilt, left + targetWidth / 2f, bottom)
        canvas.scale((1.0f + breath * 0.4f) * tapScaleX, (1.0f + breath) * tapScaleY, left + targetWidth / 2f, bottom)

        val dstRect = RectF(left, top, right, bottom)
        canvas.drawBitmap(bmp, null, dstRect, paint)

        // 3. Draw Lifelike Eyelids, Cheeks, Mouth & Expressions
        drawLifelikeEyelids(canvas, left, top, targetWidth, targetHeight, t, isPortrait = false)
        drawExpressionEffects(canvas, left, top, targetWidth, targetHeight, t, isPortrait = false)

        canvas.restore()
        canvas.restore()
    }

    private fun drawCornerPeekPhotorealistic(canvas: Canvas, w: Float, h: Float, t: Float) {
        val bmp = portraitBitmap ?: fullBodyBitmap
        if (bmp == null) {
            drawFallbackPlaceholder(canvas, w, h, t)
            return
        }

        val floatSway = sin(t * 2.4f) * (h * 0.015f)
        val nod = if (isTalking) sin(t * 6.5f) * (h * 0.010f) else 0f
        val tapOffset = getTapBounceOffset()

        canvas.save()
        canvas.translate(0f, floatSway + nod - tapOffset)

        val bmpAspect = bmp.width.toFloat() / bmp.height.toFloat()
        val targetHeight = h * 1.05f
        val targetWidth = targetHeight * bmpAspect

        val left = (w - targetWidth) / 2f
        val top = 0f
        val right = left + targetWidth
        val bottom = top + targetHeight

        // Gentle breathing scale and subtle float tilt
        val breath = sin(t * 2.1f) * 0.010f
        val peekTilt = sin(t * 1.8f) * 0.9f
        canvas.save()
        canvas.rotate(peekTilt, left + targetWidth / 2f, bottom)
        canvas.scale(1.0f + breath * 0.3f, 1.0f + breath, left + targetWidth / 2f, bottom)

        val dstRect = RectF(left, top, right, bottom)
        canvas.drawBitmap(bmp, null, dstRect, paint)

        drawLifelikeEyelids(canvas, left, top, targetWidth, targetHeight, t, isPortrait = true)
        drawExpressionEffects(canvas, left, top, targetWidth, targetHeight, t, isPortrait = true)

        canvas.restore()
        canvas.restore()
    }

    /**
     * LIFELIKE ORGANIC EYELID BLINKING:
     * Natural human blinks occur every 3.5s and last ~0.15s.
     * When tired (TIRED_EYES), eyes squint in relaxed rest posture.
     * When playful (WINK_PLAYFUL), one eye winks with confident charm.
     */
    private fun drawLifelikeEyelids(
        canvas: Canvas,
        left: Float,
        top: Float,
        targetWidth: Float,
        targetHeight: Float,
        t: Float,
        isPortrait: Boolean
    ) {
        val leftEyeX = left + targetWidth * 0.474f
        val rightEyeX = left + targetWidth * 0.516f
        val eyeY = top + (if (isPortrait) targetHeight * 0.282f else targetHeight * 0.145f)
        val eyeRadiusX = targetWidth * (if (isPortrait) 0.024f else 0.018f)
        val eyeRadiusY = targetWidth * (if (isPortrait) 0.015f else 0.011f)

        // Natural blink cadence with authentic occasional double-blinks
        val blinkCycle = (t % 3.8f)
        val isSingleBlink = blinkCycle < 0.16f
        val singleBlink = if (isSingleBlink) sin((blinkCycle / 0.16f) * Math.PI.toFloat()) else 0f

        val doubleCycle = (t % 7.6f)
        val isDoubleBlink = doubleCycle < 0.34f
        val doubleBlink = if (isDoubleBlink) {
            if (doubleCycle < 0.14f) sin((doubleCycle / 0.14f) * Math.PI.toFloat())
            else if (doubleCycle > 0.20f) sin(((doubleCycle - 0.20f) / 0.14f) * Math.PI.toFloat())
            else 0f
        } else 0f

        val naturalBlink = maxOf(singleBlink, doubleBlink)

        var leftBlink = naturalBlink
        var rightBlink = naturalBlink

        when (currentExpression) {
            Expression.TIRED_EYES -> {
                // Chronic tired squint (60% closed + slow heavy blinks)
                val heavyBlink = 0.55f + abs(sin(t * 2.0f)) * 0.25f
                leftBlink = heavyBlink.coerceIn(0f, 1f)
                rightBlink = heavyBlink.coerceIn(0f, 1f)
            }
            Expression.WINK_PLAYFUL -> {
                // Right eye winks confidently while left stays wide open
                rightBlink = 0.95f
                leftBlink = naturalBlink * 0.3f
            }
            else -> {}
        }

        // Draw left eyelid if closing
        if (leftBlink > 0.1f) {
            drawSingleEyelid(canvas, leftEyeX, eyeY, eyeRadiusX, eyeRadiusY, leftBlink)
        }

        // Draw right eyelid if closing
        if (rightBlink > 0.1f) {
            drawSingleEyelid(canvas, rightEyeX, eyeY, eyeRadiusX, eyeRadiusY, rightBlink)
        }
    }

    private fun drawSingleEyelid(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, progress: Float) {
        val lidHeight = ry * 2f * progress
        val rect = RectF(cx - rx, cy - ry, cx + rx, cy - ry + lidHeight)

        canvas.save()
        // Clip oval around eye socket
        val socketPath = Path().apply {
            addOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW)
        }
        canvas.clipPath(socketPath)

        // Skin-tone lid fill
        canvas.drawOval(rect, eyelidPaint)

        // Eyelash contour line at bottom of eyelid
        canvas.drawLine(cx - rx * 0.85f, cy - ry + lidHeight, cx + rx * 0.85f, cy - ry + lidHeight, lashPaint)
        canvas.restore()
    }

    private fun drawExpressionEffects(
        canvas: Canvas,
        left: Float,
        top: Float,
        targetWidth: Float,
        targetHeight: Float,
        t: Float,
        isPortrait: Boolean
    ) {
        val eyeCenterX = left + targetWidth * 0.495f
        val eyeCenterY = top + (if (isPortrait) targetHeight * 0.282f else targetHeight * 0.145f)
        val eyeRadius = targetWidth * (if (isPortrait) 0.09f else 0.075f)

        when (currentExpression) {
            Expression.TIRED_EYES -> {
                // WARM EYE STRAIN EFFECT: Glowing amber/red heat radiance
                drawEyeStrainGlow(canvas, eyeCenterX, eyeCenterY, eyeRadius, t)
            }
            Expression.HAPPY_WATER -> {
                // HYDRATION PARTICLES: Rising crystalline water bubbles with light refractions
                drawHydrationParticles(canvas, eyeCenterX, eyeCenterY, targetWidth, targetHeight, t)
                drawRosyCheekBlush(canvas, left + targetWidth * 0.445f, left + targetWidth * 0.545f, eyeCenterY + targetHeight * 0.038f, targetWidth * 0.024f)
            }
            Expression.WINK_PLAYFUL -> {
                // PLAYFUL WINK: Soft star catchlight & rosy blush
                drawPlayfulCatchlight(canvas, eyeCenterX + eyeRadius * 0.85f, eyeCenterY, t)
                drawRosyCheekBlush(canvas, left + targetWidth * 0.445f, left + targetWidth * 0.545f, eyeCenterY + targetHeight * 0.038f, targetWidth * 0.024f)
            }
            Expression.TASK_REMINDER -> {
                // TASK FOCUS BEACON: Pulsing golden alert ring above head
                drawTaskBeacon(canvas, left + targetWidth * 0.5f, top + 10f, t)
            }
            Expression.NORMAL -> {}
        }

        // Animated talking mouth synced to voice phonemes
        if (isTalking) {
            val mouthY = eyeCenterY + (if (isPortrait) targetHeight * 0.088f else targetHeight * 0.046f)
            drawTalkingMouth(canvas, left + targetWidth * 0.495f, mouthY, targetWidth * (if (isPortrait) 0.034f else 0.022f), t)
            drawSpeechWaves(canvas, left + targetWidth * 0.65f, eyeCenterY + (if (isPortrait) 40f else 22f), t)
        }

        // Tap celebration sparkles
        drawTapSparkles(canvas, eyeCenterX, eyeCenterY - (if (isPortrait) 25f else 15f))
    }

    private fun drawEyeStrainGlow(canvas: Canvas, eyeCenterX: Float, eyeCenterY: Float, eyeRadius: Float, t: Float) {
        val pulse = abs(sin(t * 3.6f)) * (eyeRadius * 0.32f)
        val radius = eyeRadius + pulse

        val leftEyeX = eyeCenterX - eyeRadius * 0.85f
        val rightEyeX = eyeCenterX + eyeRadius * 0.85f

        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                leftEyeX, eyeCenterY, radius,
                intArrayOf(warmEyeGlowInner, warmEyeGlowMid, warmEyeGlowOuter),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(leftEyeX, eyeCenterY, radius, glowPaint)

        glowPaint.shader = RadialGradient(
            rightEyeX, eyeCenterY, radius,
            intArrayOf(warmEyeGlowInner, warmEyeGlowMid, warmEyeGlowOuter),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(rightEyeX, eyeCenterY, radius, glowPaint)
    }

    /**
     * DYNAMIC HYDRATION PARTICLE ENGINE:
     * Ascending water bubbles with variable alpha, size & refraction sparkles!
     */
    private fun drawHydrationParticles(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        width: Float,
        height: Float,
        t: Float
    ) {
        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255)
        }

        for (i in 0 until 5) {
            val cycle = (t * 0.65f + i * 0.4f) % 1.0f
            val px = cx + sin(cycle * 4.5f + i * 1.5f) * (width * 0.34f)
            val py = (cy + height * 0.25f) - (cycle * height * 0.55f)
            val r = (3.5f + i * 1.2f) * (1f - cycle * 0.3f)
            val alpha = ((1f - cycle) * 190).toInt().coerceIn(0, 255)

            // Outer bubble glow
            bubblePaint.color = Color.argb(alpha, 56, 189, 248)
            canvas.drawCircle(px, py, r, bubblePaint)

            // Inner reflection highlight
            if (r > 3f) {
                canvas.drawCircle(px - r * 0.35f, py - r * 0.35f, r * 0.28f, highlightPaint)
            }
        }
    }

    private fun drawPlayfulCatchlight(canvas: Canvas, x: Float, y: Float, t: Float) {
        val catchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255)
        }
        val r = 4.2f + abs(sin(t * 5f)) * 2f
        canvas.drawCircle(x, y, r, catchPaint)
    }

    private fun drawTaskBeacon(canvas: Canvas, x: Float, y: Float, t: Float) {
        val beaconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(140, 245, 158, 11)
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        val beaconRadius = 12f + abs(sin(t * 3.2f)) * 8f
        canvas.drawCircle(x, y, beaconRadius, beaconPaint)
    }

    private fun drawSpeechWaves(canvas: Canvas, x: Float, y: Float, t: Float) {
        for (i in 1..2) {
            val progress = ((t * 2.8f + i * 0.5f) % 1.0f)
            val r = 6f + progress * 16f
            val alpha = ((1f - progress) * 160).toInt().coerceIn(0, 255)
            speechWavePaint.color = Color.argb(alpha, 56, 189, 248)
            canvas.drawArc(RectF(x - r, y - r, x + r, y + r), -45f, 90f, false, speechWavePaint)
        }
    }

    private fun drawTapSparkles(canvas: Canvas, cx: Float, cy: Float) {
        if (tapBounceStartTimeMs <= 0L) return
        val elapsed = (System.currentTimeMillis() - tapBounceStartTimeMs) / 1000f
        if (elapsed > 0.65f) return
        val progress = elapsed / 0.65f
        val alpha = ((1f - progress) * 230).toInt().coerceIn(0, 255)

        val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        val angles = floatArrayOf(0.6f, 1.4f, 2.3f, 3.7f, 4.8f, 5.7f)
        val colors = intArrayOf(
            Color.argb(alpha, 250, 204, 21),  // Warm gold
            Color.argb(alpha, 52, 211, 153),  // Emerald mint
            Color.argb(alpha, 56, 189, 248),  // Sky cyan
            Color.argb(alpha, 244, 114, 182), // Soft pink
            Color.argb(alpha, 250, 204, 21),  // Warm gold
            Color.argb(alpha, 52, 211, 153)   // Emerald mint
        )

        for (i in angles.indices) {
            val dist = 24f + progress * 50f
            val ang = angles[i] + progress * 0.35f
            val px = cx + cos(ang) * dist
            val py = cy - sin(ang) * dist * 0.9f
            val r = (4.5f * (1f - progress * 0.5f))

            starPaint.color = colors[i]
            val path = Path().apply {
                moveTo(px, py - r * 1.5f)
                quadTo(px, py, px + r * 1.5f, py)
                quadTo(px, py, px, py + r * 1.5f)
                quadTo(px, py, px - r * 1.5f, py)
                close()
            }
            canvas.drawPath(path, starPaint)
        }
    }

    private fun drawRosyCheekBlush(canvas: Canvas, leftX: Float, rightX: Float, cy: Float, radius: Float) {
        val blushRectL = RectF(leftX - radius, cy - radius * 0.65f, leftX + radius, cy + radius * 0.65f)
        val blushRectR = RectF(rightX - radius, cy - radius * 0.65f, rightX + radius, cy + radius * 0.65f)
        canvas.drawOval(blushRectL, blushPaint)
        canvas.drawOval(blushRectR, blushPaint)
    }

    private fun drawTalkingMouth(canvas: Canvas, cx: Float, cy: Float, rx: Float, t: Float) {
        // Natural phoneme aperture cadence
        val aperture = 0.18f + abs(sin(t * 11.5f)) * 0.55f
        val ry = rx * aperture
        val mouthRect = RectF(cx - rx, cy - ry, cx + rx, cy + ry)
        canvas.drawOval(mouthRect, mouthPaint)
    }

    data class TapPhysics(val offsetY: Float, val scaleX: Float, val scaleY: Float)

    private fun getTapPhysics(): TapPhysics {
        if (tapBounceStartTimeMs <= 0L) return TapPhysics(0f, 1f, 1f)
        val elapsedSec = (System.currentTimeMillis() - tapBounceStartTimeMs) / 1000f
        if (elapsedSec > 0.52f) {
            tapBounceStartTimeMs = 0L
            return TapPhysics(0f, 1f, 1f)
        }

        // Pixar-style squash & stretch spring leap physics
        return when {
            elapsedSec < 0.10f -> {
                val p = elapsedSec / 0.10f
                val squash = sin(p * Math.PI.toFloat()) * 0.06f
                TapPhysics(0f, 1f + squash, 1f - squash * 1.2f)
            }
            elapsedSec < 0.35f -> {
                val p = (elapsedSec - 0.10f) / 0.25f
                val jump = sin(p * Math.PI.toFloat())
                val stretch = jump * 0.07f
                TapPhysics(jump * 22f, 1f - stretch * 0.7f, 1f + stretch)
            }
            else -> {
                val p = (elapsedSec - 0.35f) / 0.17f
                val settle = sin(p * Math.PI.toFloat()) * 0.035f
                TapPhysics(0f, 1f + settle, 1f - settle)
            }
        }
    }

    private fun getTapBounceOffset(): Float {
        return getTapPhysics().offsetY
    }

    private fun drawFallbackPlaceholder(canvas: Canvas, w: Float, h: Float, t: Float) {
        val bounce = sin(t * 2.5f) * 4f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#10B981")
            textAlign = Paint.Align.CENTER
            textSize = 28f
        }
        canvas.drawCircle(w / 2f, h / 2f + bounce, 48f, p)
    }
}
