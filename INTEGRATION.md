# Ghép tính năng AI vào AICameraComposer hiện có

Mục tiêu: **không viết lại `MainActivity.kt`**. Toàn bộ tính năng mới nằm trong package
`com.gia.aicomposer.integration` (3 file) + các module thuần (pose/vision/pipeline/overlay/settings/util).
Activity cũ chỉ thêm ~6 đoạn nối dây, code camera/gallery của bạn giữ nguyên.

> **Trạng thái repo của bạn (đã kiểm tra):** fetch nhánh `main` của
> `github.com/binhvugia1997-ui/App-chulj-nh-vip-pro` hiện chỉ có `README.md`
> (1 commit `Initial commit`), chưa có `MainActivity.kt`/`SubjectTracker.kt` nào được push.
> Vì vậy mục 3 là diff theo **mốc** (anchor), không phải diff theo số dòng trên file thật.
> Muốn diff chính xác từng dòng: dán `MainActivity.kt` hiện có vào chat (hoặc push lên repo) —
> mình sẽ trả về patch đúng file của bạn.

---

## 0. Kiến trúc nối dây

```
MainActivity (của bạn)                     integration/AiCameraAssistant   (điểm nối duy nhất)
├── Preview + ImageCapture + bindToLifecycle ──► imageAnalysis (use case do assistant tạo)
├── previewContainer (FrameLayout)          ──► overlay (PoseOverlayView tự thêm vào)
└── 3 nút + switch + onStart/Stop/Destroy   ◄── Listener: guidance, pose, auto-capture, saved
```

- Assistant sở hữu: overlay, EfficientDet (cảnh), ML Kit Face + Pose Landmarker (khung 3:4),
  cảm biến ổn định, khóa khung theo máy, pipeline 9 bước, hộp thoại cài đặt.
- Assistant **không** bind CameraX: bạn bind, assistant chỉ nhận `ImageCapture` + `Camera` để đọc zoom.
- `AiSettingsDialog` dựng UI bằng code → **không cần viewBinding, không cần theme Material**.
- Overlay là `View` phủ trên preview → không vào ảnh chụp.

---

## 1. Danh sách file cần chép

Chép từ workspace này sang `D:\AICameraComposer` (giữ đúng đường dẫn).

### 1.1 Bắt buộc (code tính năng)

| File / thư mục nguồn | Đích trong app của bạn |
|---|---|
| `app/src/main/java/com/gia/aicomposer/integration/AiCameraAssistant.kt` | `app/src/main/java/com/gia/aicomposer/integration/` |
| `app/src/main/java/com/gia/aicomposer/integration/AiOverlayHost.kt` | nt |
| `app/src/main/java/com/gia/aicomposer/integration/AiSettingsDialog.kt` | nt |
| `app/src/main/java/com/gia/aicomposer/pose/` (PoseTemplate, PoseLibrary, PoseScorer) | `.../aicomposer/pose/` |
| `app/src/main/java/com/gia/aicomposer/model/SceneState.kt` | `.../aicomposer/model/` |
| `app/src/main/java/com/gia/aicomposer/vision/` (SceneAnalyzer, SceneBuilder, FreeRegionFinder, FramingAnalyzer, SaliencyFinder) | `.../aicomposer/vision/` |
| `app/src/main/java/com/gia/aicomposer/pipeline/` (StabilityMonitor, DeviceOrientation, FrameLockTracker, CapturePipeline) | `.../aicomposer/pipeline/` |
| `app/src/main/java/com/gia/aicomposer/overlay/PoseOverlayView.kt` | `.../aicomposer/overlay/` |
| `app/src/main/java/com/gia/aicomposer/settings/AppSettings.kt` | `.../aicomposer/settings/` |
| `app/src/main/java/com/gia/aicomposer/util/PreviewGeometry.kt` | `.../aicomposer/util/` |
| `app/src/main/java/com/gia/aicomposer/util/ImageConversions.kt` | nt |
| `app/src/main/assets/efficientdet_lite0.tflite` | `app/src/main/assets/` (13.8 MB) |
| `app/src/main/assets/pose_landmarker_lite.task` | `app/src/main/assets/` (5.6 MB) |
| `app/src/main/res/values/ai_strings.xml` | `app/src/main/res/values/` |
| `app/src/main/res/values/ai_colors.xml` | `app/src/main/res/values/` |

