package com.wellness.overlay3d.companion3d

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.UbershaderProvider
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Process-wide Filament state shared by every on-screen companion (dashboard + reminder overlay).
 *
 * Creating an Engine, compiling ubershaders and parsing the model are the expensive parts, so they
 * happen once and are kept for a short grace period after the last companion disappears. Every
 * method must be called on the main thread: Filament objects are only ever touched from there.
 */
object CompanionEngine {
    const val MODEL_ASSET_PATH = "models/companion.glb"
    private const val TAG = "CompanionEngine"
    private const val RELEASE_GRACE_MS = 20_000L

    class Session internal constructor(
        val engine: Engine,
        val assetLoader: AssetLoader,
        val indirectLight: IndirectLight
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "companion-model-io").apply { isDaemon = true }
    }
    private var nativesReady: Boolean? = null
    private var session: Session? = null
    private var materials: UbershaderProvider? = null
    private var users = 0
    private var modelBytes: ByteBuffer? = null
    private val pendingModelCallbacks = mutableListOf<(ByteBuffer?) -> Unit>()
    private val releaseRunnable = Runnable { if (users == 0) destroySession() }

    /** Null when this device cannot run the 3D companion; callers then show the 2D fallback. */
    @MainThread
    fun acquire(): Session? {
        checkMainThread()
        mainHandler.removeCallbacks(releaseRunnable)
        session?.let {
            users++
            return it
        }
        if (!loadNatives()) return null
        val engine = try {
            Engine.create()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Filament engine could not start", error)
            return null
        }
        if (engine.activeFeatureLevel == Engine.FeatureLevel.FEATURE_LEVEL_0) {
            // glTF ubershaders need OpenGL ES 3.0 feature level 1.
            Log.w(TAG, "GPU only supports Filament feature level 0; using the 2D fallback")
            engine.destroy()
            return null
        }
        val provider = UbershaderProvider(engine)
        val created = Session(
            engine = engine,
            assetLoader = AssetLoader(engine, provider, EntityManager.get()),
            indirectLight = StudioLighting.createIndirectLight(engine)
        )
        materials = provider
        session = created
        users = 1
        return created
    }

    @MainThread
    fun release() {
        checkMainThread()
        if (users == 0) return
        users--
        if (users == 0) {
            mainHandler.removeCallbacks(releaseRunnable)
            mainHandler.postDelayed(releaseRunnable, RELEASE_GRACE_MS)
        }
    }

    /**
     * Delivers the GLB as a direct buffer on the main thread (null when the asset is missing or
     * unreadable). The file is read off the main thread once and cached while the engine lives;
     * the buffer must stay alive until resource loading finishes, which the cache guarantees.
     */
    @MainThread
    fun loadModel(context: Context, callback: (ByteBuffer?) -> Unit) {
        checkMainThread()
        modelBytes?.let {
            callback(it)
            return
        }
        pendingModelCallbacks += callback
        if (pendingModelCallbacks.size > 1) return
        val appContext = context.applicationContext
        io.execute {
            val buffer = try {
                appContext.assets.open(MODEL_ASSET_PATH).use { input ->
                    val bytes = input.readBytes()
                    ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                        put(bytes)
                        flip()
                    }
                }
            } catch (error: IOException) {
                Log.e(TAG, "Companion model $MODEL_ASSET_PATH is missing or unreadable", error)
                null
            }
            mainHandler.post {
                if (buffer != null && session != null) modelBytes = buffer
                val callbacks = pendingModelCallbacks.toList()
                pendingModelCallbacks.clear()
                callbacks.forEach { it(buffer) }
            }
        }
    }

    private fun destroySession() {
        val current = session ?: return
        session = null
        modelBytes = null
        current.engine.destroyIndirectLight(current.indirectLight)
        current.assetLoader.destroy()
        materials?.let {
            it.destroyMaterials()
            it.destroy()
        }
        materials = null
        current.engine.destroy()
    }

    private fun loadNatives(): Boolean {
        nativesReady?.let { return it }
        val ready = try {
            Filament.init()
            Gltfio.init()
            true
        } catch (error: UnsatisfiedLinkError) {
            Log.e(TAG, "Filament native libraries are unavailable on this ABI", error)
            false
        }
        nativesReady = ready
        return ready
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "CompanionEngine is main-thread only" }
    }
}
