package com.wellness.overlay3d.companion3d

import com.wellness.overlay3d.companion3d.CompanionJoint.CHEST
import com.wellness.overlay3d.companion3d.CompanionJoint.HEAD
import com.wellness.overlay3d.companion3d.CompanionJoint.SPINE
import com.wellness.overlay3d.companion3d.CompanionJoint.WRIST_LEFT
import com.wellness.overlay3d.companion3d.CompanionJoint.WRIST_RIGHT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanRigTest {
    private fun joints(nodes: List<String?>) = CompanionJoint.entries.associateWith { HumanRig.findJoint(nodes, it) }

    @Test fun bundledScanRigDrivesSpineChestAndHeadButNeverHips() {
        // Node order of assets/models/companion.glb.
        val nodes = listOf("Body", "Character", "Hips", "Spine", "Chest", "Head")
        assertEquals(
            mapOf(HEAD to 5, CHEST to 4, SPINE to 3, WRIST_LEFT to -1, WRIST_RIGHT to -1),
            joints(nodes)
        )
    }

    @Test fun mixamoSkeletonPicksTheRealBonesOverEndsAndFingers() {
        val nodes = listOf(
            "mixamorig:Hips", "mixamorig:Spine", "mixamorig:Spine1", "mixamorig:Spine2", "mixamorig:Neck",
            "mixamorig:HeadTop_End", "mixamorig:Head", "mixamorig:LeftHandIndex1", "mixamorig:LeftHand",
            "mixamorig:LeftForeArm", "mixamorig:RightHandThumb1", "mixamorig:RightHand"
        )
        assertEquals(
            mapOf(HEAD to 6, CHEST to 3, SPINE to 1, WRIST_LEFT to 8, WRIST_RIGHT to 11),
            joints(nodes)
        )
    }

    @Test fun quaterniusSkeletonPrefersWristsAndFallsBackToAbdomenForSpine() {
        val nodes = listOf(
            "Root", "Hips", "Abdomen", "Chest", "Neck", "Head", "Head_end",
            "Hand.L", "Wrist.L", "Index1.L", "Wrist.R", "Index1.R"
        )
        assertEquals(
            mapOf(HEAD to 5, CHEST to 3, SPINE to 2, WRIST_LEFT to 8, WRIST_RIGHT to 10),
            joints(nodes)
        )
    }

    @Test fun unknownNamesMatchNoJointAndNoMove() {
        val nodes = listOf(null, "", "Body", "Character", "Hips", "Foot.L", "Eye_R")
        CompanionJoint.entries.forEach { assertEquals(it.name, -1, HumanRig.findJoint(nodes, it)) }
        assertTrue(HumanRig.resolveMoves(listOf("Armature|mixamo.com|Layer0", "Take 001", "", null)).isEmpty())
    }

    @Test fun clipNamesMapToMovesSkippingWeaponsAndPreferringPlainNames() {
        val clips = listOf(
            "CharacterArmature|Idle_Gun", "CharacterArmature|Run", "CharacterArmature|Idle_Neutral",
            "Standing Idle", "CharacterArmature|Idle", "Walk", "Wave", "Interact", "mixamo.com", null
        )
        assertEquals(
            mapOf(
                CompanionMove.IDLE to 4, CompanionMove.IDLE_ALT to 2, CompanionMove.WAVE to 6,
                CompanionMove.WALK to 5, CompanionMove.GESTURE to 7
            ),
            HumanRig.resolveMoves(clips)
        )
    }

    @Test fun missingMovesFallBackToARelatedClipButNeverInventAWalk() {
        val moves = mapOf(CompanionMove.IDLE to 0, CompanionMove.GESTURE to 3)
        assertEquals(0, HumanRig.clipFor(CompanionMove.IDLE_ALT, moves))
        assertEquals(3, HumanRig.clipFor(CompanionMove.WAVE, moves))
        assertEquals(-1, HumanRig.clipFor(CompanionMove.WALK, moves))
        assertEquals(-1, HumanRig.clipFor(CompanionMove.IDLE, emptyMap()))
    }
}