### 1.2 Chỉ để tham khảo — KHÔNG chép đè

- `app/src/main/java/com/gia/aicomposer/MainActivity.kt` — bản mẫu nối dây tối thiểu; app của bạn đã có
  `MainActivity` riêng (camera + gallery + `SubjectTracker`). Chỉ dán các đoạn ở mục 3.
- `app/src/main/res/layout/activity_main.xml` — layout demo; chỉ cần ý tưởng `FrameLayout` bọc `PreviewView`.
- `res/values/strings.xml`, `res/values/colors.xml`, `res/values/themes.xml`, `res/drawable/ic_app_icon.xml`,
  `app/build.gradle.kts`, `AndroidManifest.xml` — cấu hình demo.

### 1.3 Lệnh chép nhanh (PowerShell, chạy tại thư mục workspace này)

```powershell
$src = ".\app\src\main"
$dst = "D:\AICameraComposer\app\src\main"

New-Item -ItemType Directory -Force "$dst\java\com\gia\aicomposer\integration" | Out-Null
Copy-Item "$src\java\com\gia\aicomposer\integration\*.kt" "$dst\java\com\gia\aicomposer\integration\" -Force
foreach ($p in @("pose","model","vision","pipeline","overlay","settings","util")) {
    New-Item -ItemType Directory -Force "$dst\java\com\gia\aicomposer\$p" | Out-Null
    Copy-Item "$src\java\com\gia\aicomposer\$p\*.kt" "$dst\java\com\gia\aicomposer\$p\" -Force
}
New-Item -ItemType Directory -Force "$dst\assets" | Out-Null
Copy-Item "$src\assets\*.tflite","$src\assets\*.task" "$dst\assets\" -Force
Copy-Item "$src\res\values\ai_strings.xml","$src\res\values\ai_colors.xml" "$dst\res\values\" -Force
```

> Nếu `ai_strings.xml` / `ai_colors.xml` báo trùng resource với file bạn đang có, xoá các dòng trùng
> trong `ai_strings.xml` / `ai_colors.xml` (Android không cho 2 định nghĩa cùng tên).

---

## 2. Sửa `app/build.gradle.kts` (3 chỗ)

```diff
 android {
     ...
     compileSdk = 34
+
+    androidResources {
+        // MediaPipe đọc thẳng .tflite / .task từ assets -> phải để nguyên, không nén.
+        noCompress += listOf("tflite", "task")
+    }
 +
+    defaultConfig {
+        // MediaPipe/ML Kit ship .so cho 4 ABI (~77 MB thô). Giữ arm64-v8a (máy thật)
+        // và x86_64 (emulator); bỏ x86 + armeabi-v7a -> APK giảm ~45 MB.
+        ndk {
+            abiFilters += listOf("arm64-v8a", "x86_64")
+        }
+    }
 }

 dependencies {
     // ... các dependency hiện có của bạn (CameraX, Material, ...)
+
+    // Object detection (EfficientDet Lite0) + pose landmarker, model bundle trong assets.
+    implementation("com.google.mediapipe:tasks-vision:0.10.14")
+
+    // Face detection cho bước tìm khung (ưu tiên mặt).
+    implementation("com.google.mlkit:face-detection:16.1.6")
 }
```

Không cần bật `viewBinding` cho phần AI. Bắt buộc đã có `androidx.camera:camera-view` (cho `PreviewView`).

> **Lưu ý ABI:** `tasks-vision:0.10.14` **không** phát hành `.so` cho `x86_64` (chỉ arm64-v8a,
> armeabi-v7a, x86). Trên emulator `x86_64`, `SceneAnalyzer`/`FramingAnalyzer` tự bắt `Throwable` lúc
> tạo detector nên app vẫn chạy: gợi ý dáng dùng thư viện mẫu, tìm khung dùng ML Kit Face + saliency.
> Muốn MediaPipe chạy trên emulator: dùng image `x86` (32-bit) và thêm `"x86"` vào `abiFilters`
> (APK +~31 MB). Đo thực tế trong workspace: 110.974.312 → 66.028.099 byte (−44,9 MB).

