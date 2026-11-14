package com.wellness.overlay3d.companion3d

import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager

/**
 * Soft "studio" lighting that needs no HDR/KTX files: a sky/ground spherical-harmonics ambient
 * term plus one warm key light. Without the ambient term, faces turned away from the key light
 * render pitch black, which is what made the very first 3D attempt look like black polygons.
 */
internal object StudioLighting {
    private const val AMBIENT_INTENSITY = 30_000f
    private const val SUN_LUX = 85_000f

    fun createIndirectLight(engine: Engine): IndirectLight {
        // Two SH bands, pre-scaled for Filament: L00, L1-1 (y), L10 (z), L11 (x).
        val sky = floatArrayOf(0.95f, 0.98f, 1.05f)
        val ground = floatArrayOf(0.44f, 0.38f, 0.32f)
        val frontFill = 0.12f
        val sh = FloatArray(12)
        for (c in 0..2) {
            sh[c] = (sky[c] + ground[c]) * 0.5f
            sh[3 + c] = (sky[c] - ground[c]) * 0.5f
            sh[6 + c] = frontFill
            sh[9 + c] = 0f
        }
        return IndirectLight.Builder()
            .irradiance(2, sh)
            .intensity(AMBIENT_INTENSITY)
            .build(engine)
    }

    /** Key light from the upper front-left, slightly warm. Caller owns (and destroys) the entity. */
    fun createKeyLight(engine: Engine): Int {
        val entity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.96f, 0.9f)
            .intensity(SUN_LUX)
            .direction(0.45f, -0.75f, -0.6f)
            .castShadows(false)
            .build(engine, entity)
        return entity
    }
}
