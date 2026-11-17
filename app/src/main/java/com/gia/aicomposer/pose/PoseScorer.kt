package com.gia.aicomposer.pose

import com.gia.aicomposer.model.SceneState
import kotlin.random.Random

data class PoseSuggestion(
    val pose: PoseTemplate,
    val score: Float,
    val reasons: List<String>,
)

/**
 * Chọn dáng bằng chấm điểm có trọng số:
 *  +2 cho mỗi affordance khuyến khích khớp,
 *  +1.5 cho mỗi affordance bắt buộc khớp (thiếu affordance bắt buộc thì dáng không hợp lệ),
 *  -1.5 cho FULL_BODY khi vùng trống cao < 0.5 khung,
 *  +1.5 cho HALF_BODY khi nền trước lộn xộn (vật gần che > 35% khung),
 *  -4 nếu dáng vừa gợi ý trong 3 lần gần nhất.
 * Chọn ngẫu nhiên trong nhóm điểm tốt nhất để đổi dáng mỗi lần bấm.
 */
class PoseScorer(private val random: Random = Random.Default) {

    private data class Scored(
        val pose: PoseTemplate,
        val score: Float,
        val eligible: Boolean,
        val reasons: MutableList<String> = mutableListOf(),
    )

    fun suggest(scene: SceneState, recentPoseIds: List<String>): PoseSuggestion {
        val candidates = PoseLibrary.poses.map { score(it, scene, recentPoseIds) }
        val eligible = candidates.filter { it.eligible }
        val pool = if (eligible.isNotEmpty()) eligible else candidates
        if (pool.isEmpty()) {
            val fallback = PoseLibrary.poses.first()
            return PoseSuggestion(fallback, 0f, listOf("fallback"))
        }

        val best = pool.maxOf { it.score }
        val band = pool.filter { it.score >= best - VARIETY_BAND }
        val chosen = band[random.nextInt(band.size)]
        return PoseSuggestion(chosen.pose, chosen.score, chosen.reasons.toList())
    }

    private fun score(pose: PoseTemplate, scene: SceneState, recentPoseIds: List<String>): Scored {
        val present = scene.affordances
        var score = 0f
        val reasons = mutableListOf<String>()

        val missingRequired = pose.requiredAffordances - present
        if (missingRequired.isNotEmpty()) {
            reasons += "thiếu ${missingRequired.joinToString { it.name.lowercase() }}"
            return Scored(pose, score, eligible = false, reasons = reasons)
        }
        if (pose.requiredAffordances.isNotEmpty()) {
            score += MATCH_REQUIRED * pose.requiredAffordances.size
            reasons += "khớp ${pose.requiredAffordances.joinToString { it.name.lowercase() }}"
        }

        val matchedEncouraged = pose.encouragedAffordances.intersect(present)
        if (matchedEncouraged.isNotEmpty()) {
            score += MATCH_ENCOURAGED * matchedEncouraged.size
            reasons += "khớp ${matchedEncouraged.joinToString { it.name.lowercase() }}"
        }

        if (pose.category == PoseCategory.FULL_BODY) {
            if (scene.tallestOpenRegion < MIN_FULL_BODY_REGION) {
                score -= FULL_BODY_NOT_ENOUGH_ROOM
                reasons += "thiếu vùng trống cao"
            }
        }

        if (scene.isFrontBusy && pose.category == PoseCategory.HALF_BODY) {
            score += HALF_BODY_BUSY_BONUS
            reasons += "nền trước lộn xộn"
        }

        if (recentPoseIds.takeLast(RECENT_MEMORY).contains(pose.id)) {
            score -= RECENT_PENALTY
            reasons += "vừa gợi ý"
        }

        return Scored(pose, score, eligible = true, reasons = reasons)
    }

    companion object {
        const val MATCH_ENCOURAGED = 2.0f
        const val MATCH_REQUIRED = 1.5f
        const val FULL_BODY_NOT_ENOUGH_ROOM = 1.5f
        const val MIN_FULL_BODY_REGION = 0.5f
        const val HALF_BODY_BUSY_BONUS = 1.5f
        const val RECENT_PENALTY = 4.0f
        const val RECENT_MEMORY = 3
        const val VARIETY_BAND = 0.5f
        const val BUSY_CLUTTER_RATIO = 0.35f
    }
}
