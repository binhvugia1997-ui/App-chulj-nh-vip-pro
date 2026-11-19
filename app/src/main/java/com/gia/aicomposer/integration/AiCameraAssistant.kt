package com.gia.aicomposer.integration

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
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
import com.gia.aicomposer.overlay.CameraOverlayView
import com.gia.aicomposer.pipeline.CapturePipeline
import com.gia.aicomposer.pipeline.DeviceOrientation
import com.gia.aicomposer.pipeline.FocusGate
import com.gia.aicomposer.pipeline.FrameLockTracker
import com.gia.aicomposer.pipeline.PipelineState
import com.gia.aicomposer.pipeline.PipelineUi
import com.gia.aicomposer.pipeline.StabilityMonitor
import com.gia.aicomposer.pose.PoseScorer
import com.gia.aicomposer.pose.PoseSuggestion
import com.gia.aicomposer.settings.AppSettings
import com.gia.aicomposer.util.PhotoStore
import com.gia.aicomposer.util.toArgbBitmap
import com.gia.aicomposer.vision.FramingAnalyzer
import com.gia.aicomposer.vision.SceneAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Điểm nối duy nhất cho toàn bộ tính năng AI của app.
 *
 * Lớp này sở hữu: nhận diện cảnh, tìm khung, ổn định theo cảm biến, khóa khung theo máy và
 * pipeline 9 bước. Overlay duy nhất ([CameraOverlayView]) do activity khai báo trong layout và
 * truyền vào, nên thứ tự vẽ nằm ở XML chứ không bị chèn thêm FrameLayout lúc runtime.
 * Activity chỉ cần:
 *   1. truyền overlay + PreviewView khi tạo assistant,
 *   2. bind use case [imageAnalysis] cùng Preview/ImageCapture,
 *   3. gọi [onCameraOpened] sau khi bind xong,
 *   4. chuyển tiếp onStart/onStop/onDestroy và vài nút bấm.
 *
 * Overlay là View phủ trên preview, không phải use case CameraX nên không bao giờ lọt vào ảnh.
 */
