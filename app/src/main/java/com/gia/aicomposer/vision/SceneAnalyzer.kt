package com.gia.aicomposer.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.gia.aicomposer.model.SceneState
import com.gia.aicomposer.util.rotatedSize
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * Nhận diện cảnh bằng MediaPipe EfficientDet Lite0 (model bundle trong assets).
 * Chạy live-stream trên một executor riêng; ImageAnalysis dùng KEEP_ONLY_LATEST nên
 * khung cũ bị bỏ thay vì làm nghẽn camera.
 */
class SceneAnalyzer(
    context: Context,
    private val onScene: (SceneState) -> Unit,
) {
    private val appContext = context.applicationContext
    private var detector: ObjectDetector? = null

    @Volatile
    private var frameWidth = 0

    @Volatile
    private var frameHeight = 0

    @Volatile
    private var closed = false

    fun initialize(): Boolean {
        if (detector != null) return true
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(Delegate.CPU)
                .build()
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setMaxResults(MAX_RESULTS)
                .setScoreThreshold(SCORE_THRESHOLD)
                .setResultListener { result, input ->
                    try {
                        val scene = SceneBuilder.build(result.detections(), frameWidth, frameHeight)
                        if (!closed) onScene(scene)
                    } catch (t: Throwable) {
                        Log.w(TAG, "scene post-processing failed", t)
                    } finally {
                        runCatching { input.close() }
                    }
                }
                .setErrorListener { error -> Log.w(TAG, "object detector error", error) }
                .build()

            detector = ObjectDetector.createFromOptions(appContext, options)
            true
        } catch (t: Throwable) {
            // Không có model/cặp thư viện phù hợp -> app vẫn chạy bằng thư viện dáng mẫu.
            Log.w(TAG, "EfficientDet Lite0 unavailable, using offline pose library only", t)
            detector = null
            false
        }
    }

    fun submit(bitmap: Bitmap, rotationDegrees: Int, timestampMs: Long) {
        if (closed) return
        val activeDetector = detector ?: return
        val (width, height) = rotatedSize(bitmap.width, bitmap.height, rotationDegrees)
        frameWidth = width
        frameHeight = height

        val image = BitmapImageBuilder(bitmap).build()
        val options = ImageProcessingOptions.builder()
            .setRotationDegrees(rotationDegrees)
            .build()
        try {
            activeDetector.detectAsync(image, options, timestampMs)
        } catch (t: Throwable) {
            Log.w(TAG, "detectAsync failed", t)
            runCatching { image.close() }
        }
    }

    fun close() {
        closed = true
        runCatching { detector?.close() }
        detector = null
    }

    companion object {
        private const val TAG = "SceneAnalyzer"
        private const val MODEL_ASSET = "efficientdet_lite0.tflite"
        private const val MAX_RESULTS = 10
        private const val SCORE_THRESHOLD = 0.35f
    }
}
