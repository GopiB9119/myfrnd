package com.wellness.overlay3d.companion3d

/** Things the companion can do with its body, independent of how a model names its clips. */
enum class CompanionMove { IDLE, IDLE_ALT, WAVE, WALK, GESTURE }

enum class CompanionJoint { HEAD, CHEST, SPINE, WRIST_LEFT, WRIST_RIGHT }

/**
 * Maps clip and joint names from any humanoid glTF (Quaternius, Mixamo, Ready Player Me, Blender
 * exports, ...) onto [CompanionMove] / [CompanionJoint], so `assets/models/companion.glb` can be
 * swapped for another rigged human without code changes.
 */
object HumanRig {

    private val moveAliases: Map<CompanionMove, List<String>> = mapOf(
        CompanionMove.IDLE to listOf("idle", "breathing", "breathe", "stand", "standing", "rest"),
        CompanionMove.IDLE_ALT to listOf("neutral", "relaxed", "bored", "looking", "look"),
        CompanionMove.WAVE to listOf("wave", "waving", "hello", "greet", "greeting"),
        CompanionMove.WALK to listOf("walk", "walking", "stroll"),
        CompanionMove.GESTURE to listOf("interact", "talk", "talking", "yes", "nod", "point", "pointing", "explain", "drink")
    )

    /** Clips that never make sense for a wellness buddy, even if they also contain an idle/walk word. */
    private val excludedTokens = setOf(
        "gun", "pistol", "rifle", "sword", "weapon", "shoot", "shooting", "death", "die", "dead",
        "hit", "recieve", "receive", "punch", "kick", "attack", "slash", "run", "running"
    )

    /** Prefixes that exporters glue onto clip/joint names and that carry no meaning. */
    private val noiseTokens = setOf(
        "armature", "character", "mixamorig", "mixamo", "com", "action", "anim", "animation",
        "layer0", "baselayer", "skeleton", "rig", "def", "bip", "bip01", "jnt"
    )

    private val camelCaseBoundary = Regex("([a-z0-9])([A-Z])")
    private val separators = Regex("[^a-z0-9]+")

    fun tokens(name: String): List<String> = name
        .replace(camelCaseBoundary, "$1 $2")
        .lowercase()
        .split(separators)
        .filter { it.isNotEmpty() }

    /** Returns the best clip index for every move that a matching clip exists for. */
    fun resolveMoves(clipNames: List<String?>): Map<CompanionMove, Int> {
        val result = linkedMapOf<CompanionMove, Int>()
        val tokenized = clipNames.map { name -> tokens(name.orEmpty()).filterNot { it in noiseTokens } }
        val idleAltAliases = moveAliases.getValue(CompanionMove.IDLE_ALT)
        for (move in CompanionMove.entries) {
            val aliases = moveAliases.getValue(move)
            var bestIndex = -1
            var bestScore = Int.MIN_VALUE
            tokenized.forEachIndexed { index, words ->
                if (words.isEmpty() || words.any { it in excludedTokens }) return@forEachIndexed
                val aliasRank = aliases.indexOfFirst { it in words }
                if (aliasRank < 0) return@forEachIndexed
                // A "neutral/look" idle is the alternate idle, never the main one.
                if (move == CompanionMove.IDLE && words.any { it in idleAltAliases }) return@forEachIndexed
                val score = 100 - aliasRank * 10 - (words.size - 1) * 3
                if (score > bestScore) {
                    bestScore = score
                    bestIndex = index
                }
            }
            if (bestIndex >= 0) result[move] = bestIndex
        }
        return result
    }

    /** Picks the clip to play for [move], falling back to a related clip when a model lacks it. */
    fun clipFor(move: CompanionMove, moves: Map<CompanionMove, Int>): Int {
        val chain = when (move) {
            CompanionMove.IDLE -> listOf(CompanionMove.IDLE, CompanionMove.IDLE_ALT)
            CompanionMove.IDLE_ALT -> listOf(CompanionMove.IDLE_ALT, CompanionMove.IDLE)
            CompanionMove.WAVE -> listOf(CompanionMove.WAVE, CompanionMove.GESTURE)
            CompanionMove.GESTURE -> listOf(CompanionMove.GESTURE, CompanionMove.WAVE)
            CompanionMove.WALK -> listOf(CompanionMove.WALK)
        }
        return chain.firstNotNullOfOrNull { moves[it] } ?: -1
    }

    /** Index of the node best matching [joint], or -1. Unnamed nodes are skipped. */
    fun findJoint(nodeNames: List<String?>, joint: CompanionJoint): Int {
        var bestIndex = -1
        var bestScore = -1
        nodeNames.forEachIndexed { index, name ->
            if (name.isNullOrBlank()) return@forEachIndexed
            val words = tokens(name).filterNot { it in noiseTokens }
            if (words.any { it == "end" || it == "top" || it == "nub" || it == "tip" }) return@forEachIndexed
            val score = when (joint) {
                CompanionJoint.HEAD -> if ("head" in words) 100 - words.size else -1
                CompanionJoint.CHEST -> when {
                    "chest" in words -> 100 - words.size
                    "spine2" in words || "spine03" in words -> 90 - words.size
                    else -> -1
                }
                CompanionJoint.SPINE -> when {
                    "spine" in words -> 100 - words.size
                    "spine1" in words || "spine01" in words -> 90 - words.size
                    "abdomen" in words -> 80 - words.size
                    else -> -1
                }
                CompanionJoint.WRIST_LEFT -> wristScore(words, left = true)
                CompanionJoint.WRIST_RIGHT -> wristScore(words, left = false)
            }
            if (score > bestScore) {
                bestScore = score
                bestIndex = index
            }
        }
        return bestIndex
    }

    private val fingerPrefixes = listOf("index", "thumb", "middle", "ring", "pinky")

    private fun wristScore(words: List<String>, left: Boolean): Int {
        val isWrist = "wrist" in words
        if (!isWrist && "hand" !in words) return -1
        if (words.any { word -> fingerPrefixes.any { word.startsWith(it) } }) return -1
        val side = if (left) setOf("l", "left") else setOf("r", "right")
        if (words.none { it in side }) return -1
        return (if (isWrist) 100 else 90) - words.size
    }
}
