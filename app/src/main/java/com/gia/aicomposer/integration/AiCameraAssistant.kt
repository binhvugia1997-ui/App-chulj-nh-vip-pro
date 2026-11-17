package com.gia.aicomposer.integration

import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.gia.aicomposer.R
import com.gia.aicomposer.model.SceneState
import com.gia.aicomposer.overlay.PoseOverlayView
import com.gia.aicomposer.pipeline.CapturePipeline
import com.gia.aicomposer.pipeline.DeviceOrientation
import com.gia.aicomposer.pipeline.FrameLockTracker
import com.gia.aicomposer.pipeline.PipelineState
import com.gia.aicomposer.pipeline.PipelineUi
import com.gia.aicomposer.pipeline.StabilityMonitor
import com.gia.aicomposer.pose.PoseScorer
import com.gia.aicomposer.pose.PoseSuggestion
import com.gia.aicomposer.pose.PoseTemplate
import com.gia.aicomposer.settings.AppSettings
import com.gia.aicomposer.util.toArgbBitmap
import com.gia.aicomposer.vision.FramingAnalyzer
import com.gia.aicomposer.vision.SceneAnalyzer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Điểm nối duy nhất cho toàn bộ tính năng AI của app.
 *
 * Lớp này tự sở hữu: overlay (hình que + khung 3:4 + vòng tâm), nhận diện cảnh, tìm khung,
 * ổn định theo cảm biến, khóa khung theo máy và pipeline 9 bước. Activity chỉ cần:
 *   1. tạo assistant sau khi có PreviewView,
 *   2. bind use case [imageAnalysis] cùng Preview/ImageCapture,
 *   3. gọi [onCameraOpened] sau khi bind xong,
 *   4. chuyển tiếp onStart/onStop/onDestroy và vài nút bấm.
 *
 * Overlay là View phủ trên preview, không phải use case CameraX nên không bao giờ lọt vào ảnh.
 */
