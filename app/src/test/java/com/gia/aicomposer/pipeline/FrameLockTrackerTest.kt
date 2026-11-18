package com.gia.aicomposer.pipeline

import android.graphics.PointF
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hành vi của công thức trôi khung và hút tâm, tính bằng số cụ thể thay vì đọc hằng số:
 * 30° → đúng 0.55 × cạnh ngắn, hút dính < 5 dp, hút dần 5–25 dp (hệ số 0.9), > 25 dp đứng yên.
 *
 * Toạ độ chuẩn hoá 0..1 theo `contentRect`; view 1080×1920, density 3 (1 dp = 3 px) nên
 * 1 dp lệch tâm = 3 px = 3/1080 toạ độ chuẩn hoá.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FrameLockTrackerTest {

    private val density = 3f
    private val viewWidth = 1080
    private val viewHeight = 1920

    /** FIT_CENTER khớp đúng aspect nên nội dung phủ kín view. */
    private val content = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())

    private fun tracker() = FrameLockTracker(density)

    /** Khóa ở tâm rồi trả vị trí khung sau một lần cập nhật ở góc [yaw]. */
    private fun driftAt(
        yaw: Float,
        distanceFactor: Float = 1f,
        zoomRatio: Float = 1f,
        basePosture: DevicePosture? = DevicePosture(0f, 0f, true),
    ): PointF {
        val tracker = tracker()
        tracker.lock(
            center = PointF(0.5f, 0.5f),
            distanceFactor = distanceFactor,
            zoomFactor = 1f,
            posture = basePosture,
            nowMs = 0L,
        )
        return tracker.update(
            current = DevicePosture(yaw, 0f, true),
            angularVelocityDegPerSec = 0f,
            contentRect = content,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            zoomRatio = zoomRatio,
            nowMs = 33L,
        ) ?: throw AssertionError("khung phải còn khóa")
    }

    @Test
    fun thirtyDegreesMovesFrameExactly055OfShortSide() {
        // 0.55 × 1080 px = 594 px = 0.55 toạ độ chuẩn hoá.
        assertEquals(0.5f - 0.55f, driftAt(30f).x, 1e-4f)
        assertEquals(0.5f, driftAt(30f).y, 1e-4f)
    }

    @Test
    fun driftIsLinearInAngle() {
        assertEquals(0.5f - 0.275f, driftAt(15f).x, 1e-4f)
        assertEquals(0.5f + 0.275f, driftAt(-15f).x, 1e-4f)
    }

    @Test
    fun driftStopsAtThirtyDegrees() {
        val atThirty = driftAt(30f).x
        assertEquals(atThirty, driftAt(90f).x, 1e-4f)
        assertEquals(atThirty, driftAt(150f).x, 1e-4f)
        assertEquals(atThirty, driftAt(-330f).x, 1e-4f)
    }

    @Test
    fun driftDirectionFollowsScene() {
        // Xoay phải (yaw tăng) làm cảnh trượt trái → khung bám sang trái.
        assertEquals(-0.05f, driftAt(30f).x, 1e-4f)
        assertEquals(1.05f, driftAt(-30f).x, 1e-4f)
    }

    @Test
    fun distanceFactorScalesDrift() {
        // Ở 15°: 0.55 × (15/30) × 0.6 = 0.165
        assertEquals(0.5f - 0.165f, driftAt(15f, distanceFactor = 0.6f).x, 1e-4f)
        // Ở 15°: 0.55 × (15/30) × 1.4 = 0.385
        assertEquals(0.5f - 0.385f, driftAt(15f, distanceFactor = 1.4f).x, 1e-4f)
    }

    @Test
    fun distanceFactorIsClampedAtLockTime() {
        // Ngoài khoảng 0.6–1.4 bị kẹp, không cho khung trôi vô hạn.
        assertEquals(driftAt(30f, distanceFactor = 1.4f).x, driftAt(30f, distanceFactor = 5f).x, 1e-4f)
        assertEquals(driftAt(30f, distanceFactor = 0.6f).x, driftAt(30f, distanceFactor = 0f).x, 1e-4f)
    }

    @Test
    fun zoomInfluencesDriftBy06() {
        // zoom 2× → hệ số 1 + (2-1)×0.6 = 1.6; ở 10°: 0.55 × 1.6 × (10/30) = 0.29333
        assertEquals(0.5f - 0.293333f, driftAt(10f, zoomRatio = 2f).x, 1e-4f)
    }

    @Test
    fun withoutBasePostureThereIsNoDrift() {
        // Cảm biến chưa có mốc lúc khóa: mẫu hợp lệ đầu tiên thành mốc, khung đứng yên.
        assertEquals(0.5f, driftAt(30f, basePosture = null).x, 1e-4f)
        assertEquals(0.5f, driftAt(30f, basePosture = DevicePosture(0f, 0f, false)).x, 1e-4f)
    }

    @Test
    fun updateWithoutLockReturnsNull() {
        assertNull(
            tracker().update(
                current = DevicePosture(10f, 0f, true),
                angularVelocityDegPerSec = 0f,
                contentRect = content,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                zoomRatio = 1f,
                nowMs = 33L,
            ),
        )
    }

    @Test
    fun updateReturnsNullWhenContentRectIsEmpty() {
        val tracker = tracker()
        tracker.lock(PointF(0.5f, 0.5f), 1f, 1f, DevicePosture(0f, 0f, true), 0L)
        assertNull(
            tracker.update(
                current = DevicePosture(10f, 0f, true),
                angularVelocityDegPerSec = 0f,
                contentRect = RectF(),
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                zoomRatio = 1f,
                nowMs = 33L,
            ),
        )
    }

    @Test
    fun driftIsCappedAtQuarterFrame() {
        // distanceFactor 1.4 + zoom 2.5 → vượt biên; kết quả bị kẹp trong ±0.25.
        val tracker = tracker()
        tracker.lock(PointF(0.5f, 0.5f), 1.4f, 1f, DevicePosture(0f, 0f, true), 0L)
        val point = tracker.update(
            current = DevicePosture(30f, 0f, true),
            angularVelocityDegPerSec = 0f,
            contentRect = content,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            zoomRatio = 3f,
            nowMs = 33L,
        )!!
        assertEquals(-0.25f, point.x, 1e-4f)
    }

    @Test
    fun frameInside5DpIsPulledExactlyToCenter() {
        val snapped = tracker().snapToCenter(centerOffsetPx(4f), content, viewWidth, viewHeight)
        assertEquals(0.5f, snapped.x, 1e-5f)
        assertEquals(0.5f, snapped.y, 1e-5f)
    }

    @Test
    fun frameAt15DpIsPulledPartially() {
        // pull = 0.9 × (1 - (15-5)/20) = 0.45 → còn 55% độ lệch: 0.541666 → 0.522917
        val before = centerOffsetPx(15f)
        val snapped = tracker().snapToCenter(before, content, viewWidth, viewHeight)
        assertEquals(0.5229167f, snapped.x, 1e-4f)
        assertEquals(0.5f, snapped.y, 1e-5f)
    }

    @Test
    fun frameAt25DpAndBeyondIsNotPulled() {
        assertEquals(0.5694444f, tracker().snapToCenter(centerOffsetPx(25f), content, viewWidth, viewHeight).x, 1e-4f)
        assertEquals(0.5833333f, tracker().snapToCenter(centerOffsetPx(30f), content, viewWidth, viewHeight).x, 1e-4f)
    }

    @Test
    fun distanceFromCenterIsMeasuredInDp() {
        assertEquals(15f, tracker().distanceFromCenterDp(centerOffsetPx(15f), content, viewWidth, viewHeight), 1e-4f)
        assertEquals(0f, tracker().distanceFromCenterDp(PointF(0.5f, 0.5f), content, viewWidth, viewHeight), 1e-5f)
    }

    /** Tâm lệch [dp] dp sang phải so với tâm view, quy về toạ độ chuẩn hoá. */
    private fun centerOffsetPx(dp: Float) = PointF(0.5f + dp * density / viewWidth, 0.5f)
}
