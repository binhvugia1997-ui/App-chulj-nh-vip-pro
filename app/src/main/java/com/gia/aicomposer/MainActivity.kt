package com.gia.aicomposer

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.gia.aicomposer.databinding.ActivityMainBinding
import com.gia.aicomposer.integration.AiCameraAssistant
import com.gia.aicomposer.model.SceneState
import com.gia.aicomposer.pose.PoseSuggestion

/**
 * Bản mẫu nối dây tối thiểu.
 *
 * Toàn bộ tính năng AI nằm trong [AiCameraAssistant]; activity này chỉ còn phần camera
 * (Preview + ImageCapture + bindToLifecycle) và vài nút. Đây chính là những đoạn cần dán
 * vào MainActivity.kt hiện có — xem INTEGRATION.md để biết diff từng bước.
 */
class MainActivity : AppCompatActivity(), AiCameraAssistant.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var assistant: AiCameraAssistant

    private var syncingSwitch = false

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                binding.tvGuidance.text = getString(R.string.guidance_permission_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Tạo assistant ngay sau khi có PreviewView (overlay được thêm vào previewContainer).
        assistant = AiCameraAssistant(this, binding.previewView, binding.previewContainer)
        assistant.listener = this

        // 2. Nối nút bấm vào API của assistant.
        binding.btnSuggestPose.setOnClickListener { assistant.suggestPose() }
        binding.btnCapture.setOnClickListener { assistant.captureNow() }
        binding.btnSettings.setOnClickListener { assistant.showSettingsDialog() }
        binding.switchAutoCapture.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitch) return@setOnCheckedChangeListener
            assistant.setAutoCaptureEnabled(checked)
        }

        binding.tvPoseName.text = if (assistant.settings.poseSuggestionsEnabled) {
            getString(R.string.pose_hint_idle)
        } else {
            getString(R.string.guidance_pose_disabled)
        }
        syncSwitch(assistant.settings.autoCaptureEnabled)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // 3. Chuyển tiếp vòng đời cho assistant.
    override fun onStart() {
        super.onStart()
        assistant.start()
    }

    override fun onStop() {
        super.onStop()
        assistant.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        assistant.release()
    }

    // 4. Phần camera giữ nguyên như app cũ, chỉ thêm assistant.imageAnalysis vào bind.
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()

                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()
                preview.setSurfaceProvider(binding.previewView.surfaceProvider)

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()
                assistant.setImageCapture(capture)

                val useCases = arrayOf(preview, capture, assistant.imageAnalysis)
                provider.unbindAll()
                val camera: Camera = try {
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, *useCases)
                } catch (t: Throwable) {
                    Log.w(TAG, "back camera unavailable, trying front", t)
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, *useCases)
                }

                // 5. Báo cho assistant biết camera đã sẵn sàng để pipeline chạy tiếp.
                assistant.onCameraBound(camera)
                assistant.onCameraOpened()
            } catch (t: Throwable) {
                Log.e(TAG, "startCamera failed", t)
                binding.tvGuidance.text = getString(R.string.pipeline_error, t.message ?: "camera")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // 6. Nhận trạng thái từ assistant để cập nhật UI.
    override fun onGuidance(text: String) {
        binding.tvGuidance.text = text
    }

    override fun onPoseSuggested(suggestion: PoseSuggestion, scene: SceneState) {
        binding.tvPoseName.text = getString(
            R.string.suggest_result,
            suggestion.pose.displayName,
            suggestion.score,
        )
    }

    override fun onPoseSuggestionsToggled(enabled: Boolean) {
        val suggestion = assistant.currentSuggestion
        binding.tvPoseName.text = when {
            !enabled -> getString(R.string.guidance_pose_disabled)
            suggestion == null -> getString(R.string.pose_hint_idle)
            else -> getString(R.string.suggest_result, suggestion.pose.displayName, suggestion.score)
        }
    }

    override fun onAutoCaptureChanged(enabled: Boolean) {
        syncSwitch(enabled)
    }

    override fun onCaptureSaved(uri: Uri) {
        Log.d(TAG, "saved $uri")
    }

    override fun onCaptureFailed(message: String?) {
        Log.w(TAG, "capture failed: $message")
    }

    private fun syncSwitch(checked: Boolean) {
        syncingSwitch = true
        binding.switchAutoCapture.isChecked = checked
        syncingSwitch = false
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
