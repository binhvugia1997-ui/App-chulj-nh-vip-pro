package com.gia.aicomposer.pipeline

import android.graphics.PointF
import android.graphics.RectF
import com.gia.aicomposer.util.PreviewGeometry
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Khóa khung + theo máy.
 *
 * Lúc khóa: ghi tâm khung và tư thế máy làm gốc. Khi xoay máy, khung trôi theo:
 *   độ trôi = góc xoay (giới hạn ±30°) × 0.55 × cạnh ngắn màn hình
 *              × hệ số khoảng cách (0.6–1.4) × hệ số zoom
 * Làm mượt thích ứng theo tốc độ xoay và bù dự đoán theo vận tốc: vận tốc xoay
 * được quy ra px/ms bằng đúng hệ số trôi ở trên, rồi nhân với khoảng dự đoán
 * ngắn (xem [PREDICTION_LOOKAHEAD_MS]) để bù độ trễ của cảm biến.
 * Hút dính tâm: < 5dp dính hẳn, 5–25dp hút dần (hệ số 0.9), > 25dp không hút.
 */
class FrameLockTracker(private val density: Float) {

    private data class Lock(
        val center: PointF,
        val distanceFactor: Float,
        val zoomFactor: Float,
    )

    private var lock: Lock? = null
    private var smoothedPx: PointF? = null
    private var lastUpdateMs = 0L

    /** Tư thế gốc; nếu cảm biến chưa sẵn sàng lúc khóa thì lấy mẫu hợp lệ đầu tiên. */
    private var basePosture: DevicePosture? = null

    fun lock(
        center: PointF,
        distanceFactor: Float,
        zoomFactor: Float,
        posture: DevicePosture?,
        nowMs: Long,
    ) {
        lock = Lock(
            center = PointF(center.x, center.y),
            distanceFactor = distanceFactor.coerceIn(MIN_DISTANCE_FACTOR, MAX_DISTANCE_FACTOR),
            zoomFactor = zoomFactor,
        )
        smoothedPx = null
        lastUpdateMs = nowMs
        basePosture = posture?.takeIf { it.valid }
    }

    fun unlocked() {
        lock = null
        smoothedPx = null
        lastUpdateMs = 0L
        basePosture = null
    }

    /** Trả tâm khung (chuẩn hoá) sau khi trôi + làm mượt; null nếu chưa khóa. */
    fun update(
        current: DevicePosture,
        angularVelocityDegPerSec: Float,
        contentRect: RectF,
        viewWidth: Int,
        viewHeight: Int,
        zoomRatio: Float,
        nowMs: Long,
    ): PointF? {
        val active = lock ?: return null
        if (contentRect.width() <= 0f || contentRect.height() <= 0f) return null

        val basePx = PreviewGeometry.toView(active.center, contentRect)
        if (basePosture == null && current.valid) basePosture = current
        val base = basePosture
        val hasBase = base != null && base.valid && current.valid

        val yawOffset = if (hasBase) shortestAngleDelta(current.yawDeg, base!!.yawDeg) else 0f
        val pitchOffset = if (hasBase) current.pitchDeg - base!!.pitchDeg else 0f
        val yaw = yawOffset.coerceIn(-MAX_DRIFT_ANGLE_DEG, MAX_DRIFT_ANGLE_DEG)
        val pitch = pitchOffset.coerceIn(-MAX_DRIFT_ANGLE_DEG, MAX_DRIFT_ANGLE_DEG)

        val shortSide = min(viewWidth, viewHeight).toFloat()
        val distanceFactor = active.distanceFactor.coerceIn(MIN_DISTANCE_FACTOR, MAX_DISTANCE_FACTOR)
        val zoomFactor = (1f + (zoomRatio - 1f) * ZOOM_INFLUENCE).coerceIn(0.8f, 2.5f)
        val gain = DRIFT_GAIN * shortSide * distanceFactor * zoomFactor

        // Xoay phải (yaw tăng) làm cảnh trượt sang trái -> khung bám theo cảnh.
        val driftX = -(yaw / MAX_DRIFT_ANGLE_DEG) * gain
        // Ngẩng máy (pitch tăng) làm cảnh trượt xuống -> khung trôi xuống.
        val driftY = (pitch / MAX_DRIFT_ANGLE_DEG) * gain

        val elapsed = if (lastUpdateMs == 0L) 0L else nowMs - lastUpdateMs
        lastUpdateMs = nowMs

        // Cùng một hệ số trôi cho cả vị trí và vận tốc: px mỗi độ xoay.
        val pxPerDegree = gain / MAX_DRIFT_ANGLE_DEG
        // °/s -> px/ms, rồi nhân với khoảng dự đoán để ra px cần bù về phía trước.
        val velocityPxPerMs = angularVelocityDegPerSec * pxPerDegree / 1000f
        // Không dự đoán xa hơn nhịp tính vừa rồi, và luôn dự đoán ít nhất một khung 60Hz.
        val lookaheadMs = elapsed.toFloat().coerceIn(MIN_LOOKAHEAD_MS, PREDICTION_LOOKAHEAD_MS)
        val prediction = velocityPxPerMs * lookaheadMs

        // Xoay phải (vận tốc dương) làm cảnh trượt sang trái -> khung phải đi trước sang trái.
        val targetPx = PointF(basePx.x + driftX - prediction, basePx.y + driftY)

        val speedNorm = (abs(angularVelocityDegPerSec) / SPEED_REFERENCE_DEG_PER_SEC).coerceIn(0f, 1f)
        val alpha = SMOOTH_SLOW + (SMOOTH_FAST - SMOOTH_SLOW) * speedNorm

        val previous = smoothedPx
        val smoothed = if (previous == null) {
            targetPx
        } else {
            PointF(
                previous.x + (targetPx.x - previous.x) * alpha,
                previous.y + (targetPx.y - previous.y) * alpha,
            )
        }
        smoothedPx = smoothed

        val normalized = PreviewGeometry.toNormalized(smoothed.x, smoothed.y, contentRect)
        return PointF(
            normalized.x.coerceIn(-DRIFT_LIMIT, 1f + DRIFT_LIMIT),
            normalized.y.coerceIn(-DRIFT_LIMIT, 1f + DRIFT_LIMIT),
        )
    }