### Manifest (tuỳ chọn nhưng nên thêm)

Cảm biến không bắt buộc để app vẫn cài được trên máy/emulator thiếu gyro:

```diff
     <uses-feature android:name="android.hardware.camera.any" android:required="true" />
+    <uses-feature android:name="android.hardware.sensor.accelerometer" android:required="false" />
+    <uses-feature android:name="android.hardware.sensor.gyroscope" android:required="false" />
```

---

## 3. Diff từng bước cho `MainActivity.kt` hiện có

> Vì mình không đọc được file của bạn, diff dùng **mốc (anchor)** thay vì số dòng. Mỗi bước độc lập,
> dán đúng chỗ ghi trong tiêu đề. Tên biến `binding`, `previewView`, `imageCapture` — đổi theo app bạn.

### 3.0 Toàn bộ đoạn nối dây trong một khối (copy-paste)

Đây là **toàn bộ** phần AI trong activity — nếu dán nguyên khối này vào một Activity rỗng thì app
chạy được. `MainActivity.kt` trong workspace là bản mẫu tương đương nhưng dùng `viewBinding`
(`binding.previewView`, `binding.previewContainer`) — cùng logic, khác cách lấy view.

```kotlin
class MainActivity : AppCompatActivity(), AiCameraAssistant.Listener {

    private lateinit var previewView: PreviewView        // PreviewView có sẵn của bạn
    private lateinit var assistant: AiCameraAssistant
    private lateinit var imageCapture: ImageCapture
    private var syncingAutoCapture = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)           // giữ nguyên của bạn
        previewView = findViewById(R.id.previewView)     // đổi id nếu khác

        // (1) tạo assistant: tự bọc PreviewView trong FrameLayout nếu layout chưa có
        assistant = AiCameraAssistant(this, previewView)
        assistant.listener = this

        // (2) nối nút/switch của bạn
        findViewById<View>(R.id.btnSuggestPose).setOnClickListener { assistant.suggestPose() }
        findViewById<View>(R.id.btnCapture).setOnClickListener { assistant.captureNow() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { assistant.showSettingsDialog() }
        findViewById<CompoundButton>(R.id.switchAutoCapture).apply {
            isChecked = assistant.settings.autoCaptureEnabled
            setOnCheckedChangeListener { _, checked ->
                if (syncingAutoCapture) return@setOnCheckedChangeListener
                assistant.setAutoCaptureEnabled(checked)
            }
        }

        startCamera()                                    // hàm camera cũ của bạn
    }

    // (3) camera: giữ nguyên code cũ, chỉ thêm assistant.imageAnalysis vào bind
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            assistant.setImageCapture(imageCapture)

            provider.unbindAll()
            val camera: Camera = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
                assistant.imageAnalysis,             // use case phân tích của assistant
            )
            assistant.onCameraBound(camera)          // để đọc zoom ratio
            assistant.onCameraOpened()               // pipeline chạy tiếp
        }, ContextCompat.getMainExecutor(this))
    }

    // (4) vòng đời
    override fun onStart() { super.onStart(); assistant.start() }
    override fun onStop() { assistant.stop(); super.onStop() }
    override fun onDestroy() { assistant.release(); super.onDestroy() }

    // (5) nhận trạng thái — override cái bạn cần
    override fun onGuidance(text: String) { tvGuidance.text = text }

    override fun onPoseSuggested(suggestion: PoseSuggestion, scene: SceneState) {
        tvPoseName.text = getString(R.string.suggest_result, suggestion.pose.displayName, suggestion.score)
    }

    override fun onAutoCaptureChanged(enabled: Boolean) {
        syncingAutoCapture = true
        switchAutoCapture.isChecked = enabled
        syncingAutoCapture = false
    }

    override fun onCaptureSaved(uri: Uri) { /* uri trỏ tới file JPG vừa lưu */ }
    override fun onCaptureFailed(message: String?) { /* toast nếu muốn */ }
}
```

