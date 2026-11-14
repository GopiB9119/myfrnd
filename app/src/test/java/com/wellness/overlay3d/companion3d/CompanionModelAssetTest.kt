package com.wellness.overlay3d.companion3d

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The bundled model must be a self-contained glTF binary that gltfio can load and the procedural rig can drive. */
@RunWith(RobolectricTestRunner::class) // Robolectric supplies the real org.json implementation.
@Config(sdk = [34])
class CompanionModelAssetTest {
    private val bytes = listOf("src/main/assets", "app/src/main/assets")
        .map { File(it, CompanionEngine.MODEL_ASSET_PATH) }
        .firstOrNull { it.isFile }
        ?.readBytes()
        ?: error("${CompanionEngine.MODEL_ASSET_PATH} not found from ${File("").absolutePath}")
    private val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private val jsonLength = header.getInt(12)

    private fun gltf() = JSONObject(String(bytes, 20, jsonLength, Charsets.UTF_8))

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

    @Test fun isAGlb2FileWithJsonThenBinaryChunkUnderFiveMegabytes() {
        assertTrue("${bytes.size} bytes", bytes.size < 5 * 1024 * 1024)
        assertEquals(0x46546C67, header.getInt(0))
        assertEquals(2, header.getInt(4))
        assertEquals(bytes.size, header.getInt(8))
        assertEquals(0x4E4F534A, header.getInt(16))
        val binChunk = 20 + jsonLength
        assertEquals(0x004E4942, header.getInt(binChunk + 4))
        val binLength = header.getInt(binChunk)
        assertTrue(binChunk + 8 + binLength <= bytes.size)
        // gltfio only receives this file, so the one buffer must be the embedded BIN chunk.
        val buffer = gltf().getJSONArray("buffers").objects().single()
        assertFalse(buffer.has("uri"))
        assertTrue(buffer.getInt("byteLength") <= binLength)
    }

    @Test fun describesASkinnedTexturedHumanWithoutRequiredExtensions() {
        val gltf = gltf()
        assertFalse(gltf.has("extensionsRequired"))
        assertTrue(gltf.getJSONArray("meshes").length() > 0)
        assertTrue(gltf.getJSONArray("materials").length() > 0)
        val images = gltf.getJSONArray("images").objects()
        assertTrue(images.isNotEmpty())
        images.forEachIndexed { index, image ->
            assertTrue("image $index must be embedded", image.has("bufferView") || image.optString("uri").startsWith("data:"))
        }

        val nodeNames = gltf.getJSONArray("nodes").objects().map { if (it.has("name")) it.getString("name") else null }
        val skins = gltf.getJSONArray("skins").objects().map { skin ->
            skin.getJSONArray("joints").let { joints -> List(joints.length()) { joints.getInt(it) } }
        }
        val rig = skins.firstOrNull { joints -> joints.map(nodeNames::get).containsAll(listOf("Head", "Chest", "Spine")) }
        assertNotNull("skins: ${skins.map { it.map(nodeNames::get) }}", rig)
        // The procedural pose bends these bones, so they must resolve to joints of that skin.
        listOf(CompanionJoint.HEAD, CompanionJoint.CHEST, CompanionJoint.SPINE).forEach { joint ->
            assertTrue(joint.name, HumanRig.findJoint(nodeNames, joint) in rig!!)
        }
    }
}