    /** Hút dính tâm màn hình theo khoảng cách (dp). */
    fun snapToCenter(
        centerNorm: PointF,
        contentRect: RectF,
        viewWidth: Int,
        viewHeight: Int,
    ): PointF {
        if (contentRect.width() <= 0f || viewWidth <= 0 || viewHeight <= 0) return centerNorm
        val px = PreviewGeometry.toView(centerNorm, contentRect)
        val targetX = viewWidth / 2f
        val targetY = viewHeight / 2f
        val dx = px.x - targetX
        val dy = px.y - targetY
        val distanceDp = hypot(dx, dy) / density

        val pull = when {
            distanceDp <= SNAP_DEAD_ZONE_DP -> 1f
            distanceDp >= SNAP_FADE_END_DP -> 0f
            else -> SNAP_STRENGTH * (1f - (distanceDp - SNAP_DEAD_ZONE_DP) / (SNAP_FADE_END_DP - SNAP_DEAD_ZONE_DP))
        }
        val snapped = PointF(px.x + (targetX - px.x) * pull, px.y + (targetY - px.y) * pull)
        return PreviewGeometry.toNormalized(snapped.x, snapped.y, contentRect)
    }

    fun distanceFromCenterDp(
        centerNorm: PointF,
        contentRect: RectF,
        viewWidth: Int,
        viewHeight: Int,
    ): Float {
        if (contentRect.width() <= 0f) return Float.MAX_VALUE
        val px = PreviewGeometry.toView(centerNorm, contentRect)
        return hypot(px.x - viewWidth / 2f, px.y - viewHeight / 2f) / density
    }

    companion object {
        const val MAX_DRIFT_ANGLE_DEG = 30f
        const val DRIFT_GAIN = 0.55f
        const val MIN_DISTANCE_FACTOR = 0.6f
        const val MAX_DISTANCE_FACTOR = 1.4f
        const val ZOOM_INFLUENCE = 0.6f

        /** Khoảng dự đoán tối đa: ~3 khung hình ở 33ms, đủ bù độ trễ cảm biến mà không vọt xa. */
        const val PREDICTION_LOOKAHEAD_MS = 90f

        /** Sàn dự đoán cho khung hình đầu hoặc khi hai lần cập nhật quá sát nhau. */
        const val MIN_LOOKAHEAD_MS = 16f
        const val SMOOTH_SLOW = 0.35f
        const val SMOOTH_FAST = 0.90f
        const val SPEED_REFERENCE_DEG_PER_SEC = 120f
        const val SNAP_DEAD_ZONE_DP = 5f
        const val SNAP_FADE_END_DP = 25f
        const val SNAP_STRENGTH = 0.9f
        const val DRIFT_LIMIT = 0.25f
    }
}