Các bước 3.1–3.7 dưới đây là bản tách nhỏ của đúng khối này, để bạn dán vào file đang có thay vì
thay cả class.

### Bước 3.1 — khai báo + import

```diff
 package com.gia.aicomposer
+
+import android.net.Uri
+import androidx.camera.core.Camera
+import com.gia.aicomposer.integration.AiCameraAssistant
+import com.gia.aicomposer.model.SceneState
+import com.gia.aicomposer.pose.PoseSuggestion

-class MainActivity : AppCompatActivity() {
+class MainActivity : AppCompatActivity(), AiCameraAssistant.Listener {
+
+    private lateinit var aiAssistant: AiCameraAssistant
+    private var syncingSwitch = false
```

### Bước 3.2 — trong `onCreate`, **sau** `setContentView(...)` và sau khi `PreviewView` đã có trong layout

```diff
     setContentView(binding.root)
+
+    // Overlay AI được thêm vào chính khung chứa PreviewView.
+    aiAssistant = AiCameraAssistant(this, binding.previewView, binding.previewContainer)
+    aiAssistant.listener = this
```

Nếu layout chưa có `FrameLayout` bọc `PreviewView`, bỏ tham số thứ 3 — assistant tự bọc:

```kotlin
aiAssistant = AiCameraAssistant(this, binding.previewView)
```

### Bước 3.3 — layout: bọc `PreviewView` trong `FrameLayout` (khuyến nghị)

```diff
-    <androidx.camera.view.PreviewView
-        android:id="@+id/previewView"
-        android:layout_width="0dp"
-        android:layout_height="0dp"
-        app:layout_constraintTop_toTopOf="parent"
-        app:layout_constraintBottom_toBottomOf="parent"
-        app:layout_constraintStart_toStartOf="parent"
-        app:layout_constraintEnd_toEndOf="parent" />
+    <FrameLayout
+        android:id="@+id/previewContainer"
+        android:layout_width="0dp"
+        android:layout_height="0dp"
+        app:layout_constraintTop_toTopOf="parent"
+        app:layout_constraintBottom_toBottomOf="parent"
+        app:layout_constraintStart_toStartOf="parent"
+        app:layout_constraintEnd_toEndOf="parent">
+
+        <androidx.camera.view.PreviewView
+            android:id="@+id/previewView"
+            android:layout_width="match_parent"
+            android:layout_height="match_parent" />
+    </FrameLayout>
```

Lưu ý: nếu view khác đang `layout_constraintTop_toBottomOf="@id/previewView"` thì đổi sang
`@id/previewContainer` (vì PreviewView giờ nằm trong FrameLayout).

### Bước 3.4 — `startCamera()`: thêm use case phân tích + báo assistant

```diff
     val imageCapture = ImageCapture.Builder()
         .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
         .build()
+    aiAssistant.setImageCapture(imageCapture)
+
+    // Dùng đúng ImageAnalysis của assistant (đã cấu hình 640x480 RGBA + KEEP_ONLY_LATEST).
+    val imageAnalysis = aiAssistant.imageAnalysis

-    cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
+    val camera = cameraProvider.bindToLifecycle(
+        this, cameraSelector, preview, imageCapture, imageAnalysis,
+    )
+
+    aiAssistant.onCameraBound(camera)
+    aiAssistant.onCameraOpened()
```

**Quan trọng:** CameraX chỉ cho **một** `ImageAnalysis` mỗi camera. Nếu app bạn đã có `ImageAnalysis`
riêng, hãy bỏ nó khỏi `bindToLifecycle` và dùng `aiAssistant.imageAnalysis` (hoặc gọi
`aiAssistant.imageAnalysis.setAnalyzer(...)` không được — nó đã có analyzer riêng). Không bind cả hai.

### Bước 3.5 — vòng đời

