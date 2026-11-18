package com.gia.aicomposer.pose

import com.gia.aicomposer.model.SceneState
import kotlin.random.Random

data class PoseSuggestion(
    val pose: PoseTemplate,
    val score: Float,
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

    internal data class Scored(
        val pose: PoseTemplate,
        val score: Float,
        val eligible: Boolean,
    )

    fun suggest(scene: SceneState, recentPoseIds: List<String>): PoseSuggestion {
        val candidates = PoseLibrary.poses.map { score(it, scene, recentPoseIds) }
        // Dáng thiếu affordance bắt buộc vẫn có thể dùng khi không còn lựa chọn nào khác.
        val pool = candidates.filter { it.eligible }.ifEmpty { candidates }

        val best = pool.maxOf { it.score }
        val band = pool.filter { it.score >= best - VARIETY_BAND }
        val chosen = band[random.nextInt(band.size)]
        return PoseSuggestion(chosen.pose, chosen.score)
    }

    internal fun score(pose: PoseTemplate, scene: SceneState, recentPoseIds: List<String>): Scored {
        val present = scene.affordances
        if (!present.containsAll(pose.requiredAffordances)) {
            return Scored(pose, 0f, eligible = false)
        }

        var score = MATCH_REQUIRED * pose.requiredAffordances.size
        score += MATCH_ENCOURAGED * pose.encouragedAffordances.count { it in present }

        if (pose.category == PoseCategory.FULL_BODY && scene.tallestOpenRegion < MIN_FULL_BODY_REGION) {
            score -= FULL_BODY_NOT_ENOUGH_ROOM
        }

        if (scene.isFrontBusy && pose.category == PoseCategory.HALF_BODY) {
            score += HALF_BODY_BUSY_BONUS
        }

        if (pose.id in recentPoseIds.takeLast(RECENT_MEMORY)) {
            score -= RECENT_PENALTY
        }

        return Scored(pose, score, eligible = true)
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
    }
}
