package com.wellness.overlay3d.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.opengl.Matrix
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.withTranslation
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.EntityManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.ToneMapper
import com.google.android.filament.TransformManager
import com.google.android.filament.View as FilamentView
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.Animator
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.wellness.overlay3d.R
import com.wellness.overlay3d.companion3d.AnimationDirector
import com.wellness.overlay3d.companion3d.CompanionEngine
import com.wellness.overlay3d.companion3d.CompanionJoint
import com.wellness.overlay3d.companion3d.CompanionMove
import com.wellness.overlay3d.companion3d.HumanRig
import com.wellness.overlay3d.companion3d.StudioLighting
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * The companion: a 3D human (assets/models/companion.glb) rendered with Filament into a
 * transparent TextureView, plus a 2D layer for wellness effects (water bubbles, focus beacon,
 * eye-strain glow, speech waves, tap sparkles, the thread cord) that tracks the head and hands of
 * the 3D body. Models with animation clips play them; still models (such as the bundled scan,
 * which only has an auto-generated Hips/Spine/Chest/Head rig) are animated procedurally.
 * Used by the dashboard hero card and by the reminder overlay window.
 *
 * Frames are only produced while the view is attached, visible, on screen and animating; with
 * reduced motion it renders a single still pose on demand.
 */
class HumanCompanionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class Expression { NORMAL, TIRED_EYES, HAPPY_WATER, TASK_REMINDER, WINK_PLAYFUL }
    enum class DisplayMode { FULL_BODY, TOP_RIGHT_PEEK, THREAD_DROP }
    enum class RenderState { LOADING, READY, FAILED }

    /** False while the host screen is paused; stops the render loop. */
    var animationEnabled = true
        set(value) {
            if (field == value) return
            field = value
            updateLoop()
        }

    var reducedMotion = false
        set(value) {
            if (field == value) return
            field = value
            applyExpressionMotion()
            requestRender()
        }

    var isTalking = false
        set(value) {
            if (field == value) return
            field = value
            requestRender()
        }

    /** Setting an expression (even the same one again) replays its gesture. */
    var currentExpression = Expression.NORMAL
        set(value) {
            field = value
            expressionStart = clock
            applyExpressionMotion()
            requestRender()
        }

    var displayMode = DisplayMode.FULL_BODY
        set(value) {
            if (field == value) return
            field = value
            applyExpressionMotion()
            requestRender()
        }

    /** [PHASE_WALK_IN] walks in profile towards [walkDirection]; anything else faces the user. */
    var animationPhase: String = PHASE_IDLE
        set(value) {
            if (field == value) return
            field = value
            applyExpressionMotion()
            requestRender()
        }

    /** 1 = walking to the right of the screen, -1 = to the left. */
    var walkDirection: Int = 1

    /** Lets the user spin the companion with a horizontal drag; it springs back afterwards. */
    var dragToRotate = false

    var onRenderStateChanged: ((RenderState) -> Unit)? = null

    var renderState = RenderState.LOADING
        private set

    @VisibleForTesting
    var framesRendered = 0L
        private set

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val shadowLayer = GroundShadowLayer(context)
    private val textureView = TextureView(context)
    private val effectsLayer = EffectsLayer(context)
    private val fallbackView = ImageView(context)
    private val loadingView = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(context.getColor(R.color.primary))
        alpha = 0.7f
    }

    // Filament objects; all non-null only between attach and detach.
    private var session: CompanionEngine.Session? = null
    private var uiHelper: UiHelper? = null
    private var renderer: Renderer? = null
    private var scene: Scene? = null
    private var filamentView: FilamentView? = null
    private var colorGrading: ColorGrading? = null
    private var camera: Camera? = null
    private var cameraEntity = 0
    private var keyLight = 0
    private var swapChain: SwapChain? = null
    private var asset: FilamentAsset? = null
    private var resourceLoader: ResourceLoader? = null
    private var animator: Animator? = null
    private var director: AnimationDirector? = null
    private var moves: Map<CompanionMove, Int> = emptyMap()
    private var modelReady = false
    private var attachGeneration = 0
    /** Accepted frames containing the body; it counts as visible after [FRAMES_UNTIL_VISIBLE]. */
    private var modelFramesSubmitted = 0

    private var headInstance = 0
    private var chestInstance = 0
    private var spineInstance = 0
    private var wristInstances = IntArray(0)
    private val headRest = FloatArray(16)
    private val chestRest = FloatArray(16)
    private val spineRest = FloatArray(16)
    /** True for still models (e.g. scans) without clips: the body is animated in code instead. */
    private var proceduralBody = false
    private val normalize = FloatArray(16)
    private val placement = FloatArray(16)
    private val rootMatrix = FloatArray(16)
    private val projection = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val scratchA = FloatArray(16)
    private val scratchB = FloatArray(16)
    private val scratchC = FloatArray(16)
    private val scratchVec = FloatArray(4)
    private val scratchOut = FloatArray(4)
    private val screenPoint = FloatArray(2)

    // Motion state, in seconds of animation time (frozen while motion is reduced).
    private var clock = 0f
    private var expressionStart = 0f
    private var tapStart = -10f
    private var lastFrameNanos = 0L
    private var facingYaw = 0f
    private var userYaw = 0f
    private var dragging = false
    private var downX = 0f
    private var lastTouchX = 0f

    // Render loop state.
    private var frameScheduled = false
    private var needsRedraw = true
    private var visibleAggregated = true
    private val visibleRect = Rect()
    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        frameScheduled = false
        onFrame(frameTimeNanos)
    }
    private val drawListener = ViewTreeObserver.OnDrawListener { updateLoop() }

    // Screen-space anchors of the 3D body, refreshed every frame for the 2D effects.
    private var anchorsValid = false
    private var faceX = 0f
    private var faceY = 0f
    private var headTopX = 0f
    private var headTopY = 0f
    private var chestY = 0f
    private var handX = 0f
    private var handY = 0f
    private var cordTopX = 0f
    private var groundX = 0f
    private var groundY = 0f
    private var groundHalfWidth = 0f
    private var pixelsPerMeter = 0f
    private var hopHeight = 0f

    // What the 2D layers last drew, so unchanged idle frames skip redrawing them.
    private var effectsDrawn = false
    private var shadowDrawnVisible = false
    private val shadowDrawn = FloatArray(4)

    init {
        isClickable = true
        isFocusable = true
        clipChildren = false
        textureView.isOpaque = false
        listOf(shadowLayer, textureView, effectsLayer, fallbackView).forEach {
            it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        fallbackView.setImageResource(R.drawable.ic_launcher_foreground)
        fallbackView.scaleType = ImageView.ScaleType.FIT_CENTER
        fallbackView.visibility = GONE
        val spinnerSize = (32 * density).toInt()
        loadingView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(loadingView, LayoutParams(spinnerSize, spinnerSize, Gravity.CENTER))
    }

    fun triggerTapReaction() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (motionAllowed) {
            tapStart = clock
            director?.let { if (!it.isPlayingOneShot) it.playOnce(clipFor(CompanionMove.WAVE)) }
        }
        requestRender()
    }

    // region lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachGeneration++
        textureView.visibility = VISIBLE
        effectsLayer.visibility = VISIBLE
        shadowLayer.visibility = VISIBLE
        fallbackView.visibility = GONE
        loadingView.visibility = VISIBLE
        setRenderState(RenderState.LOADING)
        if (!isHardwareAccelerated) {
            fail("window is not hardware accelerated")
            return
        }
        val generation = attachGeneration
        // Starting the engine costs a few hundred ms of main thread; let the first frame (with the
        // spinner) reach the screen before paying for it.
        post { if (generation == attachGeneration) startRenderer(generation) }
    }

    private fun startRenderer(generation: Int) {
        if (!setUpFilament()) {
            fail("3D renderer unavailable on this device")
            return
        }
        CompanionEngine.loadModel(context) { buffer ->
            if (generation == attachGeneration && session != null) onModelBytes(buffer)
        }
        // Scrolling a dashboard or sliding an overlay window in both end with a redraw.
        viewTreeObserver.addOnDrawListener(drawListener)
        requestRender()
    }

    override fun onDetachedFromWindow() {
        attachGeneration++
        viewTreeObserver.removeOnDrawListener(drawListener)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameScheduled = false
        tearDownFilament()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visibleAggregated = isVisible
        updateLoop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        requestRender()
    }

    private fun setUpFilament(): Boolean {
        val shared = CompanionEngine.acquire() ?: return false
        session = shared
        val engine = shared.engine
        renderer = engine.createRenderer().apply {
            setClearOptions(Renderer.ClearOptions().apply {
                clear = true
                clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
            })
        }
        val newScene = engine.createScene().apply { indirectLight = shared.indirectLight }
        keyLight = StudioLighting.createKeyLight(engine)
        newScene.addEntity(keyLight)
        scene = newScene
        cameraEntity = EntityManager.get().create()
        val newCamera = engine.createCamera(cameraEntity).apply {
            // Sunny-16 exposure, matching the physically based light intensities.
            setExposure(16f, 1f / 125f, 100f)
        }
        camera = newCamera
        val grading = ColorGrading.Builder()
            .toneMapper(ToneMapper.PBRNeutralToneMapper())
            .build(engine)
        colorGrading = grading
        filamentView = engine.createView().apply {
            this.scene = newScene
            this.camera = newCamera
            setBlendMode(FilamentView.BlendMode.TRANSLUCENT)
            setPostProcessingEnabled(true)
            setAntiAliasing(FilamentView.AntiAliasing.NONE)
            // Dithering noise would show up as speckles over the transparent window.
            setDithering(FilamentView.Dithering.NONE)
            setMultiSampleAntiAliasingOptions(FilamentView.MultiSampleAntiAliasingOptions().apply {
                enabled = true
                sampleCount = 4
            })
            setShadowingEnabled(false)
            setColorGrading(grading)
        }
        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
            renderCallback = SurfaceCallback()
            isOpaque = false
            attachTo(textureView)
        }
        return true
    }

    private fun tearDownFilament() {
        val shared = session ?: return
        val engine = shared.engine
        uiHelper?.detach()
        uiHelper = null
        swapChain?.let {
            engine.destroySwapChain(it)
            engine.flushAndWait()
        }
        swapChain = null
        val currentScene = scene
        resourceLoader?.asyncCancelLoad()
        asset?.let {
            currentScene?.removeEntities(it.entities)
            shared.assetLoader.destroyAsset(it)
        }
        resourceLoader?.destroy()
        resourceLoader = null
        asset = null
        animator = null
        director = null
        modelReady = false
        modelFramesSubmitted = 0
        anchorsValid = false
        effectsDrawn = false
        shadowDrawnVisible = false
        effectsLayer.invalidate()
        shadowLayer.invalidate()
        headInstance = 0
        wristInstances = IntArray(0)
        filamentView?.let { engine.destroyView(it) }
        filamentView = null
        // The engine is shared and outlives this view, so its colour-grading LUT must be freed here.
        colorGrading?.let { engine.destroyColorGrading(it) }
        colorGrading = null
        if (keyLight != 0) {
            currentScene?.removeEntity(keyLight)
            engine.destroyEntity(keyLight)
            EntityManager.get().destroy(keyLight)
            keyLight = 0
        }
        currentScene?.let { engine.destroyScene(it) }
        scene = null
        if (cameraEntity != 0) {
            engine.destroyCameraComponent(cameraEntity)
            EntityManager.get().destroy(cameraEntity)
            cameraEntity = 0
        }
        camera = null
        renderer?.let { engine.destroyRenderer(it) }
        renderer = null
        session = null
        CompanionEngine.release()
    }

    private inner class SurfaceCallback : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            val shared = session ?: return
            swapChain?.let { shared.engine.destroySwapChain(it) }
            swapChain = shared.engine.createSwapChain(surface, uiHelper?.swapChainFlags ?: 0L)
            requestRender()
        }

        override fun onDetachedFromSurface() {
            val shared = session ?: return
            swapChain?.let {
                shared.engine.destroySwapChain(it)
                // The surface is about to go away; Filament must be done with it first.
                shared.engine.flushAndWait()
            }
            swapChain = null
        }

        override fun onResized(width: Int, height: Int) {
            // The viewport follows the TextureView size in updateCamera(); UiHelper reports 0x0 here
            // when it attaches to a surface that already exists (the normal case for this view).
            requestRender()
        }
    }

    private fun fail(reason: String) {
        Log.w(TAG, "3D companion unavailable ($reason); showing the fallback")
        tearDownFilament()
        textureView.visibility = GONE
        effectsLayer.visibility = GONE
        shadowLayer.visibility = GONE
        fallbackView.visibility = VISIBLE
        setRenderState(RenderState.FAILED)
    }

    private fun setRenderState(state: RenderState) {
        if (renderState == state) return
        renderState = state
        loadingView.visibility = if (state == RenderState.LOADING) VISIBLE else GONE
        if (state == RenderState.READY) Log.i(TAG, "Companion on screen after $modelFramesSubmitted body frames")
        onRenderStateChanged?.invoke(state)
    }

    // endregion

    // region model loading

    private fun onModelBytes(buffer: ByteBuffer?) {
        val shared = session ?: return
        if (buffer == null) {
            fail("model asset missing")
            return
        }
        val created = shared.assetLoader.createAsset(buffer)
        if (created == null) {
            fail("model is not a valid glTF binary")
            return
        }
        asset = created
        val loader = ResourceLoader(shared.engine, true)
        resourceLoader = loader
        if (!loader.asyncBeginLoad(created)) {
            fail("model resources could not be decoded")
            return
        }
        requestRender()
    }

    /** Drives asynchronous texture/buffer upload one step per frame; true once the model is usable. */
    private fun pumpResourceLoading(): Boolean {
        if (modelReady) return true
        val loader = resourceLoader ?: return false
        val loaded = asset ?: return false
        loader.asyncUpdateLoad()
        if (loader.asyncGetLoadProgress() < 1f) return false
        onModelLoaded(loaded)
        return true
    }

    private fun onModelLoaded(loaded: FilamentAsset) {
        val engine = session?.engine ?: return
        val modelAnimator = loaded.instance.animator
        animator = modelAnimator

        val clipCount = modelAnimator.animationCount
        val names = List(clipCount) { modelAnimator.getAnimationName(it) }
        val durations = FloatArray(clipCount) { modelAnimator.getAnimationDuration(it) }
        moves = HumanRig.resolveMoves(names)
        director = AnimationDirector(durations)
        proceduralBody = moves.isEmpty()
        Log.i(TAG, "Companion model ready: clips=$names moves=$moves procedural=$proceduralBody")

        val tm = engine.transformManager
        val entities = loaded.entities
        val nodeNames = entities.map { loaded.getName(it) }
        fun jointInstance(joint: CompanionJoint, rest: FloatArray): Int {
            val index = HumanRig.findJoint(nodeNames, joint)
            val instance = if (index >= 0) tm.getInstance(entities[index]) else 0
            if (instance != 0) tm.getTransform(instance, rest)
            return instance
        }
        headInstance = jointInstance(CompanionJoint.HEAD, headRest)
        chestInstance = jointInstance(CompanionJoint.CHEST, chestRest)
        spineInstance = jointInstance(CompanionJoint.SPINE, spineRest)
        wristInstances = listOf(CompanionJoint.WRIST_LEFT, CompanionJoint.WRIST_RIGHT)
            .map { HumanRig.findJoint(nodeNames, it) }
            .filter { it >= 0 }
            .map { tm.getInstance(entities[it]) }
            .filter { it != 0 }
            .toIntArray()

        // Normalise any model to a 1.7 m person standing on y = 0, centred on the origin.
        val box = loaded.boundingBox
        val center = box.center
        val half = box.halfExtent
        val height = (half[1] * 2f).takeIf { it > 1e-4f } ?: 1f
        val scale = MODEL_HEIGHT / height
        Matrix.setIdentityM(normalize, 0)
        Matrix.scaleM(normalize, 0, scale, scale, scale)
        Matrix.translateM(normalize, 0, -center[0], -(center[1] - half[1]), -center[2])

        scene?.addEntities(entities)
        // Animator and names are captured above; the parsed glTF JSON is no longer needed.
        loaded.releaseSourceData()
        modelReady = true
        applyExpressionMotion()
    }

    private fun clipFor(move: CompanionMove) = HumanRig.clipFor(move, moves)

    // endregion

    // region behaviour

    private val motionAllowed: Boolean
        get() = !reducedMotion && ValueAnimator.areAnimatorsEnabled()

    private val walkingIn: Boolean
        get() = animationPhase == PHASE_WALK_IN && motionAllowed && displayMode == DisplayMode.FULL_BODY

    /** Walk-in with the model's own walk cycle. */
    private val isWalking: Boolean
        get() = walkingIn && clipFor(CompanionMove.WALK) >= 0

    /** Walk-in for models without a walk cycle (e.g. a still scan): bouncy steps, face still visible. */
    private val isStepping: Boolean
        get() = walkingIn && clipFor(CompanionMove.WALK) < 0

    /** Chooses body clips for the current expression / mode / phase. */
    private fun applyExpressionMotion() {
        val d = director ?: return
        if (isWalking) {
            d.setLoop(clipFor(CompanionMove.WALK))
            return
        }
        val loop = when {
            displayMode == DisplayMode.THREAD_DROP -> clipFor(CompanionMove.WAVE).takeIf { motionAllowed }
                ?: clipFor(CompanionMove.IDLE)
            currentExpression == Expression.TIRED_EYES -> clipFor(CompanionMove.IDLE_ALT)
            else -> clipFor(CompanionMove.IDLE)
        }
        val gesture = when {
            !motionAllowed || displayMode == DisplayMode.THREAD_DROP -> -1
            currentExpression == Expression.WINK_PLAYFUL -> clipFor(CompanionMove.WAVE)
            currentExpression == Expression.HAPPY_WATER -> clipFor(CompanionMove.GESTURE)
            currentExpression == Expression.TASK_REMINDER -> clipFor(CompanionMove.WAVE)
            else -> -1
        }
        if (gesture >= 0) {
            d.setLoop(loop, startNow = false)
            d.playOnce(gesture, repeats = if (currentExpression == Expression.WINK_PLAYFUL) 2 else 1)
        } else {
            d.setLoop(loop)
        }
        if (!motionAllowed) d.settle()
    }

    private fun updateCharacter(dt: Float) {
        val engine = session?.engine ?: return
        val loaded = asset ?: return
        val modelAnimator = animator ?: return
        val tm = engine.transformManager

        if (headInstance != 0) tm.setTransform(headInstance, headRest)
        if (chestInstance != 0) tm.setTransform(chestInstance, chestRest)
        if (spineInstance != 0) tm.setTransform(spineInstance, spineRest)
        director?.update(dt)?.let { pose ->
            modelAnimator.applyAnimation(pose.clip, pose.time)
            if (pose.previousClip >= 0 && pose.blend < 1f) {
                modelAnimator.applyCrossFade(pose.previousClip, pose.previousTime, pose.blend)
            }
        }

        // Body placement: facing, user spin, tap hop, walk-in steps, thread pendulum, corner peek lean.
        val direction = if (walkDirection < 0) -1f else 1f
        val targetYaw = when {
            isWalking -> 90f * direction
            isStepping -> STEP_YAW_DEGREES * direction
            else -> 0f
        }
        facingYaw += (targetYaw - facingYaw) * (1f - exp(-dt * 7f))
        if (!motionAllowed) facingYaw = targetYaw
        if (!dragging) userYaw *= exp(-dt * 4f)
        if (!motionAllowed && !dragging) userYaw = 0f
        val tapAge = clock - tapStart
        val tapHop = if (motionAllowed && tapAge in 0f..TAP_HOP_SECONDS) {
            0.09f * sin((tapAge / TAP_HOP_SECONDS) * PI.toFloat())
        } else 0f
        val stepCycle = clock * 2f * PI.toFloat() * STEP_HZ
        hopHeight = tapHop + if (isStepping) abs(sin(stepCycle)) * 0.035f else 0f
        val stepRock = if (isStepping) sin(stepCycle) * 3.5f else 0f

        Matrix.setIdentityM(placement, 0)
        Matrix.translateM(placement, 0, 0f, hopHeight, 0f)
        when (displayMode) {
            DisplayMode.THREAD_DROP -> {
                val swing = if (motionAllowed) sin(clock * 1.9f) * 5f else 0f
                val bob = if (motionAllowed) sin(clock * 3.8f) * 0.02f else 0f
                Matrix.translateM(placement, 0, 0f, THREAD_PIVOT_Y + bob, 0f)
                Matrix.rotateM(placement, 0, swing, 0f, 0f, 1f)
                Matrix.translateM(placement, 0, 0f, -THREAD_PIVOT_Y, 0f)
            }
            DisplayMode.TOP_RIGHT_PEEK -> {
                val lean = 9f + if (motionAllowed) sin(clock * 1.7f) * 2f else 0f
                Matrix.translateM(placement, 0, 0.1f, 0.9f, 0f)
                Matrix.rotateM(placement, 0, lean, 0f, 0f, 1f)
                Matrix.translateM(placement, 0, 0f, -0.9f, 0f)
            }
            DisplayMode.FULL_BODY -> Unit
        }
        Matrix.rotateM(placement, 0, facingYaw + userYaw, 0f, 1f, 0f)
        if (stepRock != 0f) Matrix.rotateM(placement, 0, stepRock, 0f, 0f, 1f)
        Matrix.multiplyMM(rootMatrix, 0, placement, 0, normalize, 0)
        tm.setTransform(tm.getInstance(loaded.root), rootMatrix)

        if (proceduralBody) applyBodyMotion(tm)
        applyHeadMotion(tm)
        modelAnimator.updateBoneMatrices()
    }

    /**
     * Breathing, weight shifts and gesture leans for models without clips. Angles stay small so
     * the height-based skin weights of an auto-rigged scan never visibly tear.
     */
    private fun applyBodyMotion(tm: TransformManager) {
        val age = clock - expressionStart
        val tapAge = clock - tapStart
        val settle = if (motionAllowed) smoothstep(age / 0.8f) else 1f
        var spinePitch = 0f
        var spineYaw = 0f
        var spineRoll = 0f
        var chestPitch = 0f
        if (motionAllowed) {
            chestPitch -= sin(clock * 2f * PI.toFloat() / BREATH_SECONDS) * 1.2f
            spineRoll += sin(clock * 0.42f) * 1.3f
            spineYaw += sin(clock * 0.27f + 1f) * 2f
            if (isTalking) chestPitch += sin(clock * 5.3f) * 0.8f
            spinePitch += 4f * bump(tapAge, TAP_NOD_SECONDS)
            when (currentExpression) {
                Expression.TASK_REMINDER -> spinePitch += 5f * bump(age, 1.4f)
                Expression.HAPPY_WATER -> spineRoll += 3f * sin(age * 4f) * bump(age, 2f)
                Expression.WINK_PLAYFUL -> spineRoll += 4f * sin(age * 5f) * bump(age, 2.2f)
                else -> Unit
            }
        }
        // Eye break: lean back and stretch while looking into the distance.
        if (currentExpression == Expression.TIRED_EYES) {
            spinePitch -= 3f * settle
            chestPitch -= 2f * settle
        }
        rotateJoint(tm, spineInstance, spinePitch, spineYaw, spineRoll)
        rotateJoint(tm, chestInstance, chestPitch, 0f, 0f)
    }

    /** 0 → 1 → 0 over [length] seconds, then 0. */
    private fun bump(age: Float, length: Float): Float =
        if (age in 0f..length) sin(age / length * PI.toFloat()) else 0f

    /** Small procedural head moves layered on top of the clips: talking nods, looking far away. */
    private fun applyHeadMotion(tm: TransformManager) {
        if (headInstance == 0) return
        val age = clock - expressionStart
        val settle = if (motionAllowed) smoothstep(age / 0.8f) else 1f
        var pitch = 0f
        var yaw = 0f
        var roll = 0f
        if (motionAllowed && isTalking) pitch += sin(clock * 7.5f) * 3.5f + sin(clock * 3.1f) * 1.5f
        // Without a wave clip, a tap is answered with a friendly nod and head tilt.
        if (motionAllowed && proceduralBody) {
            val tapAge = clock - tapStart
            pitch += 10f * bump(tapAge, TAP_NOD_SECONDS)
            roll += 5f * bump(tapAge, TAP_NOD_SECONDS * 1.4f)
        }
        when (currentExpression) {
            // 20-20-20 rule: look up and away into the distance.
            Expression.TIRED_EYES -> {
                yaw -= 30f * settle + if (motionAllowed) sin(clock * 0.7f) * 3f else 0f
                pitch -= 12f * settle
            }
            Expression.HAPPY_WATER -> if (motionAllowed) {
                roll += 6f * sin(clock * 2.2f) * settle
                if (proceduralBody) pitch += 8f * sin(age * 2f * PI.toFloat() * 1.4f) * bump(age, 1.4f)
            }
            Expression.TASK_REMINDER -> if (motionAllowed && age < 1.6f) pitch += 9f * sin(age * 2f * PI.toFloat() * 1.25f) * (1f - age / 1.6f)
            Expression.WINK_PLAYFUL -> if (motionAllowed) roll += 7f * sin(clock * 1.6f) * settle
            Expression.NORMAL -> if (motionAllowed && !isWalking) yaw += sin(clock * 0.35f) * 8f
        }
        if (displayMode == DisplayMode.TOP_RIGHT_PEEK) roll -= 6f
        rotateJoint(tm, headInstance, pitch, yaw, roll)
    }

    /**
     * Rotates a joint about its own origin by body-relative angles (degrees), whatever its parents do:
     * local' = local * world^-1 * (T(p) * R * T(-p)) * world
     */
    private fun rotateJoint(tm: TransformManager, instance: Int, pitch: Float, yaw: Float, roll: Float) {
        if (instance == 0 || (abs(pitch) < 0.01f && abs(yaw) < 0.01f && abs(roll) < 0.01f)) return
        val world = scratchA
        tm.getWorldTransform(instance, world)
        val px = world[12]
        val py = world[13]
        val pz = world[14]
        val bodyYaw = facingYaw + userYaw
        val rotation = scratchB
        Matrix.setIdentityM(rotation, 0)
        Matrix.translateM(rotation, 0, px, py, pz)
        Matrix.rotateM(rotation, 0, bodyYaw, 0f, 1f, 0f)
        Matrix.rotateM(rotation, 0, yaw, 0f, 1f, 0f)
        Matrix.rotateM(rotation, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(rotation, 0, roll, 0f, 0f, 1f)
        Matrix.rotateM(rotation, 0, -bodyYaw, 0f, 1f, 0f)
        Matrix.translateM(rotation, 0, -px, -py, -pz)
        val delta = scratchC
        Matrix.multiplyMM(delta, 0, rotation, 0, world, 0)
        val inverseWorld = rotation
        if (!Matrix.invertM(inverseWorld, 0, world, 0)) return
        Matrix.multiplyMM(world, 0, inverseWorld, 0, delta, 0)
        tm.getTransform(instance, delta)
        Matrix.multiplyMM(inverseWorld, 0, delta, 0, world, 0)
        tm.setTransform(instance, inverseWorld)
    }

    // endregion

    // region rendering

    fun requestRender() {
        needsRedraw = true
        updateLoop()
    }

    private fun updateLoop() {
        if (wantsFrames()) scheduleFrame()
    }

    private fun scheduleFrame() {
        if (frameScheduled || !isAttachedToWindow) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun canRender(): Boolean =
        session != null && swapChain != null && animationEnabled && visibleAggregated &&
            width > 0 && height > 0 && getGlobalVisibleRect(visibleRect)

    private fun wantsFrames(): Boolean =
        canRender() && (needsRedraw || !modelReady || motionAllowed || dragging || abs(userYaw) > 0.05f)

    /** Calm idle motion needs fewer frames; interaction and entrances still render at full rate. */
    private fun wantsFullFrameRate(): Boolean =
        needsRedraw || dragging || abs(userYaw) > 0.05f || walkingIn || displayMode == DisplayMode.THREAD_DROP ||
            clock - tapStart < FULL_RATE_SECONDS || clock - expressionStart < FULL_RATE_SECONDS ||
            director?.isPlayingOneShot == true

    private fun onFrame(frameTimeNanos: Long) {
        if (!canRender()) {
            lastFrameNanos = 0L
            return
        }
        if (lastFrameNanos != 0L && !wantsFullFrameRate() &&
            frameTimeNanos - lastFrameNanos < IDLE_FRAME_INTERVAL_NANOS) {
            scheduleFrame()
            return
        }
        val dt = if (lastFrameNanos == 0L) 0f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = frameTimeNanos
        if (motionAllowed) clock += dt
        if (renderFrame(frameTimeNanos, if (motionAllowed) dt else 0f)) needsRedraw = false
        if (wantsFrames()) scheduleFrame() else lastFrameNanos = 0L
    }

    private fun renderFrame(frameTimeNanos: Long, dt: Float): Boolean {
        val currentRenderer = renderer ?: return false
        val currentSwapChain = swapChain ?: return false
        val view = filamentView ?: return false
        if (uiHelper?.isReadyToRender != true) return false
        val ready = pumpResourceLoading()
        if (ready) updateCharacter(dt)
        updateCamera()
        if (!currentRenderer.beginFrame(currentSwapChain, frameTimeNanos)) return false
        currentRenderer.render(view)
        currentRenderer.endFrame()
        framesRendered++
        if (ready) {
            updateAnchors()
            if (renderState != RenderState.READY) {
                modelFramesSubmitted++
                if (modelFramesSubmitted == 1 && motionAllowed) {
                    // Start the fade with the first body frame, so it is never shown at full opacity first.
                    for (layer in listOf(textureView, shadowLayer, effectsLayer)) {
                        layer.alpha = 0f
                        layer.animate().alpha(1f).setDuration(FADE_IN_MS).start()
                    }
                }
                // Filament refuses a new frame while two are still on the GPU, so once this many body
                // frames were accepted the first one has finished and is on its way to the screen.
                if (modelFramesSubmitted >= FRAMES_UNTIL_VISIBLE) setRenderState(RenderState.READY)
            }
        }
        invalidateEffectLayers()
        // Keep producing frames until the body is confirmed on screen, even when motion is reduced.
        return ready && renderState == RenderState.READY
    }

    /**
     * Redrawing a 2D layer costs an extra UI frame on top of the TextureView's own, so the layers are
     * only invalidated while an effect is showing (plus once to clear it) or when the shadow moved.
     */
    private fun invalidateEffectLayers() {
        val bodyShown = anchorsValid && renderState == RenderState.READY
        val effectsActive = bodyShown && (displayMode == DisplayMode.THREAD_DROP ||
            currentExpression != Expression.NORMAL || isTalking ||
            (motionAllowed && clock - tapStart in 0f..TAP_SPARKLE_SECONDS))
        if (effectsActive || effectsDrawn) effectsLayer.invalidate()
        effectsDrawn = effectsActive

        val shadowShown = bodyShown && displayMode == DisplayMode.FULL_BODY
        val moved = shadowShown && (abs(groundX - shadowDrawn[0]) > SHADOW_EPSILON_PX ||
            abs(groundY - shadowDrawn[1]) > SHADOW_EPSILON_PX ||
            abs(groundHalfWidth - shadowDrawn[2]) > SHADOW_EPSILON_PX ||
            abs(hopHeight - shadowDrawn[3]) > SHADOW_EPSILON_HOP)
        if (shadowShown != shadowDrawnVisible || moved) {
            shadowDrawnVisible = shadowShown
            shadowDrawn[0] = groundX
            shadowDrawn[1] = groundY
            shadowDrawn[2] = groundHalfWidth
            shadowDrawn[3] = hopHeight
            shadowLayer.invalidate()
        }
    }

    private fun updateCamera() {
        val cam = camera ?: return
        val w = textureView.width
        val h = textureView.height
        if (w <= 0 || h <= 0) return
        filamentView?.let { if (it.viewport.width != w || it.viewport.height != h) it.viewport = Viewport(0, 0, w, h) }
        val aspect = w.toFloat() / h
        val (centerY, halfHeight, halfWidth) = when (displayMode) {
            DisplayMode.FULL_BODY -> Triple(0.9f, 1.02f, 0.62f)
            DisplayMode.TOP_RIGHT_PEEK -> Triple(1.38f, 0.48f, 0.45f)
            DisplayMode.THREAD_DROP -> Triple(1.0f, 1.12f, 0.62f)
        }
        val tanHalf = tan(Math.toRadians(FOV_DEGREES / 2.0)).toFloat()
        val distance = max(halfHeight / tanHalf, halfWidth / (tanHalf * aspect))
        val eyeY = centerY + distance * 0.05f
        cam.setProjection(FOV_DEGREES, aspect.toDouble(), NEAR, FAR, Camera.Fov.VERTICAL)
        cam.lookAt(0.0, eyeY.toDouble(), distance.toDouble(), 0.0, centerY.toDouble(), 0.0, 0.0, 1.0, 0.0)
        Matrix.perspectiveM(projection, 0, FOV_DEGREES.toFloat(), aspect, NEAR.toFloat(), FAR.toFloat())
        Matrix.setLookAtM(viewMatrix, 0, 0f, eyeY, distance, 0f, centerY, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, viewMatrix, 0)
    }

    /** Projects head / hand / ground positions to view pixels for the 2D effect layers. */
    private fun updateAnchors() {
        val tm = session?.engine?.transformManager ?: return
        val world = scratchA
        val headX: Float
        val headY: Float
        val headZ: Float
        if (headInstance != 0) {
            tm.getWorldTransform(headInstance, world)
            headX = world[12]; headY = world[13]; headZ = world[14]
        } else {
            transformPoint(placement, 0f, MODEL_HEIGHT * 0.83f, 0f)
            headX = scratchOut[0]; headY = scratchOut[1]; headZ = scratchOut[2]
        }
        val yawRad = Math.toRadians((facingYaw + userYaw).toDouble())
        val forwardX = sin(yawRad).toFloat()
        val forwardZ = cos(yawRad).toFloat()
        // The head joint sits anywhere from the neck to the skull base depending on the rig, so the
        // head top comes from the normalized model height and the face from the head top.
        transformPoint(placement, 0f, MODEL_HEIGHT, 0f)
        val topLift = (scratchOut[1] - headY).coerceIn(MIN_HEAD_TOP_LIFT, MAX_HEAD_TOP_LIFT)
        val faceLift = max(topLift - FACE_BELOW_TOP, MIN_HEAD_TOP_LIFT / 2f)
        if (!project(headX, headY, headZ)) return
        val headScreenX = screenPoint[0]
        val headScreenY = screenPoint[1]
        if (!project(headX + forwardX * 0.1f, headY + faceLift, headZ + forwardZ * 0.1f)) return
        faceX = screenPoint[0]; faceY = screenPoint[1]
        if (!project(headX, headY + topLift, headZ)) return
        headTopX = screenPoint[0]; headTopY = screenPoint[1]
        pixelsPerMeter = hypot(headTopX - headScreenX, headTopY - headScreenY) / topLift
        project(headX, headY - 0.3f, headZ)
        chestY = screenPoint[1]

        var bestY = -Float.MAX_VALUE
        handX = headTopX; handY = headTopY
        for (instance in wristInstances) {
            tm.getWorldTransform(instance, world)
            if (world[13] > bestY && project(world[12], world[13], world[14])) {
                bestY = world[13]
                handX = screenPoint[0]; handY = screenPoint[1]
            }
        }
        project(0f, THREAD_PIVOT_Y, 0f)
        cordTopX = screenPoint[0]
        project(0f, 0f, 0f)
        groundX = screenPoint[0]; groundY = screenPoint[1]
        project(0.3f, 0f, 0f)
        groundHalfWidth = abs(screenPoint[0] - groundX)
        anchorsValid = true
    }

    private fun transformPoint(matrix: FloatArray, x: Float, y: Float, z: Float) {
        scratchVec[0] = x; scratchVec[1] = y; scratchVec[2] = z; scratchVec[3] = 1f
        Matrix.multiplyMV(scratchOut, 0, matrix, 0, scratchVec, 0)
    }

    private fun project(x: Float, y: Float, z: Float): Boolean {
        transformPoint(viewProjection, x, y, z)
        val w = scratchOut[3]
        if (w <= 1e-5f) return false
        screenPoint[0] = (scratchOut[0] / w * 0.5f + 0.5f) * textureView.width
        screenPoint[1] = (0.5f - scratchOut[1] / w * 0.5f) * textureView.height
        return true
    }

    // endregion

    // region touch

    // Taps fall through to super.onTouchEvent(), which calls performClick().
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragToRotate || renderState != RenderState.READY) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                lastTouchX = event.x
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.x - downX) > touchSlop) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.onTouchEvent(cancel)
                    cancel.recycle()
                }
                if (dragging) {
                    userYaw = (userYaw + (event.x - lastTouchX) / width.coerceAtLeast(1) * 240f).coerceIn(-170f, 170f)
                    lastTouchX = event.x
                    requestRender()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                requestRender()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // endregion

    // region 2D effect layers

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(90, 15, 23, 42), Color.argb(0, 15, 23, 42)), null, Shader.TileMode.CLAMP)
    }
    // Warm aura that rings the head: clear in the middle so the face itself is never tinted.
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(120, 249, 115, 22), Color.argb(60, 239, 68, 68), Color.TRANSPARENT),
            floatArrayOf(0f, 0.6f, 0.76f, 0.88f, 1f), Shader.TileMode.CLAMP)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 255, 255) }
    private val twinklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 250, 204, 21) }
    private val twinklePath = Path()
    private val beaconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.argb(220, 245, 158, 11)
    }
    private val beaconCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 245, 158, 11) }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.5f * density
    }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.2f * density
        color = Color.argb(235, 226, 232, 240)
    }
    private val cordGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 6f * density
        color = Color.argb(70, 16, 185, 129)
    }
    private val cordPath = Path()
    private val sparkColors = intArrayOf(
        Color.rgb(16, 185, 129), Color.rgb(56, 189, 248), Color.rgb(245, 158, 11),
        Color.rgb(244, 114, 182), Color.rgb(167, 139, 250), Color.rgb(250, 204, 21)
    )

    private fun drawGroundShadow(canvas: Canvas) {
        if (!anchorsValid || renderState != RenderState.READY || displayMode != DisplayMode.FULL_BODY) return
        val lift = (hopHeight / 0.09f).coerceIn(0f, 1f)
        val rx = groundHalfWidth * (1.15f - 0.25f * lift)
        shadowPaint.alpha = (255 * (1f - 0.35f * lift)).toInt()
        canvas.withTranslation(groundX, groundY) {
            scale(rx, rx * 0.2f)
            drawCircle(0f, 0f, 1f, shadowPaint)
        }
    }

    private fun drawEffects(canvas: Canvas) {
        if (!anchorsValid || renderState != RenderState.READY) return
        val t = clock
        val unit = pixelsPerMeter
        if (displayMode == DisplayMode.THREAD_DROP) drawCord(canvas, t)
        when (currentExpression) {
            Expression.TIRED_EYES -> {
                val pulse = if (motionAllowed) abs(sin(t * 2.4f)) else 0.5f
                canvas.withTranslation(faceX, faceY) {
                    scale(unit * (0.16f + 0.015f * pulse), unit * (0.21f + 0.015f * pulse))
                    drawCircle(0f, 0f, 1f, glowPaint)
                }
            }
            Expression.HAPPY_WATER -> drawBubbles(canvas, t, unit)
            Expression.WINK_PLAYFUL -> {
                val twinkle = if (motionAllowed) abs(sin(t * 3f)) else 0.6f
                drawTwinkle(canvas, faceX + unit * 0.17f, faceY - unit * 0.12f, (7f + 4f * twinkle) * density)
                val second = if (motionAllowed) abs(sin(t * 3f + 1.9f)) else 0.4f
                drawTwinkle(canvas, faceX + unit * 0.25f, faceY - unit * 0.01f, (3.5f + 2.5f * second) * density)
            }
            Expression.TASK_REMINDER -> {
                val pulse = if (motionAllowed) abs(sin(t * 3.2f)) else 0.5f
                // Above the head when there is room, otherwise beside it like a badge.
                val above = headTopY - 26f * density
                val roomAbove = above >= 20f * density
                val cx = if (roomAbove) headTopX else headTopX + unit * 0.09f + 18f * density
                val cy = if (roomAbove) above else max(headTopY, 20f * density)
                canvas.drawCircle(cx, cy, (9f + 7f * pulse) * density, beaconPaint)
                canvas.drawCircle(cx, cy, 4.5f * density, beaconCorePaint)
            }
            Expression.NORMAL -> Unit
        }
        if (isTalking) drawSpeechWaves(canvas, t, unit)
        drawTapSparkles(canvas, t, unit)
    }

    /** Four-pointed sparkle (✦) with a white core. */
    private fun drawTwinkle(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val inner = size * 0.22f
        twinklePath.reset()
        twinklePath.moveTo(cx, cy - size)
        twinklePath.lineTo(cx + inner, cy - inner)
        twinklePath.lineTo(cx + size, cy)
        twinklePath.lineTo(cx + inner, cy + inner)
        twinklePath.lineTo(cx, cy + size)
        twinklePath.lineTo(cx - inner, cy + inner)
        twinklePath.lineTo(cx - size, cy)
        twinklePath.lineTo(cx - inner, cy - inner)
        twinklePath.close()
        canvas.drawPath(twinklePath, twinklePaint)
        canvas.drawCircle(cx, cy, inner * 0.8f, highlightPaint)
    }

    private fun drawBubbles(canvas: Canvas, t: Float, unit: Float) {
        val bottom = chestY
        val top = headTopY - unit * 0.15f
        for (i in 0 until 6) {
            val cycle = if (motionAllowed) (t * 0.55f + i * 0.33f) % 1f else i / 6f
            val x = faceX + sin(cycle * 4.5f + i * 1.7f) * unit * 0.32f
            val y = bottom + (top - bottom) * cycle
            val r = (3.5f + i % 3 * 1.6f) * density * (1f - cycle * 0.3f)
            bubblePaint.color = Color.argb(((1f - cycle) * 200).toInt().coerceIn(0, 255), 56, 189, 248)
            canvas.drawCircle(x, y, r, bubblePaint)
            canvas.drawCircle(x - r * 0.35f, y - r * 0.35f, r * 0.3f, highlightPaint)
        }
    }

    private fun drawSpeechWaves(canvas: Canvas, t: Float, unit: Float) {
        val x = faceX + unit * 0.14f
        val y = faceY + unit * 0.06f
        for (i in 0..2) {
            val progress = if (motionAllowed) (t * 1.8f + i / 3f) % 1f else (i + 1) / 4f
            val r = (6f + progress * 14f) * density
            wavePaint.color = Color.argb(((1f - progress) * 190).toInt().coerceIn(0, 255), 56, 189, 248)
            canvas.drawArc(x - r, y - r, x + r, y + r, -40f, 80f, false, wavePaint)
        }
    }

    private fun drawTapSparkles(canvas: Canvas, t: Float, unit: Float) {
        val age = t - tapStart
        if (!motionAllowed || age !in 0f..TAP_SPARKLE_SECONDS) return
        val progress = age / TAP_SPARKLE_SECONDS
        val distance = unit * (0.12f + 0.4f * progress)
        for (i in sparkColors.indices) {
            val angle = i * (2f * PI.toFloat() / sparkColors.size) + 0.4f
            sparkPaint.color = sparkColors[i]
            sparkPaint.alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(
                faceX + cos(angle) * distance,
                faceY - unit * 0.1f + sin(angle) * distance,
                (4.5f - 2.5f * progress) * density,
                sparkPaint
            )
        }
    }

    private fun drawCord(canvas: Canvas, t: Float) {
        val sway = if (motionAllowed) sin(t * 5.5f) * 3f * density else 0f
        cordPath.reset()
        cordPath.moveTo(cordTopX, 0f)
        cordPath.quadTo((cordTopX + handX) / 2f + sway, handY / 2f, handX, handY)
        canvas.drawPath(cordPath, cordGlowPaint)
        canvas.drawPath(cordPath, cordPaint)
    }

    private inner class GroundShadowLayer(context: Context) : android.view.View(context) {
        override fun onDraw(canvas: Canvas) = drawGroundShadow(canvas)
    }

    private inner class EffectsLayer(context: Context) : android.view.View(context) {
        override fun onDraw(canvas: Canvas) = drawEffects(canvas)
    }

    // endregion

    private fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    companion object {
        const val PHASE_IDLE = "idle"
        const val PHASE_WALK_IN = "walk_in"
        const val PHASE_TALK = "talk"
        private const val TAG = "HumanCompanionView"
        private const val MODEL_HEIGHT = 1.7f
        private const val MIN_HEAD_TOP_LIFT = 0.08f
        private const val MAX_HEAD_TOP_LIFT = 0.35f
        private const val FACE_BELOW_TOP = 0.12f
        private const val THREAD_PIVOT_Y = 2.6f
        private const val FOV_DEGREES = 28.0
        private const val NEAR = 0.05
        private const val FAR = 30.0
        private const val TAP_HOP_SECONDS = 0.45f
        private const val TAP_NOD_SECONDS = 0.9f
        private const val TAP_SPARKLE_SECONDS = 0.8f
        private const val BREATH_SECONDS = 4.2f
        private const val STEP_HZ = 1.6f
        private const val STEP_YAW_DEGREES = 35f
        private const val FADE_IN_MS = 280L
        /** Filament's default frame latency (2) plus one. */
        private const val FRAMES_UNTIL_VISIBLE = 3
        private const val FULL_RATE_SECONDS = 2.5f
        private const val IDLE_FRAME_INTERVAL_NANOS = 66_666_667L
        private const val SHADOW_EPSILON_PX = 0.25f
        private const val SHADOW_EPSILON_HOP = 1e-4f
    }
}
