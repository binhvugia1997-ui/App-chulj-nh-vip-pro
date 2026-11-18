package com.gia.aicomposer.pipeline

import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.StringRes
import com.gia.aicomposer.R
import com.gia.aicomposer.settings.AppSettings
import com.gia.aicomposer.util.PreviewGeometry
import com.gia.aicomposer.vision.FramingResult

/**
 * 9 bước: chờ -> mở camera -> chờ ổn định -> tìm vùng đẹp -> khóa mẫu ->
 * sẵn sàng chụp -> đang chụp -> đang lưu -> xong/lỗi.
 * Mỗi bước hiện một câu hướng dẫn ngắn (guidanceRes).
 */
enum class PipelineState(@StringRes val guidanceRes: Int) {
    WAITING(R.string.pipeline_waiting),
    OPENING_CAMERA(R.string.pipeline_opening),
    WAITING_STABLE(R.string.pipeline_waiting_stable),
    FINDING_REGION(R.string.pipeline_finding_region),
    LOCKING_SAMPLE(R.string.pipeline_locking_sample),
    READY(R.string.pipeline_ready),
    CAPTURING(R.string.pipeline_capturing),
    SAVING(R.string.pipeline_saving),
    DONE(R.string.pipeline_done),
    ERROR(R.string.pipeline_error_state),
}

