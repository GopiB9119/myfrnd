package com.wellness.overlay3d.filament

import android.content.Context
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import com.google.android.filament.*
import com.google.android.filament.gltfio.*
import com.google.android.filament.utils.Utils
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels

/**
 * FilamentRenderer: High-Performance 3D Graphics Pipeline for Android System Overlays.
 *
 * Built with Google Filament Engine + gltfio loader.
 * - Safely initializes all native JNI dependencies (Filament, Gltfio, Utils).
 * - Dynamically searches and loads rigged 3D models (.glb) from assets/models/.
 * - Supports seamless playback of "walk_in", "talk", and "walk_out" skeletal animations.
 * - Fully clears GPU swapchain and destroys native memory immediately on off-screen exit
 *   to ensure zero battery/RAM drain when inactive.
 */
class FilamentRenderer(
    private val context: Context,
    var onModelLoaded: ((Boolean) -> Unit)? = null
) {

    companion object {
        private const val TAG = "FilamentRenderer"

        // Candidate model paths in assets/
        val CANDIDATE_ASSET_PATHS = arrayOf(
            "models/dev_companion.glb",
            "models/character.glb",
            "dev_companion.glb",
            "character.glb"
        )

        fun hasGlbAsset(context: Context): Boolean {
            for (path in CANDIDATE_ASSET_PATHS) {
                try {
                    val stream = context.assets.open(path)
                    stream.close()
                    return true
                } catch (_: Exception) {}
            }
            return false
        }

        init {
            try {
                Filament.init()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed initializing Filament JNI: ${t.message}", t)
            }
            try {
                Gltfio.init()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed initializing Gltfio JNI: ${t.message}", t)
            }
            try {
                Utils.init()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed initializing Filament Utils JNI: ${t.message}", t)
            }
        }
    }

    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var scene: Scene? = null
    private var view: View? = null
    private var camera: Camera? = null
    private var swapChain: SwapChain? = null

    private var assetLoader: AssetLoader? = null
    private var materialProvider: MaterialProvider? = null
    private var resourceLoader: ResourceLoader? = null
    private var filamentAsset: FilamentAsset? = null

    @Entity private var sunlightEntity: Int = 0
    @Entity private var fillLightEntity: Int = 0

    private val choreographer = Choreographer.getInstance()
    private var isRendering = false
    private var startTimeNanos: Long = 0L
    private var lastFrameTimeNanos: Long = 0L

    private var currentAnimationIndex: Int = -1
    private var animationStartTimeNanos: Long = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRendering) return
            choreographer.postFrameCallback(this)

            if (startTimeNanos == 0L) startTimeNanos = frameTimeNanos
            lastFrameTimeNanos = frameTimeNanos
            val totalElapsedSeconds = (frameTimeNanos - startTimeNanos) / 1_000_000_000.0

            renderFrame(frameTimeNanos, totalElapsedSeconds)
        }
    }

    /**
     * Initializes the Filament context attached to the translucent SurfaceView.
     */
    fun onSurfaceCreated(surface: Surface, width: Int, height: Int) {
        try {
            val eng = Engine.create().also { engine = it }
            val rend = eng.createRenderer().also { renderer = it }

            // 1. Transparent Clear Options (alpha = 0.0f)
            rend.clearOptions = Renderer.ClearOptions().apply {
                clear = true
                clearColor = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
            }

            val scn = eng.createScene().also { scene = it }
            val vw = eng.createView().also { view = it }

            // 2. Crucial for transparent system overlay
            vw.blendMode = View.BlendMode.TRANSLUCENT
            vw.isPostProcessingEnabled = false // Disables tone-mapping/bloom artifacts on transparent buffer
            vw.scene = scn

            val camEntity = EntityManager.get().create()
            val cam = eng.createCamera(camEntity).also { camera = it }
            vw.camera = cam

            swapChain = eng.createSwapChain(surface)

            setupStudioLighting(eng, scn)
            updateCameraProjection(width, height)

            val modelLoaded = loadGlbAssetIfPresent(eng, scn)
            onModelLoaded?.invoke(modelLoaded)

            isRendering = true
            choreographer.postFrameCallback(frameCallback)
        } catch (t: Throwable) {
            Log.e(TAG, "Error in onSurfaceCreated: ${t.message}", t)
            onModelLoaded?.invoke(false)
        }
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        try {
            view?.viewport = Viewport(0, 0, width, height)
            updateCameraProjection(width, height)
        } catch (t: Throwable) {
            Log.e(TAG, "Error in onSurfaceChanged: ${t.message}", t)
        }
    }

    fun onSurfaceDestroyed() {
        isRendering = false
        choreographer.removeFrameCallback(frameCallback)
    }

    private fun updateCameraProjection(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val aspect = width.toDouble() / height.toDouble()
        // Perspective camera framed on character's upper body and gestures
        camera?.setProjection(38.0, aspect, 0.1, 50.0, Camera.Fov.VERTICAL)
        camera?.lookAt(0.0, 0.95, 2.85, 0.0, 0.72, 0.0, 0.0, 1.0, 0.0)
    }

    private fun setupStudioLighting(eng: Engine, scn: Scene) {
        // Key Light: Warm directional light
        sunlightEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.96f, 0.92f)
            .intensity(95_000.0f)
            .direction(0.45f, -0.75f, -0.65f)
            .castShadows(false)
            .build(eng, sunlightEntity)
        scn.addEntity(sunlightEntity)

        // Fill / Rim Light: Cool directional light
        fillLightEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(0.75f, 0.88f, 1.0f)
            .intensity(42_000.0f)
            .direction(-0.55f, 0.35f, 0.5f)
            .build(eng, fillLightEntity)
        scn.addEntity(fillLightEntity)
    }

    /**
     * Safely locates and loads a local .glb model from assets.
     * Returns true if a 3D model was successfully loaded into the scene.
     */
    private fun loadGlbAssetIfPresent(eng: Engine, scn: Scene): Boolean {
        var foundPath: String? = null
        var buffer: ByteBuffer? = null

        // 1. Check if candidate file actually exists BEFORE creating native providers
        for (path in CANDIDATE_ASSET_PATHS) {
            try {
                val inputStream: InputStream = context.assets.open(path)
                val channel = Channels.newChannel(inputStream)
                val directBuffer = ByteBuffer.allocateDirect(inputStream.available())
                channel.read(directBuffer)
                directBuffer.rewind()
                buffer = directBuffer
                foundPath = path
                inputStream.close()
                break
            } catch (_: Exception) {
                // Continue searching candidate paths
            }
        }

        if (buffer == null || foundPath == null) {
            Log.i(TAG, "No .glb asset found in assets/models/. Using companion character view.")
            return false
        }

        return try {
            val matProvider = UbershaderProvider(eng).also { materialProvider = it }
            val loader = AssetLoader(eng, matProvider, EntityManager.get()).also { assetLoader = it }
            val resLoader = ResourceLoader(eng).also { resourceLoader = it }

            val asset = loader.createAsset(buffer)
            if (asset != null) {
                resLoader.loadResources(asset)
                asset.releaseSourceData()
                scn.addEntities(asset.entities)
                filamentAsset = asset
                Log.i(TAG, "Successfully loaded 3D GLB model from: $foundPath")
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Native error parsing gltfio asset from '$foundPath': ${t.message}", t)
            false
        }
    }

    /**
     * Plays a named animation track ("walk_in", "talk", "walk_out") on the GLB model.
     */
    fun playAnimation(name: String) {
        val asset = filamentAsset ?: return
        val animator = asset.instance?.animator ?: return
        val count = animator.animationCount
        if (count == 0) return

        var targetIndex = -1
        val targetQuery = name.lowercase()

        for (i in 0 until count) {
            val animName = animator.getAnimationName(i)?.lowercase() ?: ""
            if (animName.contains(targetQuery)) {
                targetIndex = i
                break
            }
        }

        // Secondary fallback search if specific names differ (e.g. "walk", "speech", "idle")
        if (targetIndex == -1) {
            when (targetQuery) {
                "walk_in", "walk_out" -> {
                    for (i in 0 until count) {
                        val n = animator.getAnimationName(i)?.lowercase() ?: ""
                        if (n.contains("walk") || n.contains("run") || n.contains("move")) {
                            targetIndex = i; break
                        }
                    }
                }
                "talk" -> {
                    for (i in 0 until count) {
                        val n = animator.getAnimationName(i)?.lowercase() ?: ""
                        if (n.contains("talk") || n.contains("speak") || n.contains("gesture")) {
                            targetIndex = i; break
                        }
                    }
                }
            }
        }

        // Default to first track if available
        if (targetIndex == -1 && count > 0) {
            targetIndex = 0
        }

        currentAnimationIndex = targetIndex
        animationStartTimeNanos = System.nanoTime()
    }

    private fun renderFrame(frameTimeNanos: Long, totalElapsedSeconds: Double) {
        val rend = renderer ?: return
        val vw = view ?: return
        val sc = swapChain ?: return

        // Update skeletal animation
        val animator = filamentAsset?.instance?.animator
        if (animator != null && currentAnimationIndex >= 0 && currentAnimationIndex < animator.animationCount) {
            val elapsedSec = (frameTimeNanos - animationStartTimeNanos) / 1_000_000_000.0
            val duration = animator.getAnimationDuration(currentAnimationIndex).toDouble()
            val time = if (duration > 0.0) (elapsedSec % duration).toFloat() else elapsedSec.toFloat()
            animator.applyAnimation(currentAnimationIndex, time)
            animator.updateBoneMatrices()
        }

        try {
            if (rend.beginFrame(sc, frameTimeNanos)) {
                rend.render(vw)
                rend.endFrame()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error during renderFrame: ${t.message}")
        }
    }

    /**
     * Strict native resource disposal: 0% CPU, 0% GPU, and 0% RAM when overlay is hidden.
     */
    fun destroy() {
        isRendering = false
        choreographer.removeFrameCallback(frameCallback)

        val eng = engine ?: return

        try {
            filamentAsset?.let { asset ->
                scene?.removeEntities(asset.entities)
                assetLoader?.destroyAsset(asset)
            }
        } catch (_: Throwable) {}
        filamentAsset = null

        try { resourceLoader?.destroy() } catch (_: Throwable) {}
        resourceLoader = null

        try { assetLoader?.destroy() } catch (_: Throwable) {}
        assetLoader = null

        try { materialProvider?.destroy() } catch (_: Throwable) {}
        materialProvider = null

        try {
            if (sunlightEntity != 0) {
                scene?.removeEntity(sunlightEntity)
                eng.destroyEntity(sunlightEntity)
                EntityManager.get().destroy(sunlightEntity)
                sunlightEntity = 0
            }
            if (fillLightEntity != 0) {
                scene?.removeEntity(fillLightEntity)
                eng.destroyEntity(fillLightEntity)
                EntityManager.get().destroy(fillLightEntity)
                fillLightEntity = 0
            }
        } catch (_: Throwable) {}

        try {
            swapChain?.let { eng.destroySwapChain(it) }
        } catch (_: Throwable) {}
        swapChain = null

        try {
            camera?.let {
                eng.destroyCameraComponent(it.entity)
                EntityManager.get().destroy(it.entity)
            }
        } catch (_: Throwable) {}
        camera = null

        try { view?.let { eng.destroyView(it) } } catch (_: Throwable) {}
        view = null

        try { scene?.let { eng.destroyScene(it) } } catch (_: Throwable) {}
        scene = null

        try { renderer?.let { eng.destroyRenderer(it) } } catch (_: Throwable) {}
        renderer = null

        try { eng.destroy() } catch (_: Throwable) {}
        engine = null

        System.gc()
    }
}