```diff
 override fun onStart() {
     super.onStart()
+    aiAssistant.start()
 }

 override fun onStop() {
+    aiAssistant.stop()
     super.onStop()
 }

 override fun onDestroy() {
+    aiAssistant.release()
     super.onDestroy()
 }
```

`aiAssistant.stop()` tắt cảm biến và **tạm dừng** pipeline (kể cả SubjectTracker cũ vẫn chạy độc lập
được). Việc mở hộp thoại xin quyền hay khóa màn hình cũng rơi vào `onStop` → `onStart`, nên
`start()` sẽ đưa pipeline chạy tiếp từ bước "chờ ổn định" thay vì để nó kẹt ở `WAITING`.
Nếu muốn tắt hẳn tự căn khung thì dùng `setAutoCaptureEnabled(false)`, đừng trông vào `onStop`.

### Bước 3.6 — nút bấm

```diff
 btnSuggestPose.setOnClickListener {
-    // code cũ của bạn
+    aiAssistant.suggestPose()          // vẽ hình que + trả tên/điểm qua listener
 }

 shutterButton.setOnClickListener {
-    // code cũ của bạn
+    aiAssistant.captureNow()           // chụp tay vẫn đi qua pipeline để chung state
 }

 settingsButton.setOnClickListener {
-    // code cũ của bạn
+    aiAssistant.showSettingsDialog()   // gợi ý dáng / tự chụp / độ trễ 0–3s
 }

 switchAutoCapture.setOnCheckedChangeListener { _, checked ->
-    // code cũ của bạn
+    if (syncingSwitch) return@setOnCheckedChangeListener
+    aiAssistant.setAutoCaptureEnabled(checked)
 }
```

Nếu bạn muốn giữ nguyên nút chụp cũ (tự gọi `imageCapture.takePicture`), vẫn được — pipeline không
bắt buộc. Nhưng khi đó overlay/pipeline không biết đã chụp; cách gọn nhất là thay bằng
`aiAssistant.captureNow()` (pipeline tự tắt sau khi chụp tay, ảnh vẫn do `ImageCapture` lấy).

### Bước 3.7 — nhận trạng thái (dán vào cuối class)

```kotlin
override fun onGuidance(text: String) {
    tvGuidance.text = text                     // câu hướng dẫn của 9 bước
}

override fun onPoseSuggested(suggestion: PoseSuggestion, scene: SceneState) {
    tvPoseName.text = getString(
        R.string.suggest_result, suggestion.pose.displayName, suggestion.score,
    )
}

override fun onPoseSuggestionsToggled(enabled: Boolean) {
    val s = aiAssistant.currentSuggestion
    tvPoseName.text = when {
        !enabled -> getString(R.string.guidance_pose_disabled)
        s == null -> getString(R.string.pose_hint_idle)
        else -> getString(R.string.suggest_result, s.pose.displayName, s.score)
    }
}

override fun onAutoCaptureChanged(enabled: Boolean) {
    syncingSwitch = true
    switchAutoCapture.isChecked = enabled
    syncingSwitch = false
}

override fun onCaptureSaved(uri: Uri) {
    // ảnh nằm ở getExternalFilesDir(Pictures)/AICameraComposer/AIC_*.jpg
    // muốn đưa vào GalleryActivity của bạn thì dùng uri này.
}

override fun onCaptureFailed(message: String?) { /* hiện toast nếu muốn */ }
```

Các callback khác của `Listener` có default rỗng → chỉ override cái bạn cần.

---

## 4. Bảng API `AiCameraAssistant`

| Thành viên | Việc dùng |
|---|---|
| `imageAnalysis: ImageAnalysis` | bind cùng Preview/ImageCapture |
| `overlay: PoseOverlayView` | hiếm khi cần; assistant đã tự thêm vào container |
| `setImageCapture(capture)` | sau khi tạo `ImageCapture` |
| `onCameraBound(camera)` | để đọc zoom ratio cho độ trôi khung |
| `onCameraOpened()` | sau `bindToLifecycle` thành công |
| `start()` / `stop()` / `release()` | onStart / onStop / onDestroy (`stop` chỉ tạm dừng, `start` chạy tiếp) |
| `suggestPose(): PoseSuggestion?` | nút "Gợi ý dáng" |
| `captureNow()` | nút chụp tay |
| `setAutoCaptureEnabled(Boolean)` | switch tự chụp |
| `showSettingsDialog()` | nút Cài đặt |
| `settings: AppSettings` | đọc/ghi trực tiếp nếu cần |
| `currentSuggestion` / `currentPose` | dáng đang hiện |
| `listener: Listener?` | nhận guidance / pose / auto-capture / saved |

