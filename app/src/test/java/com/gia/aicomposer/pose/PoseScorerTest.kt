package com.gia.aicomposer.pose

import android.graphics.RectF
import com.gia.aicomposer.model.DepthBand
import com.gia.aicomposer.model.SceneObject
import com.gia.aicomposer.model.SceneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Chấm điểm dáng: +1.5/affordance bắt buộc, +2/affordance khuyến khích, −1.5 khi FULL_BODY
 * không đủ chỗ (vùng trống cao < 0.5 khung), +1.5 cho HALF_BODY khi nền trước lộn xộn
 * (> 35% khung), −4 khi dáng vừa xuất hiện trong 3 lần gần nhất.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PoseScorerTest {

    private val scorer = PoseScorer(FixedRandom())

    private fun pose(id: String) = PoseLibrary.poses.first { it.id == id }

    /** Cảnh có ghế; [openHeight] điều khiển vùng trống nên cũng điều khiển OPEN_GROUND. */
    private fun chairScene(openHeight: Float = 1f, clutter: Float = 0f) = SceneState(
        objects = listOf(
            SceneObject(
                label = "chair",
                confidence = 0.9f,
                box = RectF(0.1f, 0.4f, 0.4f, 0.8f),
                depth = DepthBand.MID,
                affordances = setOf(Affordance.CHAIR),
            ),
        ),
        freeRegion = RectF(0f, 0f, 1f, openHeight),
        nearClutterRatio = clutter,
    )

    private fun openScene(openHeight: Float = 1f, clutter: Float = 0f) = SceneState(
        freeRegion = RectF(0f, 0f, 1f, openHeight),
        nearClutterRatio = clutter,
    )

    @Test
    fun requiredAffordanceIsWorthOneAndAHalf() {
        val scored = scorer.score(pose("fb_sit_chair"), chairScene(), emptyList())
        assertTrue(scored.eligible)
        assertEquals(1.5f, scored.score, 1e-4f)
    }

    @Test
    fun encouragedAffordanceIsWorthTwo() {
        val scored = scorer.score(pose("fb_stand"), openScene(), emptyList())
        assertTrue(scored.eligible)
        assertEquals(2.0f, scored.score, 1e-4f)
    }

    @Test
    fun fullBodyLosesPointsWhenOpenRegionIsUnderHalfFrame() {
        assertEquals(0.5f, scorer.score(pose("fb_stand"), openScene(openHeight = 0.49f), emptyList()).score, 1e-4f)
        // Đúng 0.5 khung vẫn còn đủ chỗ (ngưỡng là "< 0.5").
        assertEquals(2.0f, scorer.score(pose("fb_stand"), openScene(openHeight = 0.5f), emptyList()).score, 1e-4f)
    }

    @Test
    fun halfBodyIsNotPenalisedForLowOpenRegion() {
        // 0.49 khung: vẫn có OPEN_GROUND nhưng dưới ngưỡng 0.5 của FULL_BODY.
        assertEquals(0.5f, scorer.score(pose("fb_stand"), openScene(openHeight = 0.49f), emptyList()).score, 1e-4f)
        assertEquals(2.0f, scorer.score(pose("hb_portrait"), openScene(openHeight = 0.49f), emptyList()).score, 1e-4f)
    }

    @Test
    fun halfBodyGetsBonusOnBusyForeground() {
        // 0.36 > 0.35 → lộn xộn.
        assertEquals(3.5f, scorer.score(pose("hb_portrait"), openScene(clutter = 0.36f), emptyList()).score, 1e-4f)
        // Đúng 0.35 không tính là lộn xộn.
        assertEquals(2.0f, scorer.score(pose("hb_portrait"), openScene(clutter = 0.35f), emptyList()).score, 1e-4f)
    }

    @Test
    fun recentPoseIsPenalised() {
        assertEquals(-2.0f, scorer.score(pose("fb_stand"), openScene(), listOf("fb_stand")).score, 1e-4f)
        assertEquals(2.0f, scorer.score(pose("fb_stand"), openScene(), listOf("a", "b")).score, 1e-4f)
    }

    @Test
    fun onlyLastThreePicksAreRemembered() {
        // fb_stand ở vị trí thứ 4 tính từ cuối → đã quên.
        assertEquals(2.0f, scorer.score(pose("fb_stand"), openScene(), listOf("fb_stand", "a", "b", "c")).score, 1e-4f)
        // Nằm trong 3 lần cuối → trừ 4 điểm.
        assertEquals(-2.0f, scorer.score(pose("fb_stand"), openScene(), listOf("a", "b", "fb_stand")).score, 1e-4f)
    }

    @Test
    fun missingRequiredAffordanceMakesPoseIneligible() {
        val scored = scorer.score(pose("fb_lean_wall"), openScene(), emptyList())
        assertFalse(scored.eligible)
    }

    @Test
    fun suggestStaysInsideVarietyBandOfTheBestScore() {
        val scene = chairScene()
        val best = PoseLibrary.poses.maxOf { scorer.score(it, scene, emptyList()).score }
        repeat(20) { seed ->
            val suggestion = PoseScorer(Random(seed)).suggest(scene, emptyList())
            assertTrue(
                "gợi ý ${suggestion.pose.id} (${suggestion.score}) thấp hơn dải $VAR_BAND",
                suggestion.score >= best - PoseScorer.VARIETY_BAND,
            )
            assertTrue(suggestion.score <= best)
        }
    }

    @Test
    fun suggestNeverReturnsPoseWithMissingRequiredAffordance() {
        val scene = chairScene()
        repeat(10) { seed ->
            val suggestion = PoseScorer(Random(seed)).suggest(scene, emptyList())
            assertTrue(
                "dáng ${suggestion.pose.id} cần ${suggestion.pose.requiredAffordances}",
                scene.affordances.containsAll(suggestion.pose.requiredAffordances),
            )
        }
    }

    private class FixedRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }

    private companion object {
        const val VAR_BAND = 0.5f
    }
}