class AiCameraAssistant(
    private val activity: ComponentActivity,
    private val previewView: PreviewView,
    overlayContainer: ViewGroup? = null,
    private val previewAspect: Float = DEFAULT_PREVIEW_ASPECT,
) {

    /** Callback cho UI của app; mọi hàm được gọi trên main thread. */
    interface Listener {
        /** Câu hướng dẫn của bước hiện tại, đã dịch sẵn. */
        fun onGuidance(text: String) {}

        /** Có dáng mới để hiện tên/điểm; overlay đã tự vẽ. */
        fun onPoseSuggested(suggestion: PoseSuggestion, scene: SceneState) {}

        /** Cài đặt bật/tắt gợi ý dáng vừa đổi. */
        fun onPoseSuggestionsToggled(enabled: Boolean) {}

        /** Trạng thái tự chụp đổi (kể cả khi pipeline tự tắt sau khi chụp xong). */
        fun onAutoCaptureChanged(enabled: Boolean) {}

        fun onCaptureSaved(uri: Uri) {}

        fun onCaptureFailed(message: String?) {}
    }

    var listener: Listener? = null

    val settings = AppSettings(activity)

    /** Overlay dùng chung: hình que + khung 3:4 + vòng tròn tâm. */
    val overlay: PoseOverlayView = PoseOverlayView(activity)

    /**
     * Use case phân tích khung hình. Bind nó cùng Preview/ImageCapture trong
     * `bindToLifecycle`. Assistant đã tự cấu hình 640x480 RGBA + KEEP_ONLY_LATEST + analyzer.
     */
    val imageAnalysis: ImageAnalysis

    var currentSuggestion: PoseSuggestion? = null
        private set

    val currentPose: PoseTemplate? get() = currentSuggestion?.pose

    private val density = activity.resources.displayMetrics.density
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val sceneExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val framingExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val poseScorer = PoseScorer()
    private val recentPoses = ArrayDeque<String>()

    @Volatile
    private var latestScene: SceneState = SceneState.empty()

    @Volatile
    private var cameraReady = false

    @Volatile
    private var released = false

    private var lastSceneRequestMs = 0L
    private var lastFramingRequestMs = 0L
    private var lastAutoCapture = false
    private var latestUi = PipelineUi(state = PipelineState.WAITING)

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    private val lockTracker = FrameLockTracker(density)
    private val deviceOrientation = DeviceOrientation(activity)
    private val stabilityMonitor = StabilityMonitor(activity)
    private val sceneAnalyzer = SceneAnalyzer(activity) { scene -> latestScene = scene }
    private val framingAnalyzer: FramingAnalyzer
    private val pipeline: CapturePipeline

    init {
        val host = overlayContainer ?: AiOverlayHost.ensureContainer(previewView)
        host.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.previewAspect = previewAspect
        overlay.showPose = settings.poseSuggestionsEnabled

        imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(analysisResolutionSelector())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        imageAnalysis.setAnalyzer(analysisExecutor) { proxy -> analyzeFrame(proxy) }

        // Không có model/cặp thư viện phù hợp thì false và app vẫn chạy bằng thư viện dáng mẫu.
        sceneAnalyzer.initialize()

        pipeline = CapturePipeline(
            settings = settings,
            tracker = lockTracker,
            orientation = deviceOrientation,
            zoomProvider = { camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f },
            onUi = ::render,
            onCaptureRequested = { capturePhoto() },
            onFinished = { listener?.onAutoCaptureChanged(false) },
        )

        framingAnalyzer = FramingAnalyzer(activity, framingExecutor) { result ->
            activity.runOnUiThread { pipeline.onFramingResult(result) }
        }

        stabilityMonitor.onSnapshot = { snapshot ->
            activity.runOnUiThread { pipeline.onMotion(snapshot) }
        }

        overlay.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            overlay.previewAspect = previewAspect
            pipeline.setViewport(view.width, view.height, previewAspect)
        }
    }

    // ------------------------------------------------------------ vòng đời

    /** Gọi trong `Activity.onStart()`: bật cảm biến và tiếp tục pipeline đang tạm dừng. */
    fun start() {
        if (released) return
        stabilityMonitor.start()
        deviceOrientation.start()
        if (cameraReady) pipeline.resume()
    }

    /**
     * Gọi trong `Activity.onStop()`: tắt cảm biến và **tạm dừng** pipeline.
     * Hộp thoại xin quyền, khóa màn hình hay chuyển app đều đi qua đây, nên không được
     * reset về [PipelineState.WAITING] — [start] sẽ đưa pipeline chạy tiếp.
     */
    fun stop() {
        stabilityMonitor.stop()
        deviceOrientation.stop()
        pipeline.pause()
    }

    /** Gọi trong `Activity.onDestroy()`: giải phóng model và executor. */
    fun release() {
        if (released) return
        released = true
        pipeline.stop()
        sceneAnalyzer.close()
        framingAnalyzer.close()
        imageAnalysis.clearAnalyzer()
        analysisExecutor.shutdown()
        sceneExecutor.shutdown()
        framingExecutor.shutdown()
    }

    // ------------------------------------------------------------ camera

    /** Gọi ngay sau khi tạo ImageCapture để assistant chụp được. */
    fun setImageCapture(capture: ImageCapture) {
        imageCapture = capture
    }

    /** Gọi sau `bindToLifecycle` để pipeline đọc được zoom ratio. */
    fun onCameraBound(boundCamera: Camera?) {
        camera = boundCamera
    }

    /** Gọi sau khi bind thành công: pipeline chuyển từ "mở camera" sang "chờ ổn định". */
    fun onCameraOpened() {
        cameraReady = true
        pipeline.onCameraOpened()
        if (settings.autoCaptureEnabled) pipeline.start(true)
    }

    // ------------------------------------------------------------ hành động

    /**
     * Nút "Gợi ý dáng": chấm điểm theo cảnh hiện tại rồi vẽ hình que lên overlay.
     * Trả null nếu người dùng đã tắt gợi ý dáng trong Cài đặt.
     */
    fun suggestPose(): PoseSuggestion? {
        if (!settings.poseSuggestionsEnabled) {
            toast(activity.getString(R.string.guidance_pose_disabled))
            return null
        }
        val suggestion = poseScorer.suggest(latestScene, recentPoses.toList())
        recentPoses.addLast(suggestion.pose.id)
        while (recentPoses.size > PoseScorer.RECENT_MEMORY) recentPoses.removeFirst()

        currentSuggestion = suggestion
        overlay.pose = suggestion.pose
        overlay.showPose = true
        listener?.onPoseSuggested(suggestion, latestScene)
        Log.d(TAG, "pose=${suggestion.pose.id} score=${suggestion.score} scene=${latestScene.summary}")
        return suggestion
    }

    /** Nút chụp tay: đi qua pipeline để dùng chung state/hướng dẫn. */
    fun captureNow() {
        if (!cameraReady) {
            toast(activity.getString(R.string.pipeline_opening))
            return
        }
        pipeline.captureNow()
    }

    /** Bật/tắt tự căn khung + tự chụp (dùng cho switch trên màn hình chính và trong Cài đặt). */
    fun setAutoCaptureEnabled(enabled: Boolean) {
        settings.autoCaptureEnabled = enabled
        if (!cameraReady) return
        if (enabled) pipeline.start(true) else pipeline.stop()
    }

    /** Mở hộp thoại cài đặt (gợi ý dáng / tự chụp / độ trễ). */
    fun showSettingsDialog() {
        AiSettingsDialog.show(activity, settings) { applyUserSettings() }
    }

    // ------------------------------------------------------------ nội bộ

    private fun applyUserSettings() {
        val poseEnabled = settings.poseSuggestionsEnabled
        overlay.pose = currentSuggestion?.pose
        overlay.showPose = poseEnabled && currentSuggestion != null
        listener?.onPoseSuggestionsToggled(poseEnabled)

        if (cameraReady) {
            if (settings.autoCaptureEnabled) pipeline.start(true) else pipeline.stop()
        }
        listener?.onAutoCaptureChanged(settings.autoCaptureEnabled)
        listener?.onGuidance(guidanceFor(latestUi))
    }

    /**
     * Một ImageAnalysis duy nhất phục vụ cả nhận diện cảnh (gợi ý dáng) và tìm khung.
     * KEEP_ONLY_LATEST + throttle giữ cho camera không bị nghẽn.
     */
    private fun analyzeFrame(proxy: ImageProxy) {
        try {
            if (released) return
            val now = SystemClock.elapsedRealtime()
            val needsScene = settings.poseSuggestionsEnabled &&
                now - lastSceneRequestMs >= SCENE_INTERVAL_MS
            val needsFraming = pipeline.state == PipelineState.FINDING_REGION &&
                now - lastFramingRequestMs >= FRAMING_INTERVAL_MS
            if (!needsScene && !needsFraming) return

            val bitmap = proxy.toArgbBitmap()
            val rotation = proxy.imageInfo.rotationDegrees
            if (needsScene) {
                lastSceneRequestMs = now
                sceneExecutor.execute { sceneAnalyzer.submit(bitmap, rotation, now) }
            }
            if (needsFraming) {
                lastFramingRequestMs = now
                framingExecutor.execute { framingAnalyzer.submit(bitmap, rotation) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "frame analysis failed", t)
        } finally {
            proxy.close()
        }
    }

    private fun capturePhoto() {
        val capture = imageCapture
        val file = createPhotoFile()
        if (!cameraReady || capture == null || file == null) {
            pipeline.onCaptureFailed("camera/storage not ready")
            listener?.onCaptureFailed("camera/storage not ready")
            return
        }

        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(activity),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "saved ${file.absolutePath}")
                    val uri = Uri.fromFile(file)
                    pipeline.onCaptureSaved()
                    listener?.onCaptureSaved(uri)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "takePicture failed", exception)
                    pipeline.onCaptureFailed(exception.message)
                    listener?.onCaptureFailed(exception.message)
                }
            },
        )
    }

    private fun createPhotoFile(): File? {
        val base = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: activity.filesDir
            ?: return null
        val dir = File(base, PHOTO_DIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        val name = "AIC_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.jpg"
        return File(dir, name)
    }

    private fun render(ui: PipelineUi) {
        latestUi = ui
        overlay.frameCenter = ui.frameCenter
        overlay.frameHeightFraction = ui.frameHeightFraction
        overlay.frameNormAspect = ui.frameNormAspect
        overlay.showFrame =
            ui.state.ordinal >= PipelineState.FINDING_REGION.ordinal &&
                ui.state != PipelineState.ERROR
        overlay.showCenterTarget =
            ui.state == PipelineState.READY || ui.state == PipelineState.CAPTURING
        overlay.aligned =
            ui.lockedOnTarget || ui.alignmentOffsetDp <= CapturePipeline.ALIGN_TOLERANCE_DP
        overlay.holdProgress = ui.holdProgress

        listener?.onGuidance(guidanceFor(ui))

        if (!ui.autoCapture && lastAutoCapture) listener?.onAutoCaptureChanged(false)
        lastAutoCapture = ui.autoCapture
    }

    private fun guidanceFor(ui: PipelineUi): String = when (ui.state) {
        PipelineState.READY -> when {
            ui.lockedOnTarget -> activity.getString(
                R.string.pipeline_counting,
                ui.countdownRemainingMs / 1000f,
            )
            ui.holdProgress > 0f -> activity.getString(R.string.pipeline_holding)
            else -> activity.getString(R.string.pipeline_ready)
        }
        PipelineState.ERROR -> activity.getString(
            R.string.pipeline_error,
            ui.errorMessage ?: activity.getString(R.string.pipeline_error_state),
        )
        else -> activity.getString(ui.state.guidanceRes)
    }

    private fun analysisResolutionSelector(): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

    private fun toast(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "AiCameraAssistant"

        /** Preview 4:3 ở chế độ dọc -> aspect hiển thị 3:4. */
        const val DEFAULT_PREVIEW_ASPECT = 0.75f

        private const val SCENE_INTERVAL_MS = 700L
        private const val FRAMING_INTERVAL_MS = 280L
        private const val ANALYSIS_WIDTH = 640
        private const val ANALYSIS_HEIGHT = 480
        private const val PHOTO_DIR = "AICameraComposer"
    }
}
