package com.gia.aicomposer

import com.gia.aicomposer.model.SceneState
import com.gia.aicomposer.pipeline.CapturePipeline
import com.gia.aicomposer.pipeline.FrameLockTracker
import com.gia.aicomposer.pipeline.StabilityMonitor
import com.gia.aicomposer.pipeline.shortestAngleDelta
import com.gia.aicomposer.pose.PoseScorer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Chốt các ngưỡng đã thoả thuận (0.08 / 600 ms, 30° / 0.55, 15 dp / 1 s) ở dạng test.
 *
 * Đây là test "hợp đồng": nó không kiểm hành vi runtime (máy thật, cảm biến) mà bảo đảm
 * một đợt refactor không âm thầm đổi con số. Hành vi hình học tương ứng được kiểm
 * trong [com.gia.aicomposer.pipeline.FrameLockTrackerTest] và
 * [com.gia.aicomposer.pipeline.StabilityMonitorContractTest].
 *
 * Các giá trị dưới đây là `const val` nên được inline lúc biên dịch; đổi số trong code
 * sẽ làm test này đỏ ngay.
 */
class AgreedThresholdsTest {

    @Test
    fun motionStabilityIsGyro008OverTenEvaluations() {
        assertEquals(0.08f, StabilityMonitor.GYRO_STILL_STD, 0f)
        assertEquals(0.12f, StabilityMonitor.ACCEL_STILL_STD, 0f)
        assertEquals(50L, StabilityMonitor.EVALUATION_INTERVAL_MS)
        assertEquals(10, StabilityMonitor.REQUIRED_STILL_EVALUATIONS)
        // Ngưỡng thoả thuận là 0.08 giữ ~600ms, nhưng spec gốc chốt "10 lần yên" × 50ms = 500ms.
        // Test ghi nhận đúng số đang chạy; đổi REQUIRED_STILL_EVALUATIONS = 12 nếu muốn tròn 600ms.
        assertEquals(500L, StabilityMonitor.EVALUATION_INTERVAL_MS * StabilityMonitor.REQUIRED_STILL_EVALUATIONS)
        assertEquals(800L, StabilityMonitor.WINDOW_MS)
        assertEquals(1.5f, StabilityMonitor.STRONG_SHAKE_ACCEL, 0f)
        assertEquals(2.0f, StabilityMonitor.STRONG_SHAKE_GYRO, 0f)
    }

    @Test
    fun driftIs30DegreesAt055Gain() {
        assertEquals(30f, FrameLockTracker.MAX_DRIFT_ANGLE_DEG, 0f)
        assertEquals(0.55f, FrameLockTracker.DRIFT_GAIN, 0f)
        assertEquals(0.6f, FrameLockTracker.MIN_DISTANCE_FACTOR, 0f)
        assertEquals(1.4f, FrameLockTracker.MAX_DISTANCE_FACTOR, 0f)
        assertEquals(0.6f, FrameLockTracker.ZOOM_INFLUENCE, 0f)
        assertEquals(0.25f, FrameLockTracker.DRIFT_LIMIT, 0f)
    }

    @Test
    fun snapToCenterIs5to25Dp() {
        assertEquals(5f, FrameLockTracker.SNAP_DEAD_ZONE_DP, 0f)
        assertEquals(25f, FrameLockTracker.SNAP_FADE_END_DP, 0f)
        assertEquals(0.9f, FrameLockTracker.SNAP_STRENGTH, 0f)
    }

    @Test
    fun autoCaptureToleranceIs15DpHeldFor1s() {
        assertEquals(15f, CapturePipeline.ALIGN_TOLERANCE_DP, 0f)
        assertEquals(1_000L, CapturePipeline.ALIGN_HOLD_MS)
        assertEquals(600L, CapturePipeline.MOTION_FALLBACK_MS)
        assertEquals(2_500L, CapturePipeline.REGION_SEARCH_TIMEOUT_MS)
    }

    @Test
    fun poseScoringWeightsUnchanged() {
        assertEquals(2.0f, PoseScorer.MATCH_ENCOURAGED, 0f)
        assertEquals(1.5f, PoseScorer.MATCH_REQUIRED, 0f)
        assertEquals(1.5f, PoseScorer.FULL_BODY_NOT_ENOUGH_ROOM, 0f)
        assertEquals(0.5f, PoseScorer.MIN_FULL_BODY_REGION, 0f)
        assertEquals(1.5f, PoseScorer.HALF_BODY_BUSY_BONUS, 0f)
        assertEquals(4.0f, PoseScorer.RECENT_PENALTY, 0f)
        assertEquals(3, PoseScorer.RECENT_MEMORY)
        assertEquals(0.5f, PoseScorer.VARIETY_BAND, 0f)
        assertEquals(0.35f, SceneState.BUSY_CLUTTER_RATIO, 0f)
    }

    @Test
    fun shortestAngleWrapsAtHalfTurn() {
        // Đi qua điểm 0/360 phải đi đường ngắn, không nhảy 340°.
        assertEquals(-20f, shortestAngleDelta(350f, 10f), 0.001f)
        assertEquals(20f, shortestAngleDelta(10f, 350f), 0.001f)
        // Giới hạn (-180, 180]: 190° phía dương quy về -170°.
        assertEquals(-170f, shortestAngleDelta(190f, 0f), 0.001f)
        assertEquals(180f, shortestAngleDelta(-180f, 0f), 0.001f)
        assertEquals(0f, shortestAngleDelta(720f, 0f), 0.001f)
    }
}