data class PipelineUi(
    val state: PipelineState,
    val frameCenter: PointF = PointF(0.5f, 0.5f),
    val alignmentOffsetDp: Float = Float.MAX_VALUE,
    val holdProgress: Float = 0f,
    val countdownRemainingMs: Long = 0L,
    val lockedOnTarget: Boolean = false,
    val autoCapture: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Pipeline thống nhất: ổn định -> tìm vùng đẹp -> khóa khung -> theo máy -> tự chụp.
 * Toàn bộ overlay chỉ nằm trên Preview, ảnh chụp do ImageCapture (use case riêng) lấy.
 */
class CapturePipeline(
    private val settings: AppSettings,
    private val tracker: FrameLockTracker,
    private val orientation: DeviceOrientation,
    private val zoomProvider: () -> Float,
    /**
     * AF/AE đã hội tụ chưa. Mặc định `true` cho nơi không cần cổng này (test, host tự chụp tay);
     * [com.gia.aicomposer.integration.AiCameraAssistant] nối vào [FocusGate].
     */
    private val focusReady: () -> Boolean = { true },
    private val onUi: (PipelineUi) -> Unit,
    private val onCaptureRequested: () -> Unit,
    private val onFinished: () -> Unit = {},
) {

    var state: PipelineState = PipelineState.WAITING
        private set

    private val handler = Handler(Looper.getMainLooper())

    private var loopRunning = false
    private var stateSinceMs = 0L
    private var lastTickMs = 0L
    private var viewWidth = 0
    private var viewHeight = 0
    private var contentRect = RectF()
    private var motion = MotionSnapshot()
    private var lastShakeToken = 0L
    private var autoCapture = false

    /** Đang tạm dừng vì Activity rời foreground (hộp thoại quyền, khóa màn hình...). */
    private var paused = false

    private var frameCenter = PointF(0.5f, 0.5f)
    private var distanceFactor = 1f
    private var alignmentOffsetDp = Float.MAX_VALUE
    private var holdMs = 0L
    private var countdownMs = 0L
    private var lockedOnTarget = false
    private var errorMessage: String? = null
    private var regionSearchStartMs = 0L
    private var captureStartedMs = 0L

    fun setViewport(width: Int, height: Int, pixelAspect: Float) {
        viewWidth = width
        viewHeight = height
        contentRect = PreviewGeometry.contentRect(width, height, pixelAspect)
        emitUi()
    }

    /** Bật pipeline kèm tự chụp; tắt tự chụp thì dùng [stop]. */
    fun start() {
        autoCapture = true
        if (paused) {
            // Bật lại trong lúc tạm dừng: chỉ cần tiếp tục, không khởi động lại từ đầu.
            resume()
            return
        }
        if (state != PipelineState.WAITING && state != PipelineState.DONE && state != PipelineState.ERROR) {
            emitUi()
            return
        }
        errorMessage = null
        resetAlignment()
        lastShakeToken = motion.shakeToken
        setState(PipelineState.OPENING_CAMERA)
        startLoop()

        // Camera bind có thể chậm; quá 2s vẫn cho pipeline đi tiếp nếu camera đã sẵn sàng.
        handler.postDelayed({
            if (state == PipelineState.OPENING_CAMERA) setState(PipelineState.WAITING_STABLE)
        }, CAMERA_OPEN_TIMEOUT_MS)
    }

    /**
     * Tạm dừng khi Activity vào background nhưng chưa đóng: giữ nguyên bước hiện tại
     * và tự tiếp tục ở [resume], khác với [stop] là tắt hẳn.
     */
    fun pause() {
        if (paused) return
        if (state == PipelineState.WAITING || state == PipelineState.DONE || state == PipelineState.ERROR) {
            stopLoop()
            tracker.unlocked()
            return
        }
        paused = true
        stopLoop()
        resetAlignment()
    }

    /**
     * Tiếp tục sau [pause]. Quay lại bước "chờ ổn định" vì trong lúc dừng máy có thể
     * đã đổi tư thế và khung đã khóa không còn đúng.
     */
    fun resume() {
        if (!paused) return
        paused = false
        errorMessage = null
        resetAlignment()
        lastShakeToken = motion.shakeToken
        regionSearchStartMs = SystemClock.elapsedRealtime()
        setState(PipelineState.WAITING_STABLE)
        startLoop()
    }

    fun stop() {
        paused = false
        autoCapture = false
        resetAlignment()
        setState(PipelineState.WAITING)
        stopLoop()
    }

    fun onCameraOpened() {
        if (paused) {
            resume()
            return
        }
        if (state == PipelineState.OPENING_CAMERA) setState(PipelineState.WAITING_STABLE)
    }

    fun onMotion(snapshot: MotionSnapshot) {
        motion = snapshot
        if (snapshot.shakeToken != lastShakeToken) {
            lastShakeToken = snapshot.shakeToken
            tracker.unlocked()
            holdMs = 0
            countdownMs = 0
            lockedOnTarget = false
            if (state != PipelineState.WAITING && state != PipelineState.DONE && state != PipelineState.ERROR) {
                regionSearchStartMs = SystemClock.elapsedRealtime()
                setState(PipelineState.WAITING_STABLE)
            }
        }
        emitUi()
    }

    fun onFramingResult(result: FramingResult) {
        if (state != PipelineState.FINDING_REGION) return
        lockSample(result.rect, distanceFactorFor(result.subjectHeightFraction))
    }

    fun onCaptureSaved() {
        if (state != PipelineState.CAPTURING) return
        setState(PipelineState.SAVING)
        handler.postDelayed({
            if (state == PipelineState.SAVING) setState(PipelineState.DONE)
        }, SAVE_FLASH_MS)
        handler.postDelayed({
            if (state == PipelineState.DONE) {
                stop()
                onFinished()
            }
        }, DONE_RESET_MS)
    }

    fun onCaptureFailed(message: String?) {
        errorMessage = message
        setState(PipelineState.ERROR)
        handler.postDelayed({
            if (state == PipelineState.ERROR) {
                stop()
                onFinished()
            }
        }, ERROR_RESET_MS)
    }

    /** Chụp tay: không cần chờ căn khung. */
    fun captureNow() {
        if (paused) return
        if (state == PipelineState.CAPTURING || state == PipelineState.SAVING) return
        autoCapture = false
        tracker.unlocked()
        holdMs = 0
        countdownMs = 0
        lockedOnTarget = false
        captureStartedMs = SystemClock.elapsedRealtime()
        setState(PipelineState.CAPTURING)
        startLoop()
        onCaptureRequested()
    }

    private fun lockSample(rect: RectF, factor: Float) {
        distanceFactor = factor
        val center = PointF(rect.centerX(), rect.centerY())
        tracker.lock(
            center = center,
            distanceFactor = factor,
            zoomFactor = zoomProvider(),
            posture = orientation.posture,
            nowMs = SystemClock.elapsedRealtime(),
        )
        frameCenter = center
        setState(PipelineState.LOCKING_SAMPLE)
    }

    private fun startLoop() {
        if (loopRunning) return
        loopRunning = true
        lastTickMs = 0L
        handler.post(loopRunnable)
    }

    private fun stopLoop() {
        loopRunning = false
        handler.removeCallbacks(loopRunnable)
    }

    private val loopRunnable = object : Runnable {
        override fun run() {
            if (!loopRunning) return
            tick()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val delta = if (lastTickMs == 0L) 0L else (now - lastTickMs).coerceAtMost(150L)
        lastTickMs = now

        when (state) {
            PipelineState.WAITING_STABLE -> {
                val stable = motion.isStable ||
                    (!motion.hasSensors && now - stateSinceMs >= MOTION_FALLBACK_MS)
                if (stable) {
                    regionSearchStartMs = now
                    setState(PipelineState.FINDING_REGION)
                }
            }

            PipelineState.FINDING_REGION -> {
                if (now - regionSearchStartMs >= REGION_SEARCH_TIMEOUT_MS) {
                    // Không tìm được khung: lấy khung giữa mặc định.
                    lockSample(defaultCenterFrame(), 1f)
                }
            }

            PipelineState.LOCKING_SAMPLE -> {
                if (now - stateSinceMs >= LOCK_CONFIRM_MS) setState(PipelineState.READY)
            }

            PipelineState.READY -> updateAlignment(now, delta)

            PipelineState.CAPTURING -> {
                if (now - captureStartedMs > CAPTURE_TIMEOUT_MS) {
                    onCaptureFailed("timeout")
                }
            }

            else -> Unit
        }
        emitUi()
    }

    private fun updateAlignment(now: Long, delta: Long) {
        if (viewWidth <= 0 || viewHeight <= 0 || contentRect.width() <= 0f) return
        val raw = tracker.update(
            current = orientation.posture,
            angularVelocityDegPerSec = orientation.angularVelocityDegPerSec,
            contentRect = contentRect,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            zoomRatio = zoomProvider(),
            nowMs = now,
        ) ?: return

        alignmentOffsetDp = tracker.distanceFromCenterDp(raw, contentRect, viewWidth, viewHeight)
        frameCenter = tracker.snapToCenter(raw, contentRect, viewWidth, viewHeight)

        if (!autoCapture) {
            holdMs = 0
            countdownMs = 0
            lockedOnTarget = false
            return
        }

        // Phải vừa căn đúng tâm vừa có AF/AE hội tụ; thiếu một điều kiện là huỷ đếm như khi lệch.
        if (alignmentOffsetDp <= ALIGN_TOLERANCE_DP && focusReady()) {
            holdMs += delta
            if (holdMs >= ALIGN_HOLD_MS) {
                lockedOnTarget = true
                countdownMs += delta
                if (countdownMs >= settings.captureDelayMs) {
                    triggerCapture()
                }
            }
        } else {
            // Lệch ra khỏi dung sai -> hủy việc đếm.
            holdMs = 0
            countdownMs = 0
            lockedOnTarget = false
        }
    }

    private fun triggerCapture() {
        if (state != PipelineState.READY) return
        captureStartedMs = SystemClock.elapsedRealtime()
        setState(PipelineState.CAPTURING)
        holdMs = 0
        countdownMs = 0
        onCaptureRequested()
    }

    /** Xoá khung đang khóa + mọi tiến trình giữ/đếm; dùng chung cho start/pause/resume/stop. */
    private fun resetAlignment() {
        holdMs = 0
        countdownMs = 0
        lockedOnTarget = false
        alignmentOffsetDp = Float.MAX_VALUE
        tracker.unlocked()
    }

    private fun defaultCenterFrame(): RectF {
        val height = FRAME_HEIGHT_FRACTION
        val width = height * FRAME_NORM_ASPECT
        return RectF(0.5f - width / 2f, 0.5f - height / 2f, 0.5f + width / 2f, 0.5f + height / 2f)
    }

    private fun distanceFactorFor(subjectHeightFraction: Float): Float {
        val clamped = subjectHeightFraction.coerceIn(0.15f, 0.75f)
        val normalized = (clamped - 0.15f) / 0.60f
        return (FrameLockTracker.MAX_DISTANCE_FACTOR -
            normalized * (FrameLockTracker.MAX_DISTANCE_FACTOR - FrameLockTracker.MIN_DISTANCE_FACTOR))
            .coerceIn(FrameLockTracker.MIN_DISTANCE_FACTOR, FrameLockTracker.MAX_DISTANCE_FACTOR)
    }

    private fun setState(newState: PipelineState) {
        state = newState
        stateSinceMs = SystemClock.elapsedRealtime()
        if (newState != PipelineState.ERROR) errorMessage = null
        emitUi()
    }

    private fun emitUi() {
        onUi(
            PipelineUi(
                state = state,
                frameCenter = frameCenter,
                alignmentOffsetDp = alignmentOffsetDp,
                holdProgress = (holdMs.toFloat() / ALIGN_HOLD_MS).coerceIn(0f, 1f),
                countdownRemainingMs = (settings.captureDelayMs - countdownMs).coerceAtLeast(0L),
                lockedOnTarget = lockedOnTarget,
                autoCapture = autoCapture,
                errorMessage = errorMessage,
            ),
        )
    }

    companion object {
        const val FRAME_HEIGHT_FRACTION = 0.76f
        const val FRAME_NORM_ASPECT = 1f
        const val ALIGN_TOLERANCE_DP = 15f
        const val ALIGN_HOLD_MS = 1_000L
        const val TICK_INTERVAL_MS = 33L
        const val LOCK_CONFIRM_MS = 300L
        const val REGION_SEARCH_TIMEOUT_MS = 2_500L
        const val CAMERA_OPEN_TIMEOUT_MS = 2_000L
        const val MOTION_FALLBACK_MS = 600L
        const val CAPTURE_TIMEOUT_MS = 8_000L
        const val SAVE_FLASH_MS = 400L
        const val DONE_RESET_MS = 1_700L
        const val ERROR_RESET_MS = 2_500L
    }
}
