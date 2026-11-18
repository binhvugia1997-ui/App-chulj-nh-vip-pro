package com.gia.aicomposer

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * v0.1 — AI Composition Camera (clean-room, viet lai tu dau).
 *
 * Man hinh chinh: preview CameraX + overlay can khung (luoi 1/3, cham trang
 * muc tieu, cham vang chu the). Khi cham vang trung cham trang va giu on
 * ~0.6s thi tu dong chup. Co nut chup tay, doi ti le khung hinh, xem lai anh.
 */
class MainActivity : AppCompatActivity() {

    private enum class AspectMode(val label: String) {
        RATIO_4_3("4:3"),
        RATIO_16_9("16:9"),
        SQUARE("1:1");

        fun next(): AspectMode = when (this) {
            RATIO_4_3 -> RATIO_16_9
            RATIO_16_9 -> SQUARE
            SQUARE -> RATIO_4_3
        }
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlay: CompositionOverlayView
    private lateinit var statusText: TextView
    private lateinit var shutterButton: Button
    private lateinit var galleryButton: Button
    private lateinit var autoButton: Button
    private lateinit var aspectButton: Button

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var analysisExecutor: ExecutorService
    private val tracker = SubjectTracker()

    private var aspectMode = AspectMode.RATIO_4_3
    private var autoCaptureEnabled = true
    private var lastAutoCaptureMs = 0L
    private var withinSinceMs = 0L
    private var capturing = false
    private var lastStatus = ""

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) {
            startCamera()
        } else {
            Toast.makeText(this, "Can quyen camera de chup anh", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val rootView = (findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        previewView = findViewById(R.id.preview_view)
        overlay = findViewById(R.id.overlay_view)
        statusText = findViewById(R.id.status_text)
        shutterButton = findViewById(R.id.shutter_button)
        galleryButton = findViewById(R.id.gallery_button)
        autoButton = findViewById(R.id.auto_button)
        aspectButton = findViewById(R.id.aspect_button)
        analysisExecutor = Executors.newSingleThreadExecutor()

        overlay.onTargetMoved = { withinSinceMs = 0L }

        shutterButton.setOnClickListener { takePhoto() }
        galleryButton.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        autoButton.setOnClickListener {
            autoCaptureEnabled = !autoCaptureEnabled
            withinSinceMs = 0L
            updateAutoButton()
            setStatus(if (autoCaptureEnabled) "Tu dong chup: BAT" else "Che do tay - cham vang la chu the")
        }
        aspectButton.setOnClickListener {
            aspectMode = aspectMode.next()
            updateAspectButton()
            startCamera()
        }
        updateAutoButton()
        updateAspectButton()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            val perms = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.READ_MEDIA_IMAGES
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }

    private fun updateAutoButton() {
        autoButton.text = if (autoCaptureEnabled) "Tu dong: BAT" else "Tu dong: TAT"
    }

    private fun updateAspectButton() {
        aspectButton.text = "Ti le ${aspectMode.label}"
    }

    private fun setStatus(text: String) {
        if (text != lastStatus) {
            lastStatus = text
            statusText.text = text
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val aspect = when (aspectMode) {
            AspectMode.RATIO_4_3 -> AspectRatio.RATIO_4_3
            AspectMode.RATIO_16_9 -> AspectRatio.RATIO_16_9
            // 1:1 khong co trong CameraX: preview/chup 4:3 roi crop vuong sau khi chup.
            AspectMode.SQUARE -> AspectRatio.RATIO_4_3
        }
        val preview = Preview.Builder().setTargetAspectRatio(aspect).build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        imageCapture = ImageCapture.Builder()
            .setTargetAspectRatio(aspect)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisExecutor, ::analyzeFrame)

        overlay.previewAspectRatio = if (aspect == AspectRatio.RATIO_16_9) 16f / 9f else 4f / 3f

        provider.unbindAll()
        try {
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
                analysis
            )
            setStatus("Di chuyen may de cham vang trung cham trang")
        } catch (e: Exception) {
            Log.e(TAG, "bind failed", e)
            Toast.makeText(this, "Khong mo duoc camera: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun analyzeFrame(image: ImageProxy) {
        try {
            val point = tracker.analyze(image)
            if (point != null) {
                runOnUiThread { onSubjectPoint(point.first, point.second) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "analyze error", e)
        } finally {
            image.close()
        }
    }

    private fun onSubjectPoint(x: Float, y: Float) {
        overlay.subjectPoint = Pair(x, y)
        overlay.invalidate()
        if (!autoCaptureEnabled || capturing) return
        val now = SystemClock.uptimeMillis()
        if (now - lastAutoCaptureMs < AUTO_COOLDOWN_MS) return
        val dist = overlay.distanceToTarget()
        if (dist < LOCK_THRESHOLD) {
            if (withinSinceMs == 0L) withinSinceMs = now
            val held = now - withinSinceMs
            if (held >= LOCK_HOLD_MS) {
                withinSinceMs = 0L
                lastAutoCaptureMs = now
                overlay.locked = true
                overlay.invalidate()
                setStatus("Da khoa khung - dang chup...")
                takePhoto()
            } else {
                setStatus("Dang giu khung... ${String.format(Locale.US, "%.1f", held / 1000.0)}s")
            }
        } else {
            withinSinceMs = 0L
            if (overlay.locked) {
                overlay.locked = false
                overlay.invalidate()
            }
            setStatus("Di chuyen may de cham vang trung cham trang")
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (capturing) return
        capturing = true
        val name = "AIC_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        if (aspectMode == AspectMode.SQUARE) {
            val tmp = File(cacheDir, "tmp_$name")
            val opts = ImageCapture.OutputFileOptions.Builder(tmp).build()
            capture.takePicture(
                opts,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        analysisExecutor.execute {
                            try {
                                saveSquareCropped(tmp, name)
                            } finally {
                                tmp.delete()
                                runOnUiThread { capturing = false }
                            }
                        }
                    }

                    override fun onError(e: ImageCaptureException) {
                        capturing = false
                        Toast.makeText(
                            this@MainActivity,
                            "Chup loi: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        } else {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/AICameraComposer")
            }
            val opts = ImageCapture.OutputFileOptions.Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ).build()
            capture.takePicture(
                opts,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        capturing = false
                        overlay.locked = false
                        overlay.invalidate()
                        setStatus("Da luu anh: $name")
                        Toast.makeText(this@MainActivity, "Da luu anh", Toast.LENGTH_SHORT).show()
                    }

                    override fun onError(e: ImageCaptureException) {
                        capturing = false
                        Toast.makeText(
                            this@MainActivity,
                            "Chup loi: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        }
    }

    /** Crop tam giua thanh vuong 1:1 roi luu vao MediaStore (chi dung cho che do 1:1). */
    private fun saveSquareCropped(tmp: File, name: String) {
        try {
            val exif = ExifInterface(tmp.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val rotate = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            var bmp = BitmapFactory.decodeFile(tmp.absolutePath)
                ?: throw IllegalStateException("khong doc duoc anh tam")
            if (rotate != 0f) {
                val m = Matrix().apply { postRotate(rotate) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rotated != bmp) bmp.recycle()
                bmp = rotated
            }
            val side = minOf(bmp.width, bmp.height)
            val x = (bmp.width - side) / 2
            val y = (bmp.height - side) / 2
            val square = Bitmap.createBitmap(bmp, x, y, side, side)
            if (square != bmp) bmp.recycle()
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/AICameraComposer")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { out ->
                    square.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
            }
            square.recycle()
            runOnUiThread {
                overlay.locked = false
                overlay.invalidate()
                setStatus("Da luu anh vuong: $name")
                Toast.makeText(this, "Da luu anh", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "square save failed", e)
            runOnUiThread {
                Toast.makeText(this, "Luu anh loi: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val TAG = "AICamera"
        private const val LOCK_THRESHOLD = 0.08f // khoang cach chuan hoa cham vang - cham trang
        private const val LOCK_HOLD_MS = 600L // giu trung khung bao lau thi chup
        private const val AUTO_COOLDOWN_MS = 2500L // nghi giua 2 lan tu chup
    }
}