class AiCameraAssistant(
    private val activity: ComponentActivity,
    private val previewView: PreviewView,
    private val overlay: CameraOverlayView,
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

    /**
     * Use case phân tích khung hình. Bind nó cùng Preview/ImageCapture trong
     * `bindToLifecycle`. Assistant đã tự cấu hình 640x480 RGBA + KEEP_ONLY_LATEST + analyzer.
     */
    val imageAnalysis: ImageAnalysis

    var currentSuggestion: PoseSuggestion? = null
        private set

    /**
     * Analyzer cũ của activity (ví dụ `SubjectTracker`) chạy trên **cùng** [imageAnalysis].
     *
     * CameraX chỉ cho bind một `ImageAnalysis` mỗi camera, nên khi ghép vào app đã có luồng
     * phân tích riêng (chấm vàng + tự chụp heuristic) thì chuyển analyzer đó vào đây thay vì
     * bind use case thứ hai. Callback chạy trên analysis thread, **trước** khi proxy bị đóng;
     * không được gọi `proxy.close()` bên trong.
     */
    var legacyFrameTap: ((ImageProxy) -> Unit)? = null

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
    private var lastGuidance: String? = null
    private var latestUi = PipelineUi(state = PipelineState.WAITING)

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    /** Đang dùng camera trước (selfie) hay chưa. */
    var frontCameraActive: Boolean = false
        private set

    /** Cổng AF/AE: pipeline chỉ tự chụp khi ảnh đã nét và phơi sáng ổn định. */
    private val focusGate = FocusGate()

    /** Đọc `CONTROL_AF_STATE` / `CONTROL_AE_STATE` của từng frame và đẩy vào [focusGate]. */
    private val sessionCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
            // INACTIVE = camera không chạy AF (ống kính fix-focus, thường là camera trước).
            // Coi như đã nét, nếu không thì cổng không bao giờ mở và máy đó không tự chụp được.
            val afConverged = afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                afState == CaptureResult.CONTROL_AF_STATE_INACTIVE
            val aeConverged = aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
            val now = SystemClock.elapsedRealtime()
            activity.runOnUiThread { focusGate.onCaptureResult(afConverged, aeConverged, now) }
        }
    }

    private val lockTracker = FrameLockTracker(density)
    private val deviceOrientation = DeviceOrientation(activity)
    private val stabilityMonitor = StabilityMonitor(activity)
    private val sceneAnalyzer = SceneAnalyzer(activity) { scene -> latestScene = scene }
    private val framingAnalyzer: FramingAnalyzer
    private val pipeline: CapturePipeline

    init {
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
            focusReady = focusGate::isReady,
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

        overlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncViewport() }
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

    /**
     * Builder `ImageCapture` đã gắn callback đọc AF/AE — host **phải** dựng use case từ builder này
     * (`imageCapture = assistant.imageCaptureBuilder().build()`) thay vì `ImageCapture.Builder()`.
     *
     * Builder trơn thì [focusGate] không bao giờ nhận kết quả và pipeline sẽ không tự chụp, vì
     * `focusReady` chỉ đúng khi AF/AE đã hội tụ.
     */
    fun imageCaptureBuilder(): ImageCapture.Builder {
        val builder = ImageCapture.Builder()
        Camera2Interop.Extender(builder).setSessionCaptureCallback(sessionCaptureCallback)
        return builder
    }

    /**
     * Chạm để lấy nét + đo sáng tại điểm chuẩn hoá (0..1) trên preview.
     * Reset [focusGate] để lần căn sau phải chờ AF/AE hội tụ lại từ đầu.
     */
    fun focusAt(normX: Float, normY: Float) {
        val control = camera?.cameraControl ?: return
        if (previewView.width <= 0 || previewView.height <= 0) return
        val point = previewView.meteringPointFactory.createPoint(
            normX.coerceIn(0f, 1f) * previewView.width,
            normY.coerceIn(0f, 1f) * previewView.height,
        )
        val action = FocusMeteringAction
            .Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(FOCUS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        focusGate.reset()
        control.startFocusAndMetering(action)
    }

    /**
     * Một chỗ duy nhất đổi tỉ lệ preview: overlay vẽ và pipeline đo dùng chung một aspect hiển thị
     * (rộng/cao của nội dung camera), nên không còn cảnh khung 3:4 lệch chấm vàng khi đổi 16:9.
     */
    fun setPreviewAspect(aspect: Float) {
        overlay.previewAspect = aspect
        syncViewport()
    }

    /** Gọi sau `bindToLifecycle` để pipeline đọc được zoom ratio. */
    fun onCameraBound(boundCamera: Camera?) {
        camera = boundCamera
    }

    /**
     * Báo assistant đang dùng camera trước. Overlay hình que sẽ lật ngang phần vẽ vì PreviewView
     * tự lật hình còn khung phân tích thì không. Gọi lại mỗi lần đổi camera.
     */
    fun setFrontCameraActive(active: Boolean) {
        frontCameraActive = active
        overlay.mirrorHorizontally = active
    }

    /** Gọi sau khi bind thành công: pipeline chuyển từ "mở camera" sang "chờ ổn định". */
    fun onCameraOpened() {
        cameraReady = true
        pipeline.onCameraOpened()
        syncAutoCapture()
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
        overlay.invalidate()
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
        syncAutoCapture()
    }

    /** Mở hộp thoại cài đặt (gợi ý dáng / tự chụp / độ trễ). */
    fun showSettingsDialog() {
        AiSettingsDialog.show(activity, settings) { applyUserSettings() }
    }

    // ------------------------------------------------------------ nội bộ

    private fun syncViewport() {
        pipeline.setViewport(overlay.width, overlay.height, overlay.previewAspect)
    }

    private fun syncAutoCapture() {
        if (!cameraReady) return
        if (settings.autoCaptureEnabled) pipeline.start() else pipeline.stop()
    }

    private fun applyUserSettings() {
        val poseEnabled = settings.poseSuggestionsEnabled
        overlay.pose = currentSuggestion?.pose
        overlay.showPose = poseEnabled && currentSuggestion != null
        overlay.invalidate()
        listener?.onPoseSuggestionsToggled(poseEnabled)

        syncAutoCapture()
        listener?.onAutoCaptureChanged(settings.autoCaptureEnabled)
        lastGuidance = null
        listener?.onGuidance(guidanceFor(latestUi))
    }

    /**
     * Một ImageAnalysis duy nhất phục vụ cả nhận diện cảnh (gợi ý dáng) và tìm khung.
     * KEEP_ONLY_LATEST + throttle giữ cho camera không bị nghẽn.
     */
    private fun analyzeFrame(proxy: ImageProxy) {
        try {
            if (released) return
            legacyFrameTap?.invoke(proxy)
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

    /**
     * Ảnh của pipeline đi cùng một đường lưu với ảnh chụp tay ([PhotoStore]): MediaStore album
     * `Pictures/AICameraComposer` trên Android 10+, thư mục ảnh của app trên Android 8-9.
     * Nhờ vậy ảnh tự chụp hiện ngay trong GalleryActivity, không nằm trong thư mục riêng của app.
     */
    private fun capturePhoto() {
        val capture = imageCapture
        if (!cameraReady || capture == null) {
            failCapture("camera not ready")
            return
        }
        val target = PhotoStore.prepare(activity)
        if (target == null) {
            failCapture("storage not ready")
            return
        }

        capture.takePicture(
            target.options,
            ContextCompat.getMainExecutor(activity),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val uri = PhotoStore.publish(activity, target, outputFileResults)
                    Log.d(TAG, "saved $uri")
                    pipeline.onCaptureSaved()
                    listener?.onCaptureSaved(uri)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "takePicture failed", exception)
                    PhotoStore.discard(target)
                    pipeline.onCaptureFailed(exception.message)
                    listener?.onCaptureFailed(exception.message)
                }
            },
        )
    }

    private fun failCapture(message: String) {
        pipeline.onCaptureFailed(message)
        listener?.onCaptureFailed(message)
    }

    private fun render(ui: PipelineUi) {
        latestUi = ui
        overlay.frameCenter = ui.frameCenter
        overlay.showCenterTarget =
            ui.state == PipelineState.READY || ui.state == PipelineState.CAPTURING
        overlay.aligned =
            ui.lockedOnTarget || ui.alignmentOffsetDp <= CapturePipeline.ALIGN_TOLERANCE_DP
        overlay.holdProgress = ui.holdProgress
        overlay.invalidate()

        // render() chạy mỗi tick (~33ms): chỉ đẩy chữ lên UI khi câu hướng dẫn đổi.
        val guidance = guidanceFor(ui)
        if (guidance != lastGuidance) {
            lastGuidance = guidance
            listener?.onGuidance(guidance)
        }

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

        private const val SCENE_INTERVAL_MS = 700L
        private const val FRAMING_INTERVAL_MS = 280L
        private const val ANALYSIS_WIDTH = 640
        private const val ANALYSIS_HEIGHT = 480

        /** AF/AE tự huỷ sau 3 s nếu camera không hội tụ (tránh khoá cứng ở cảnh khó). */
        private const val FOCUS_TIMEOUT_SECONDS = 3L
    }
}