---

## 5. Thứ tự build & kiểm tra trên máy bạn

> Checklist chi tiết từng mục (emulator vs máy thật, kèm tiêu chí đạt/không đạt): xem `TESTING.md`.

1. Chép file (mục 1) → sửa gradle (mục 2) → dán 7 bước (mục 3).
2. `gradlew.bat :app:assembleDebug` — phải `BUILD SUCCESSFUL`, APK ở
   `app\build\outputs\apk\debug\app-debug.apk`.
3. Emulator (không cảm biến): bấm "Gợi ý dáng" → thấy hình que + khung 3:4; bật "Tự chụp" → sau
   ~600 ms pipeline coi như yên (fallback), tìm khung, đếm 1 s rồi chụp. Ảnh phải **không** có overlay.
4. Máy thật: rung máy → pipeline về "Giữ máy yên"; xoay máy → khung trôi theo; giữ tâm trong 15 dp
   khoảng 1 s → đếm ngược → chụp; lệch ra giữa chừng → huỷ đếm.
5. GalleryActivity: ảnh mới nằm ở `Android/data/com.gia.aicomposer/files/Pictures/AICameraComposer/`.

## 6. Ghi chú xung đột có thể gặp

- **Hai `ImageAnalysis`**: bỏ cái cũ khỏi `bindToLifecycle` (xem 3.4).
- **Tên string/color trùng**: xoá dòng trùng trong `ai_strings.xml` / `ai_colors.xml`.
- **Overlay cũ**: `CompositionOverlayView` của bạn vẫn hoạt động; nếu muốn ẩn khi dùng overlay AI thì
  set `visibility = GONE` khi bật gợi ý dáng.
- **`SubjectTracker` cũ**: không xung đột (đọc cảm biến độc lập). Nếu muốn một nguồn duy nhất, thay
  điều kiện ổn định của nó bằng `AiCameraAssistant` — nhưng không bắt buộc.
- **Theme**: dialog cài đặt dùng `AlertDialog` + `SwitchCompat` nên chạy với mọi theme.

## 7. Chưa kiểm chứng được ở đây

Máy build là Linux cloud, không có emulator/KVM và không truy cập được `D:\AICameraComposer`:
chưa chạy runtime, chưa xem overlay bằng mắt, chưa test cảm biến/khóa khung/tự chụp. Build APK của
workspace thì đã xanh (`BUILD SUCCESSFUL`) và có đủ class + 2 model trong assets.

## 8. Đã kiểm chứng trong workspace (trước khi giao)

- `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL` (exit 0); APK
  `app/build/outputs/apk/debug/app-debug.apk` ≈ 63 MB (66.028.099 byte), chỉ còn ABI
  `arm64-v8a` + `x86_64` (trước khi lọc ABI: ≈ 106 MB / 110.974.312 byte với 4 ABI).
- APK chứa `assets/efficientdet_lite0.tflite` + `assets/pose_landmarker_lite.task` (stored, không nén)
  và các class `integration/AiCameraAssistant`, `integration/AiOverlayHost`, `integration/AiSettingsDialog`.- Kiểm tra tĩnh: package `integration/`, `pose/`, `vision/`, `pipeline/`, `overlay/`, `settings/`, `util/`
  **không** tham chiếu `MainActivity`, `ActivityMainBinding` hay `binding.` nào → chép sang app của bạn
  không kéo theo code demo.
- Toàn bộ `R.string.*` / `R.color.*` mà code dùng đều có trong `ai_strings.xml` / `ai_colors.xml`.
