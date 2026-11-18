package com.gia.aicomposer.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.gia.aicomposer.util.PreviewGeometry
import com.gia.aicomposer.util.rotatedSize
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class FramingResult(
    /** Khung 3:4 chuẩn hoá 0..1 theo ảnh đã xoay đúng chiều. */
    val rect: RectF,
    /** Chiều cao chủ thể so với khung, dùng cho hệ số khoảng cách của drift. */
    val subjectHeightFraction: Float,
)

/**
 * Tìm khung 3:4 đẹp nhất khi máy đã yên:
 *  ưu tiên mặt (ML Kit Face Detection) -> người (MediaPipe Pose Landmarker) -> vùng nổi bật.
 * Điểm mỗi khung ứng viên = 40% độ tin cậy + 30% mặt nằm trong khung
 *                        + 20% tâm khung gần điểm 1/3 + 10% không sát mép.
 * Không tìm được gì thì trả khung giữa mặc định.
 */
class FramingAnalyzer(
    context: Context,
    private val executor: Executor,
    private val onResult: (FramingResult) -> Unit,
) {
    private val appContext = context.applicationContext

    /**
     * Mỗi lúc chỉ một request đang chạy để khỏi dồn việc lên ML Kit.
     * ML Kit không phải lúc nào cũng gọi callback, nên mỗi request có một watchdog tự nhả cờ
     * thay vì treo làn phân tích vĩnh viễn.
     */
    private val requestLock = Any()
    private var busy = false

    /** Tăng mỗi lần request được cấp/thu hồi; callback của request cũ bị bỏ qua. */
    private var generation = 0

    private var watchdogTask: ScheduledFuture<*>? = null

    private val watchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "framing-watchdog").apply { isDaemon = true }
        }

    private val faceDetector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(0.10f)
            .build(),
    )

    private val poseLandmarker: PoseLandmarker? = try {
        PoseLandmarker.createFromOptions(
            appContext,
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(POSE_MODEL_ASSET)
                        .setDelegate(Delegate.CPU)
                        .build(),
                )
                .setRunningMode(RunningMode.IMAGE)
                .setNumPoses(1)
                .build(),
        )
    } catch (t: Throwable) {
        Log.w(TAG, "pose landmarker unavailable, falling back to saliency", t)
        null
    }

    /** Mọi thứ một lượt tìm khung cần, để callback không phải mang 7 tham số. */
    private data class Request(
        val id: Int,
        val bitmap: Bitmap,
        val rotationDegrees: Int,
        val frameWidth: Int,
        val frameHeight: Int,
        val normAspect: Float,
    )

    private data class Candidate(
        val rect: RectF,
        val confidence: Float,
        val focus: RectF,
        val subjectHeight: Float,
        val priorityBonus: Float,
    )

    fun submit(bitmap: Bitmap, rotationDegrees: Int) {
        val request = beginRequest(bitmap, rotationDegrees) ?: return
        try {
            val input = InputImage.fromBitmap(bitmap, rotationDegrees)
            faceDetector.process(input)
                .addOnSuccessListener(executor) { faces -> finish(request, faces) }
                .addOnFailureListener(executor) { finish(request, emptyList()) }
        } catch (t: Throwable) {
            Log.w(TAG, "face detection failed", t)
            finish(request, emptyList())
        }
    }

    /** Nhận request mới nếu đang rảnh; null khi request trước chưa xong. */
    private fun beginRequest(bitmap: Bitmap, rotationDegrees: Int): Request? = synchronized(requestLock) {
        if (busy) return null
        busy = true
        val id = ++generation
        watchdogTask = watchdog.schedule(
            { abandonRequest(id) },
            STUCK_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
        val (frameWidth, frameHeight) = rotatedSize(bitmap.width, bitmap.height, rotationDegrees)
        Request(
            id = id,
            bitmap = bitmap,
            rotationDegrees = rotationDegrees,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            normAspect = PreviewGeometry.normalizedAspect(
                pixelAspect = frameWidth.toFloat() / frameHeight.toFloat(),
                targetAspect = TARGET_ASPECT,
            ),
        )
    }

    /** Watchdog: ML Kit im lặng quá lâu thì bỏ request đó và mở đường cho request sau. */
    private fun abandonRequest(id: Int) {
        synchronized(requestLock) {
            if (id != generation || !busy) return
            busy = false
            generation++
            watchdogTask = null
            Log.w(TAG, "framing request timed out after ${STUCK_TIMEOUT_MS}ms")
        }
    }

    /** Nhận kết quả của request còn hiệu lực; false nếu đã bị watchdog bỏ. */
    private fun claim(id: Int): Boolean = synchronized(requestLock) {
        if (id != generation) return false
        busy = false
        generation++
        watchdogTask?.cancel(false)
        watchdogTask = null
        true
    }

    private fun finish(request: Request, faces: List<Face>) {
        // Kết quả về muộn sau khi watchdog đã bỏ request: không dùng, cũng không đụng cờ busy.
        if (!claim(request.id)) return
        try {
            val candidates = mutableListOf<Candidate>()

            faces.sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }
                .take(2)
                .forEach { face ->
                    val box = normalizeRect(face.boundingBox, request.frameWidth, request.frameHeight)
                    val heightFraction = (box.height() * FACE_TO_SUBJECT_RATIO).coerceIn(0.25f, 0.98f)
                    candidates += Candidate(
                        rect = candidateRect(
                            centerX = box.centerX(),
                            centerY = box.centerY() + box.height() * 0.6f,
                            heightFraction = heightFraction,
                            normAspect = request.normAspect,
                        ),
                        confidence = FACE_CONFIDENCE,
                        focus = box,
                        subjectHeight = heightFraction,
                        priorityBonus = FACE_PRIORITY_BONUS,
                    )
                }

            if (faces.isEmpty()) poseCandidate(request)?.let { candidates += it }
            saliencyCandidate(request)?.let { candidates += it }

            val best = candidates.maxByOrNull { score(it) }
            if (best == null || score(best) < MIN_ACCEPTABLE_SCORE) {
                onResult(centerResult(request.normAspect))
            } else {
                onResult(FramingResult(best.rect, best.subjectHeight))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "framing search failed", t)
            onResult(centerResult(request.normAspect))
        }
    }

    private fun score(candidate: Candidate): Float {
        val inside = intersectionRatio(candidate.rect, candidate.focus)
        val thirds = thirdsScore(candidate.focus.centerX(), candidate.focus.centerY())
        val inset = min(
            min(candidate.rect.left, candidate.rect.top),
            min(1f - candidate.rect.right, 1f - candidate.rect.bottom),
        )
        val edgeScore = (inset / 0.05f).coerceIn(0f, 1f)
        return 0.40f * candidate.confidence +
            0.30f * inside +
            0.20f * thirds +
            0.10f * edgeScore +
            candidate.priorityBonus
    }

    private fun poseCandidate(request: Request): Candidate? {
        val landmarker = poseLandmarker ?: return null
        return try {
            val image = BitmapImageBuilder(request.bitmap).build()
            val result = try {
                landmarker.detect(
                    image,
                    ImageProcessingOptions.builder()
                        .setRotationDegrees(request.rotationDegrees)
                        .build(),
                )
            } finally {
                runCatching { image.close() }
            }
            val landmarks = result.landmarks().firstOrNull() ?: return null

            var minX = 1f
            var minY = 1f
            var maxX = 0f
            var maxY = 0f
            var visibilitySum = 0f
            var visibleCount = 0
            for (landmark in landmarks) {
                val visibility = landmark.visibility().orElse(1f)
                if (visibility < VISIBILITY_THRESHOLD) continue
                minX = min(minX, landmark.x())
                minY = min(minY, landmark.y())
                maxX = max(maxX, landmark.x())
                maxY = max(maxY, landmark.y())
                visibilitySum += visibility
                visibleCount++
            }
            if (visibleCount < MIN_POSE_LANDMARKS || maxX <= minX || maxY <= minY) return null

            val body = RectF(minX, minY, maxX, maxY)
            val bodyHeight = (body.height() * 1.15f).coerceIn(0.3f, 0.98f)
            Candidate(
                rect = candidateRect(body.centerX(), body.centerY(), bodyHeight, request.normAspect),
                confidence = (visibilitySum / visibleCount).coerceIn(0f, 1f),
                focus = body,
                subjectHeight = bodyHeight,
                priorityBonus = PERSON_PRIORITY_BONUS,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "pose landmarker failed", t)
            null
        }
    }

    private fun saliencyCandidate(request: Request): Candidate? = try {
        val center = SaliencyFinder.brightestBusyCell(request.bitmap, request.rotationDegrees)
        if (center == null) {
            null
        } else {
            val rect = candidateRect(
                centerX = center.first,
                centerY = center.second,
                heightFraction = SALIENCE_HEIGHT,
                normAspect = request.normAspect,
            )
            Candidate(
                rect = rect,
                confidence = SALIENCE_CONFIDENCE,
                focus = rect,
                subjectHeight = SALIENCE_HEIGHT,
                priorityBonus = 0f,
            )
        }
    } catch (t: Throwable) {
        Log.w(TAG, "saliency failed", t)
        null
    }

    private fun candidateRect(
        centerX: Float,
        centerY: Float,
        heightFraction: Float,
        normAspect: Float,
    ): RectF {
        val height = heightFraction.coerceIn(0.25f, 0.98f)
        val width = (height * normAspect).coerceIn(0.2f, 0.98f)
        val left = (centerX - width / 2f).coerceIn(0f, 1f - width)
        val top = (centerY - height / 2f).coerceIn(0f, 1f - height)
        return RectF(left, top, left + width, top + height)
    }

    private fun centerResult(normAspect: Float): FramingResult = FramingResult(
        rect = candidateRect(0.5f, 0.5f, DEFAULT_HEIGHT, normAspect),
        subjectHeightFraction = DEFAULT_HEIGHT,
    )

    private fun normalizeRect(rect: Rect, frameWidth: Int, frameHeight: Int): RectF = RectF(
        (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f),
        (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f),
        (rect.right.toFloat() / frameWidth).coerceIn(0f, 1f),
        (rect.bottom.toFloat() / frameHeight).coerceIn(0f, 1f),
    )

    private fun intersectionRatio(rect: RectF, focus: RectF): Float {
        val width = max(0f, min(rect.right, focus.right) - max(rect.left, focus.left))
        val height = max(0f, min(rect.bottom, focus.bottom) - max(rect.top, focus.top))
        val focusArea = focus.width() * focus.height()
        if (focusArea <= 0f) return 0f
        return ((width * height) / focusArea).coerceIn(0f, 1f)
    }

    private fun thirdsScore(x: Float, y: Float): Float {
        var best = Float.MAX_VALUE
        for (px in THIRD_POINTS) {
            for (py in THIRD_POINTS) {
                best = min(best, abs(x - px) + abs(y - py))
            }
        }
        return (1f - best / 0.5f).coerceIn(0f, 1f)
    }

    fun close() {
        synchronized(requestLock) {
            busy = false
            generation++
            watchdogTask?.cancel(false)
            watchdogTask = null
        }
        watchdog.shutdownNow()
        runCatching { faceDetector.close() }
        runCatching { poseLandmarker?.close() }
    }

    companion object {
        private const val TAG = "FramingAnalyzer"
        private const val POSE_MODEL_ASSET = "pose_landmarker_lite.task"
        private const val TARGET_ASPECT = 3f / 4f
        private const val DEFAULT_HEIGHT = 0.86f
        private const val FACE_TO_SUBJECT_RATIO = 4.2f
        private const val FACE_CONFIDENCE = 0.92f
        private const val FACE_PRIORITY_BONUS = 0.15f
        private const val PERSON_PRIORITY_BONUS = 0.10f
        private const val SALIENCE_HEIGHT = 0.72f
        private const val SALIENCE_CONFIDENCE = 0.45f
        private const val MIN_ACCEPTABLE_SCORE = 0.25f
        private const val VISIBILITY_THRESHOLD = 0.5f
        private const val MIN_POSE_LANDMARKS = 8

        private val THIRD_POINTS = floatArrayOf(1f / 3f, 2f / 3f)

        /** Một lượt face detection bình thường mất dưới 200ms; quá mốc này coi như mất callback. */
        private const val STUCK_TIMEOUT_MS = 2_000L
    }
}
