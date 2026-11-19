# Ghép module AI + thư viện ảnh vào project thật

Tài liệu này là hướng dẫn merge **từng file/thư mục** trong workspace này vào project thật ở
`https://github.com/binhvugia1997-ui/App-chulj-nh-vip-pro` (nhánh `main`), kèm các bước thêm
dependency/assets và **chỉ rõ chỗ phải sửa** trong `MainActivity.kt`, `SubjectTracker.kt`,
`overlay/CameraOverlayView.kt` (gộp hai overlay cũ) để hành vi cũ (chấm vàng/chấm trắng, tự chụp
heuristic, thư viện ảnh) không đổi.

---

## 0. Trạng thái repo thật (đã kiểm tra lại)

`main` hiện ở commit `c9c7cc8` *"Merge remote v0.1 project with AI camera pipeline module"* và **đã có**
app v0.1 + module AI:

```
app/src/main/java/com/gia/aicomposer/
├── MainActivity.kt              ← app v0.1: camera, chấm vàng/trắng, tự chụp heuristic, nút Thư viện
├── SubjectTracker.kt            ← heuristic độ sáng (Y plane), EMA
├── CompositionOverlayView.kt    ← lưới 1/3 + chấm trắng + chấm vàng, chạm đổi điểm mục tiêu
├── GalleryActivity.kt           ← thư viện ảnh (MediaStore)
├── integration/                 ← 3 file nối dây AI
├── pose/ model/ vision/ pipeline/ overlay/ settings/ util/
app/src/main/assets/             ← efficientdet_lite0.tflite (13,8 MB) + pose_landmarker_lite.task (5,5 MB)
```

Nghĩa là "project thật" cần merge **chính là bản v0.1 này**. Mọi đoạn diff dưới đây được viết theo
đúng nội dung file trong repo (không phải diff theo "mốc" chung chung), nên đối chiếu được từng dòng.
Nếu máy Windows của bạn (`D:\AICameraComposer`) đang khác repo, hãy so file ở mục 1 trước.

**Đợt này workspace thêm/sửa so với `main`:**

| File | Thay đổi |
|---|---|
| `util/PhotoStore.kt` | **mới** — một chỗ duy nhất quyết định ảnh lưu ở đâu, dùng chung cho chụp tay và pipeline AI |
| `integration/AiCameraAssistant.kt` | chụp qua `PhotoStore` (MediaStore) thay vì thư mục riêng của app; thêm `legacyFrameTap` để chạy `SubjectTracker` trên cùng `ImageAnalysis` |
| `SubjectTracker.kt` | đọc được cả Y plane (YUV) và RGBA_8888, giữ nguyên thuật toán/EMA/kết quả với YUV |
| `GalleryActivity.kt` | xin quyền đọc (API 26-28), đọc MediaStore + thư mục app, nạp lại ở `onResume`, thumbnail/decode có lấy mẫu, trạng thái rỗng |
| `res/layout/activity_gallery.xml` | thêm `TextView @+id/empty_state` |
| `res/values/strings.xml` | thêm `gallery_empty` |
| `app/build.gradle.kts` | `splits` ABI = arm64-v8a + x86_64 (đã có trong repo) — số đo mới ở mục 8 |

---

## 0.5 Cách nhanh: áp patch thay vì chép tay

`docs/merge/ai-module.patch` là diff của toàn bộ module AI so với commit `c9c7cc8`: 26 file,
106.402 byte, **không chứa `MainActivity.kt`** (file đó vẫn sửa theo mục 3). Áp trên cây sạch ở
đúng commit nền:

```bash
git switch -c ai-module c9c7cc8
git apply --check docs/merge/ai-module.patch   # phải không in gì (exit 0)
git apply docs/merge/ai-module.patch
```

Sau khi áp: `git status --short` thấy 20 file sửa + `util/PhotoStore.kt` + `app/src/test/`,
rồi `./gradlew :app:testDebugUnitTest :app:assembleDebug` phải xanh. Patch đã gồm
`app/build.gradle.kts` (`splits` ABI, `noCompress`, `tasks-vision:1.2.3`, ML Kit, JUnit/Robolectric)
và 5 file test, nên mục 2.1 chỉ còn để tham chiếu từng dòng.

## 1. Bảng file/thư mục: chép mới, ghi đè, hay giữ nguyên

Chạy tại thư mục workspace này. Đích giả định: `D:\AICameraComposer` (đổi theo máy bạn).

### 1.1 Chép mới (project thật chưa có)

| Nguồn | Đích | Ghi chú |
|---|---|---|
| `app/src/main/java/com/gia/aicomposer/integration/AiCameraAssistant.kt` | `.../aicomposer/integration/` | điểm nối duy nhất |
| `app/src/main/java/com/gia/aicomposer/integration/AiSettingsDialog.kt` | nt | dialog dựng bằng code, không cần viewBinding |
| `app/src/main/java/com/gia/aicomposer/pose/` (3 file) | `.../aicomposer/pose/` | `PoseTemplate`, `PoseLibrary` (30 dáng), `PoseScorer` |
| `app/src/main/java/com/gia/aicomposer/model/SceneState.kt` | `.../aicomposer/model/` | |
| `app/src/main/java/com/gia/aicomposer/vision/` (5 file) | `.../aicomposer/vision/` | `SceneAnalyzer`, `SceneBuilder`, `FramingAnalyzer`, `FreeRegionFinder`, `SaliencyFinder` |
| `app/src/main/java/com/gia/aicomposer/pipeline/` (4 file) | `.../aicomposer/pipeline/` | `StabilityMonitor`, `DeviceOrientation`, `FrameLockTracker`, `CapturePipeline` |
| `app/src/main/java/com/gia/aicomposer/overlay/CameraOverlayView.kt` | `.../aicomposer/overlay/` | overlay duy nhất: lưới + chấm vàng/trắng + khung 3:4 + hình que (thay `CompositionOverlayView` cũ) |
| `app/src/main/java/com/gia/aicomposer/settings/AppSettings.kt` | `.../aicomposer/settings/` | SharedPreferences |
| `app/src/main/java/com/gia/aicomposer/util/PreviewGeometry.kt` | `.../aicomposer/util/` | |
| `app/src/main/java/com/gia/aicomposer/util/ImageConversions.kt` | nt | RGBA → Bitmap |
| `app/src/main/java/com/gia/aicomposer/util/PhotoStore.kt` | nt | **mới**: MediaStore + fallback, dùng cho gallery |
| `app/src/main/assets/efficientdet_lite0.tflite` | `app/src/main/assets/` | 13.836.895 byte, **không nén** |
| `app/src/main/assets/pose_landmarker_lite.task` | nt | 5.777.746 byte, **không nén** |
| `app/src/main/res/values/ai_strings.xml` | `app/src/main/res/values/` | chuỗi của overlay/pipeline/cài đặt |
| `app/src/main/res/values/ai_colors.xml` | nt | `guide_accent`, `guide_accent_aligned` |

### 1.2 Ghi đè / merge có kiểm soát (project thật đã có)

| File | Cách làm |
|---|---|
| `GalleryActivity.kt` | **ghi đè** bằng bản trong workspace: bản cũ chỉ đọc MediaStore và không xin quyền, nên ảnh của pipeline AI (và ảnh trên API 26-28) không hiện |
| `integration/AiCameraAssistant.kt` | nếu bạn đã chép bản trước đó, ghi đè (có `PhotoStore` + `legacyFrameTap`) |
| `SubjectTracker.kt` | **sửa 1 chỗ** (mục 4) — bắt buộc nếu dùng chung `ImageAnalysis` RGBA của assistant |
| `MainActivity.kt` | **7 mốc** (mục 3), không viết lại cả file |
| `res/layout/activity_gallery.xml` | thêm `TextView @+id/empty_state` (mục 6.3), giữ `GridView`/`ImageView`/`Button` cũ |
| `res/values/strings.xml` | thêm 1 dòng `gallery_empty` (mục 6.3) — **không** ghi đè cả file |
| `app/build.gradle.kts` | thêm `noCompress`, `splits` ABI, 2 dependency (mục 2) |
| `AndroidManifest.xml` | đã có `GalleryActivity` + 3 quyền; chỉ kiểm tra lại (mục 6.2) |

### 1.3 Giữ nguyên, **không** chép từ workspace

- `res/layout/activity_main.xml` — chỉ đổi class `CompositionOverlayView` → `overlay.CameraOverlayView`, giữ nguyên id `overlay_view` (mục 5). File `CompositionOverlayView.kt` xoá.
- `overlay/CameraOverlayView.kt` (mới) **thay** `CompositionOverlayView.kt` + `overlay/PoseOverlayView.kt` + `integration/AiOverlayHost.kt`.
- `MainActivity.kt` trong workspace — chỉ là app v0.1 tham chiếu; nguồn sửa là file của bạn.
- `res/values/themes.xml`, `colors.xml`, `drawable/*` — cấu hình demo.
- `app/build.gradle.kts`, `AndroidManifest.xml` — không chép đè, chỉ áp diff ở mục 2/6.2.
- `GalleryActivity.kt` bản cũ trong repo — thay bằng bản mới, đừng giữ cả hai.

### 1.4 Lệnh chép (PowerShell, chạy trong workspace)

```powershell
$src = ".\app\src\main"
$dst = "D:\AICameraComposer\app\src\main"

# code module AI
New-Item -ItemType Directory -Force "$dst\java\com\gia\aicomposer\integration" | Out-Null
Copy-Item "$src\java\com\gia\aicomposer\integration\*.kt" "$dst\java\com\gia\aicomposer\integration\" -Force
foreach ($p in @("pose","model","vision","pipeline","overlay","settings","util")) {
    New-Item -ItemType Directory -Force "$dst\java\com\gia\aicomposer\$p" | Out-Null
    Copy-Item "$src\java\com\gia\aicomposer\$p\*.kt" "$dst\java\com\gia\aicomposer\$p\" -Force
}

# thư viện ảnh (bản mới)
Copy-Item "$src\java\com\gia\aicomposer\GalleryActivity.kt" "$dst\java\com\gia\aicomposer\" -Force
Copy-Item "$src\res\layout\activity_gallery.xml" "$dst\res\layout\" -Force
Copy-Item "$src\res\layout\item_photo.xml" "$dst\res\layout\" -Force

# model + resources
New-Item -ItemType Directory -Force "$dst\assets" | Out-Null
Copy-Item "$src\assets\*.tflite","$src\assets\*.task" "$dst\assets\" -Force
Copy-Item "$src\res\values\ai_strings.xml","$src\res\values\ai_colors.xml" "$dst\res\values\" -Force
```

> `ai_strings.xml` / `ai_colors.xml`: nếu app bạn đã có resource **cùng tên**, xoá dòng trùng trong
> file `ai_*` (Android không cho 2 định nghĩa cùng tên). Đừng xoá dòng trong file gốc của app.

---

## 2. Dependency, assets và gradle

### 2.1 `app/build.gradle.kts`

```diff
 android {
     ...
     compileSdk = 36

+    // MediaPipe/ML Kit phát hành .so cho 4 ABI (~73 MB thô trong APK).
+    // Chia APK theo ABI để máy thật (arm64-v8a) không mang theo native x86_64 của tasks-core.
+    splits {
+        abi {
+            isEnable = true
+            reset()
+            include("arm64-v8a", "x86_64")
+            isUniversalApk = false
+        }
+    }

+    androidResources {
+        // MediaPipe đọc thẳng .tflite/.task từ assets -> phải để nguyên, không nén.
+        noCompress += listOf("tflite", "task")
+    }
+
     packaging {
         resources {
-            excludes += ...
+            excludes += listOf(
+                "META-INF/AL2.0",
+                "META-INF/LGPL2.1",
+                "META-INF/*.kotlin_module",
+            )
         }
     }
 }

 dependencies {
     // ... giữ nguyên dependency của bạn ...
+    // EfficientDet Lite0 (cảnh) + Pose Landmarker (khung 3:4). Model nằm trong assets.
+    implementation("com.google.mediapipe:tasks-vision:1.2.3")
+    // Face detection cho bước tìm khung (ưu tiên 1).
+    implementation("com.google.mlkit:face-detection:16.1.6")
 }
```

Bắt buộc đã có sẵn (app v0.1 trong repo đã có): `androidx.camera:camera-{core,camera2,lifecycle,view}:1.3.4`,
`androidx.exifinterface:exifinterface` (GalleryActivity mới dùng để xoay ảnh theo EXIF),
`androidx.appcompat`, `androidx.activity:activity-ktx` (cho `registerForActivityResult`).

Không cần bật `viewBinding` cho module AI (`AiSettingsDialog` dựng UI bằng code).

### 2.2 Assets

- Chép nguyên 2 file vào `app/src/main/assets/` (giữ đúng tên, không đổi thư mục con):
  `efficientdet_lite0.tflite` (13,8 MB), `pose_landmarker_lite.task` (5,5 MB).
- Bắt buộc có `noCompress += listOf("tflite", "task")`, nếu không MediaPipe sẽ đọc sai/lỗi.
- Code mở model bằng đường dẫn tương đối trong assets (`efficientdet_lite0.tflite`,
  `pose_landmarker_lite.task`) — đổi tên file thì phải sửa `SceneAnalyzer`/`FramingAnalyzer`.
- Không có model thì app **vẫn chạy**: `SceneAnalyzer.initialize()` trả `false`, gợi ý dáng dùng thư
  viện mẫu, tìm khung fallback về ML Kit Face → saliency → giữa.

### 2.3 Ghi chú ABI

`tasks-vision:1.2.3` không chứa `jni/` trong AAR; native nằm ở dependency
`com.google.mediapipe:tasks-core:1.2.3`, và bản này **có `x86_64`** (0.10.14 thì không: chỉ
`arm64-v8a`, `armeabi-v7a`, `x86`). Vì vậy MediaPipe nạp được trên emulator `x86_64`.
Đổi lại APK debug tăng 66.033.933 → 87.167.096 byte, trong đó +20,2 MB là riêng `lib/x86_64`
(`libmediapipe_tasks_jni.so` 19,8 MB) còn `lib/arm64-v8a` chỉ +2,9 MB. Khi bật ABI splits mỗi máy
chỉ tải phần của mình. Số đo đầy đủ: `docs/reports/2026-11-18-mediapipe-abis.md`.

---

## 3. `MainActivity.kt` — 7 mốc phải sửa (theo đúng file trong repo)

Mục tiêu: giữ nguyên toàn bộ hành vi cũ (chấm trắng/vàng, tự chụp heuristic 0.08 / 600 ms, đổi tỉ lệ,
chụp 1:1, nút Thư viện) và chỉ **thêm** phần nối dây AI.

### 3.1 Import + khai báo class

```diff
 package com.gia.aicomposer

 import android.Manifest
 import android.content.ContentValues
 import android.content.Intent
 import android.content.pm.PackageManager
 import android.graphics.Bitmap
 import android.graphics.BitmapFactory
 import android.graphics.Matrix
+import android.net.Uri
 import android.os.Build
 import android.os.Bundle
 import android.os.SystemClock
 import android.provider.MediaStore
 import android.util.Log
 import android.util.Size
 import android.widget.Button
 import android.widget.TextView
 import android.widget.Toast
 import androidx.activity.result.contract.ActivityResultContracts
 import androidx.appcompat.app.AppCompatActivity
 import androidx.camera.core.AspectRatio
+import androidx.camera.core.Camera
 import androidx.camera.core.CameraSelector
 import androidx.camera.core.ImageAnalysis
 import androidx.camera.core.ImageCapture
 ...
+import com.gia.aicomposer.integration.AiCameraAssistant
+import com.gia.aicomposer.model.SceneState
+import com.gia.aicomposer.pose.PoseSuggestion

-class MainActivity : AppCompatActivity() {
+class MainActivity : AppCompatActivity(), AiCameraAssistant.Listener {
```

### 3.2 Field mới (cạnh `tracker`)

```diff
     private lateinit var analysisExecutor: ExecutorService
     private val tracker = SubjectTracker()
+
+    private lateinit var aiAssistant: AiCameraAssistant
+    /** Đang đồng bộ switch tự chụp của UI cũ -> assistant, tránh vòng lặp listener. */
+    private var syncingAutoCapture = false
```

### 3.3 `onCreate` — sau khi đã `findViewById` xong các view

```diff
         overlay.onTargetMoved = { withinSinceMs = 0L }
+
+        // overlay là view duy nhất trong activity_main.xml (id overlay_view); assistant không
+        // tự bọc PreviewView bằng FrameLayout nữa.
+        aiAssistant = AiCameraAssistant(this, previewView, overlay)
+        aiAssistant.listener = this
+
+        // CameraX chỉ cho một ImageAnalysis -> SubjectTracker cũ chạy trên analyzer của assistant.
+        aiAssistant.legacyFrameTap = ::analyzeFrameForSubject
```

Trong `activity_main.xml` chỉ đổi đúng một chỗ: `CompositionOverlayView` →
`com.gia.aicomposer.overlay.CameraOverlayView`, giữ nguyên `@+id/overlay_view`.

### 3.4 `bindCameraUseCases()` — dùng chung `ImageCapture` + `ImageAnalysis` của assistant

```diff
                 // Builder của assistant gắn callback AF/AE; ImageCapture.Builder() trơn sẽ làm pipeline
        // không bao giờ tự chụp (xem mục 3.8).
        imageCapture = aiAssistant.imageCaptureBuilder()
            .setTargetAspectRatio(aspect)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
+        imageCapture?.let { aiAssistant.setImageCapture(it) }

-        val analysis = ImageAnalysis.Builder()
-            .setTargetResolution(Size(640, 480))
-            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
-            .build()
-        analysis.setAnalyzer(analysisExecutor, ::analyzeFrame)
+        // Assistant đã cấu hình 640x480, RGBA_8888, KEEP_ONLY_LATEST và analyzer riêng.
+        val analysis = aiAssistant.imageAnalysis

+        // Một aspect duy nhất cho cả overlay và pipeline (trước đây pipeline luôn nhận 0.75f).
+        aiAssistant.setPreviewAspect(if (aspect == AspectRatio.RATIO_16_9) 9f / 16f else 3f / 4f)

         provider.unbindAll()
         try {
-            provider.bindToLifecycle(
+            val camera = provider.bindToLifecycle(
                 this,
                 CameraSelector.DEFAULT_BACK_CAMERA,
                 preview,
                 imageCapture,
                 analysis
             )
+            aiAssistant.onCameraBound(camera)   // đọc zoom ratio cho độ trôi khung
+            aiAssistant.onCameraOpened()        // pipeline chạy tiếp (nếu tự chụp đang bật)
             setStatus("Di chuyen may de cham vang trung cham trang")
```

> Nếu app bạn đang bind **một `ImageAnalysis` khác**, bỏ nó khỏi `bindToLifecycle` và chuyển analyzer
> của nó vào `aiAssistant.legacyFrameTap` (như `SubjectTracker` ở đây). Không bind 2 `ImageAnalysis`.

### 3.5 Đổi `analyzeFrame(image: ImageProxy)` — **không** đóng proxy nữa

Assistant đóng proxy sau khi dùng xong. Giữ nguyên phần đọc/tính điểm của `SubjectTracker`:

```diff
-    private fun analyzeFrame(image: ImageProxy) {
+    /** Chạy trên analyzer của assistant; KHÔNG đóng proxy (assistant đóng). */
+    private fun analyzeFrameForSubject(image: ImageProxy) {
         try {
             val point = tracker.analyze(image)
             if (point != null) {
                 runOnUiThread { onSubjectPoint(point.first, point.second) }
             }
         } catch (e: Exception) {
             Log.e(TAG, "analyze error", e)
-        } finally {
-            image.close()
         }
     }
```

`analysisExecutor` vẫn giữ: nó còn dùng cho `saveSquareCropped()` (chế độ 1:1) và `shutdown()` ở
`onDestroy`.

### 3.6 `onSubjectPoint` — nhường quyền tự chụp khi pipeline AI đang bật

Chấm vàng vẫn cập nhật như cũ; chỉ khi người dùng bật tự chụp của AI thì **không** để heuristic cũ
chụp thêm lần nữa:

```diff
     private fun onSubjectPoint(x: Float, y: Float) {
         overlay.subjectPoint = Pair(x, y)
         overlay.invalidate()
         if (!autoCaptureEnabled || capturing) return
+        // Pipeline AI đang tự căn khung/tự chụp: nó sở hữu việc chụp, tránh 2 lần chụp.
+        if (aiAssistant.settings.autoCaptureEnabled) return
         val now = SystemClock.uptimeMillis()
```

Khi AI tắt (mặc định `autoCaptureEnabled = false` trong `AppSettings`), hành vi cũ **giữ nguyên 100%**
(ngưỡng 0.08, giữ 600 ms, nghỉ 2.5 s).

### 3.7 Vòng đời + callback (dán vào cuối class, cạnh `onDestroy`)

```diff
+    override fun onStart() {
+        super.onStart()
+        aiAssistant.start()                 // bật cảm biến, chạy tiếp pipeline đang tạm dừng
+    }
+
+    override fun onStop() {
+        aiAssistant.stop()                  // chỉ tạm dừng, không reset
+        super.onStop()
+    }
+
     override fun onDestroy() {
+        aiAssistant.release()               // đóng model + executor của assistant
         super.onDestroy()
         analysisExecutor.shutdown()
     }
+
+    // ---------------------------------------------------------- AiCameraAssistant.Listener
+
+    override fun onGuidance(text: String) = setStatus(text)
+
+    override fun onPoseSuggested(suggestion: PoseSuggestion, scene: SceneState) {
+        // Gợi ý dáng: overlay đã tự vẽ hình que, ở đây chỉ hiện tên + điểm.
+        setStatus("${suggestion.pose.displayName} • ${"%.1f".format(suggestion.score)}")
+    }
+
+    override fun onAutoCaptureChanged(enabled: Boolean) {
+        syncingAutoCapture = true
+        autoCaptureEnabled = enabled
+        withinSinceMs = 0L
+        updateAutoButton()
+        syncingAutoCapture = false
+    }
+
+    override fun onCaptureSaved(uri: Uri) {
+        // Ảnh đã nằm trong MediaStore (Pictures/AICameraComposer) -> mở Thư viện là thấy.
+        Toast.makeText(this, "Da luu anh", Toast.LENGTH_SHORT).show()
+    }
+
+    override fun onCaptureFailed(message: String?) {
+        Toast.makeText(this, "Chup loi: $message", Toast.LENGTH_SHORT).show()
+    }
```

Nếu muốn nút "Tự dong" cũ điều khiển luôn pipeline AI, sửa listener của nút:

```diff
         autoButton.setOnClickListener {
             autoCaptureEnabled = !autoCaptureEnabled
             withinSinceMs = 0L
             updateAutoButton()
+            if (!syncingAutoCapture) aiAssistant.setAutoCaptureEnabled(autoCaptureEnabled)
             setStatus(if (autoCaptureEnabled) "Tu dong chup: BAT" else "Che do tay - cham vang la chu the")
         }
```

`onCaptureSaved(uri)` nhận `content://media/...` (Android 10+) hoặc `file://` (API 26-28). Muốn mở
ảnh ngay bằng trình xem của máy:
`startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))`
— uri `content://` nên không gặp `FileUriExposedException`.

### 3.8 Đổi camera trước/sau (selfie) — lật overlay

PreviewView tự lật ngang hình của camera trước, còn khung `ImageAnalysis` thì **không** lật. Vì vậy
mọi toạ độ tracker phải lật lại lúc vẽ, còn dữ liệu lưu vẫn theo không gian camera:

```kotlin
private var lensFacing = CameraSelector.LENS_FACING_BACK

private fun switchCamera() {
    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
        CameraSelector.LENS_FACING_FRONT
    } else {
        CameraSelector.LENS_FACING_BACK
    }
    // Overlay hình que + khung 3:4 của assistant tự lật phần vẽ.
    aiAssistant.setFrontCameraActive(lensFacing == CameraSelector.LENS_FACING_FRONT)
    bindCameraUseCases()
}
```

- Gọi `setFrontCameraActive` **mỗi lần** bind camera, kể cả lần đầu, để cờ không lệch với
  `CameraSelector` thực tế.
- `CameraOverlayView` lật **mọi** điểm vẽ (tâm khung 3:4, hình que, chấm vàng) quanh trục giữa
  vùng preview. Trước đây chỉ `PoseOverlayView` lật nên chấm vàng lệch khi selfie.
- `SubjectTracker` không cần sửa: nó đọc khung phân tích (chưa mirror) và trả toạ độ chuẩn hoá theo
  không gian camera.
- Ảnh chụp bằng camera trước chỉ bị lật nếu bạn tự lật khi lưu; CameraX trả ảnh đúng chiều như cảm
  biến ghi, không mirror.

---

## 4. `SubjectTracker.kt` — 1 chỗ sửa bắt buộc

Vì `SubjectTracker` giờ chạy trên `ImageAnalysis` **RGBA_8888** của assistant (trước đây là YUV mặc
định), phải tính luma từ R/G/B. Sửa đúng 2 chỗ, **không** đổi thuật toán, EMA hay phần xoay toạ độ.

```diff
         val yPlane = image.planes[0]
         val buffer: ByteBuffer = yPlane.buffer
         val rowStride = yPlane.rowStride
+        val pixelStride = yPlane.pixelStride
@@
-                        val idx = y * rowStride + x
-                        if (idx < buffer.capacity()) {
-                            sum += buffer.get(idx).toInt() and 0xFF
-                            n++
-                        }
+                        val idx = y * rowStride + x * pixelStride
+                        if (pixelStride >= 3) {
+                            // RGBA_8888: luma BT.601 xấp xỉ, thang 0..255 như mặt phẳng Y.
+                            if (idx + 2 < buffer.capacity()) {
+                                val red = buffer.get(idx).toInt() and 0xFF
+                                val green = buffer.get(idx + 1).toInt() and 0xFF
+                                val blue = buffer.get(idx + 2).toInt() and 0xFF
+                                sum += (red * 77 + green * 150 + blue * 29) shr 8
+                                n++
+                            }
+                        } else if (idx < buffer.capacity()) {
+                            sum += buffer.get(idx).toInt() and 0xFF
+                            n++
+                        }
```

- `pixelStride == 1` (YUV): `x * 1 == x` → kết quả **giống hệt** bản cũ.
- `pixelStride == 3/4` (RGBA): lấy đúng điểm ảnh thay vì trượt byte, chấm vàng không nhảy loạn.
- Không sửa `LOCK_THRESHOLD`, `LOCK_HOLD_MS`, `AUTO_COOLDOWN_MS` trong `MainActivity` (0.08 / 600 / 2500).

Nếu bạn **giữ** `ImageAnalysis` YUV riêng cho `SubjectTracker` (không dùng `legacyFrameTap`), có thể bỏ
qua mục này — nhưng khi đó không được bind 2 `ImageAnalysis`; cách duy nhất hợp lệ là chạy nó trong
`legacyFrameTap`.

---

## 5. `overlay/CameraOverlayView.kt` — overlay duy nhất

- Trước đây là **hai** view chồng nhau: `CompositionOverlayView` (lưới 1/3, chấm trắng, chấm vàng,
  chạm đổi điểm mục tiêu) và `PoseOverlayView` (khung 3:4, hình que, vòng tâm). Hai view tính vùng
  preview theo hai quy ước aspect khác nhau (`4/3` của cảm biến vs `0.75` của nội dung hiển thị),
  nên khung 3:4 và chấm vàng lệch nhau, và mỗi frame bị `invalidate()` hai lần.
- Giờ chỉ còn một view, khai báo đúng chỗ cũ trong `activity_main.xml` với id `overlay_view` giữ
  nguyên. Một `onDraw` vẽ dưới → trên: lưới → khung 3:4 → hình que → vòng tâm + cung giữ → đường
  nối + chấm vàng → chấm trắng mục tiêu.
- Một `previewAspect` duy nhất (aspect hiển thị, rộng/cao) dùng cho cả view và pipeline — trước đây
  `pipeline.setViewport()` luôn nhận `0.75f` kể cả ở 16:9. Thay hai dòng cũ bằng
  `aiAssistant.setPreviewAspect(aspect)` trong `bindCameraUseCases()`.
- `showFrame` là thuộc tính suy ra (`showPose && pose != null`) nên không cần gán ở
  `MainActivity.suggestPose()` / `openSettings()` nữa — khung 3:4 luôn đi theo hình que.
- Xoá `CompositionOverlayView.kt`, `overlay/PoseOverlayView.kt`, `integration/AiOverlayHost.kt`.
- Chạm đổi điểm mục tiêu, `distanceToTarget()` và ngưỡng `LOCK_THRESHOLD` 0.08 / `LOCK_HOLD_MS`
  600 ms không đổi.

---

## 6. `GalleryActivity` + luồng lưu/xem ảnh

### 6.1 Vì sao phải sửa

Bản cũ trong repo:

- chỉ query `MediaStore` với `DISPLAY_NAME LIKE 'AIC_%'`;
- pipeline AI (`AiCameraAssistant`) lại ghi vào `getExternalFilesDir(Pictures)/AICameraComposer` bằng
  `OutputFileOptions.Builder(File)` → **không có trong MediaStore** → ảnh tự chụp **không bao giờ hiện**
  trong thư viện;
- không xin quyền đọc trên API 26-28; không nạp lại danh sách khi quay về từ camera.

### 6.2 Hợp đồng lưu ảnh mới (`util/PhotoStore.kt`)

| | Nơi lưu | Ai thấy |
|---|---|---|
| Android 10+ (API 29+) | `MediaStore` album `Pictures/AICameraComposer`, `IS_PENDING=1` lúc ghi, `publish()` xoá cờ | `GalleryActivity` + app Thư viện/Gallery của máy |
| Android 8-9 (API 26-28) | `getExternalFilesDir(Pictures)/AICameraComposer` | `GalleryActivity` (quét thêm thư mục này) |
| MediaStore lỗi | tự fallback về thư mục app, log `PhotoStore` | nt |

`AiCameraAssistant.capturePhoto()` đã chuyển sang `PhotoStore.prepare()` / `publish()` / `discard()`,
nên ảnh của pipeline và ảnh chụp tay (`MainActivity.takePhoto()` – vốn đã ghi MediaStore) nằm **cùng
một album**, cùng tiền tố `AIC_`. `onCaptureSaved(uri)` trả `content://...` dùng trực tiếp cho
`ACTION_VIEW`.

Manifest (repo đã có, chỉ cần kiểm tra):

```xml
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
...
<activity android:name=".GalleryActivity" android:exported="false" />
```

### 6.3 Thay `GalleryActivity` + 2 file resource

1. Ghi đè `GalleryActivity.kt` bằng bản workspace (quyền đọc, MediaStore + thư mục app, `onResume`,
   thumbnail `loadThumbnail`/decode lấy mẫu, decode full ≤ 2048 px có xoay EXIF, trạng thái rỗng).
2. `res/layout/activity_gallery.xml` — thêm vào trong `LinearLayout`, **trước** `GridView`:

```diff
+        <TextView
+            android:id="@+id/empty_state"
+            android:layout_width="match_parent"
+            android:layout_height="wrap_content"
+            android:gravity="center"
+            android:padding="24dp"
+            android:text="@string/gallery_empty"
+            android:textColor="@android:color/white"
+            android:textSize="15sp"
+            android:visibility="gone" />
+
         <GridView
             android:id="@+id/photo_grid"
```

3. `res/values/strings.xml` — thêm 1 dòng (không ghi đè file):

```diff
     <string name="app_name">AI Composition Camera</string>
+    <string name="gallery_empty">Chưa có ảnh nào — chụp một tấm trước</string>
```

4. `MainActivity` giữ nguyên nút `gallery_button` → `startActivity(Intent(this, GalleryActivity::class.java))`.

### 6.4 Kiểm tra luồng ảnh (sau khi merge)

1. Chụp tay (CHUP) → mở **Thư viện** → ảnh mới nhất ở ô đầu.
2. Bật tự chụp AI → đợi pipeline chụp → quay lại app → mở **Thư viện** → ảnh tự chụp có mặt (nhờ
   `publish()` xoá `IS_PENDING`; ảnh đang ghi sẽ không hiện nửa vời).
3. Xoay máy ở chế độ 1:1 → ảnh vuông vẫn mở đúng chiều (EXIF).
4. Chạm thumbnail → ảnh full màn hình, chạm lần nữa để đóng.
5. Android 8-9: cấp `READ_EXTERNAL_STORAGE` khi được hỏi → thấy ảnh trong thư mục app.
6. Xoá app data: thư viện rỗng hiện "Chưa có ảnh nào — chụp một tấm trước", không crash.

`adb` kiểm tra nhanh:

```bat
adb shell content query --uri content://media/external/images/media --projection _display_name:relative_path --where "_display_name LIKE 'AIC_%'"
```

---

## 7. Thứ tự merge + build

1. Chép code module (mục 1.1) + `PhotoStore.kt`.
2. Chép 2 model vào `assets/` (mục 2.2).
3. Sửa `app/build.gradle.kts` (mục 2.1) → **Sync Now**.
4. Sửa `SubjectTracker.kt` (mục 4) — 2 chỗ.
5. Dán 7 mốc vào `MainActivity.kt` (mục 3).
6. Ghi đè `GalleryActivity.kt` + layout + string (mục 6.3).
7. Build:

```bat
gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
```

Kỳ vọng: `BUILD SUCCESSFUL`; APK còn đúng 2 ABI; mở app không crash; chấm vàng/trắng và tự chụp
heuristic vẫn như trước; thư viện thấy cả ảnh chụp tay và ảnh pipeline AI.

Checklist test chi tiết (emulator E1–E12, máy thật D1–D11, thư viện G1–G5): xem `TESTING.md`.

---

## 8. Kích thước APK — số đo thực tế sau khi bật ABI splits

`app/build.gradle.kts` dùng `splits { abi { isEnable = true; reset(); include("arm64-v8a", "x86_64");
isUniversalApk = false } }` thay cho `ndk.abiFilters`. `./gradlew :app:assembleDebug` sinh đúng 2 APK,
không có bản universal (nên không còn `app-debug.apk`):

| APK | Dung lượng | native `.so` | Dùng cho |
|---|---|---|---|
| `app-arm64-v8a-debug.apk` | **57.513.275 byte (54,9 MiB)** | 23,2 MiB | máy thật |
| `app-x86_64-debug.apk` | **62.820.210 byte (59,9 MiB)** | 28,3 MiB | emulator |

`aapt dump badging` xác nhận mỗi file chỉ khai một ABI: `native-code: 'arm64-v8a'` và
`native-code: 'x86_64'`.

So với một APK gộp cả 2 ABI trước khi split (`tasks-vision:1.2.3`, 87.167.096 byte): bản arm64 giảm
**29.653.821 byte (−28,3 MiB, −34,0%)**, bản x86_64 giảm 24.346.886 byte (−23,2 MiB, −27,9%). Toàn bộ
+20,2 MB native mà `tasks-core:1.2.3` thêm vào `lib/x86_64` giờ chỉ nằm trong APK emulator.

### 8.1 Phần nào phình to

| Hạng mục | APK arm64 | APK x86_64 |
|---|---|---|
| `lib/arm64-v8a/` | 24.336.024 byte (23,2 MiB, 3 lib) | — |
| `lib/x86_64/` | — | 29.644.888 byte (28,3 MiB, 3 lib) |
| `assets/` | 22,8 MiB | 22,8 MiB |
| `classes*.dex` (12 file) | 20,7 MiB | 20,7 MiB |
| res + `resources.arsc` | 1,7 MiB | 1,7 MiB |

File đơn lẻ lớn nhất trong APK arm64:

```
15.971.160 byte  lib/arm64-v8a/libmediapipe_tasks_jni.so
13.836.895 byte  assets/efficientdet_lite0.tflite            ← model, giữ
 8.335.920 byte  lib/arm64-v8a/libface_detector_v2_jni.so
 5.777.746 byte  assets/pose_landmarker_lite.task             ← model, giữ
```

Kết luận: **không phải model làm APK phình** — 19,6 MiB model là bắt buộc và có trong cả 2 APK. Phần
dư là **native `.so` của MediaPipe + ML Kit nhân nhiều ABI** (73,5 MiB khi đóng cả 4 ABI) và **dex
20,7 MiB / 12 file** (do minify tắt ở bản debug). Splits cắt phần native của ABI còn lại.

### 8.2 Cả 2 APK vẫn đủ model

Kiểm tra trực tiếp trong từng APK (`unzip -lv`, cả hai giống nhau):

```
assets/efficientdet_lite0.tflite   13.836.895 byte   compress=0 (stored)
assets/pose_landmarker_lite.task    5.777.746 byte   compress=0 (stored)
```

Hai model không bị nén lại hay strip; SHA-256 khớp bản trước splits (`efficientdet_lite0.tflite` =
`40338edf…`, `pose_landmarker_lite.task` = `59929e1d…`). Class cần thiết vẫn có trong dex:
`GalleryActivity`, `util/PhotoStore`, `integration/AiCameraAssistant`, `SubjectTracker`.

### 8.3 Nếu cần nhỏ hơn nữa (chưa áp dụng)

- Bật R8 cho bản release (`isMinifyEnabled = true`) → dex giảm mạnh; bản debug hiện tắt nên vẫn ~20,7 MiB.
- `assets/models_bundled/*` (~4,6 MiB) là model nhúng sẵn của `mlkit:face-detection`; chỉ bỏ được nếu
  thay bằng bộ phát hiện mặt khác.
- Bỏ hẳn ABI `x86_64` khỏi `splits.abi.include` khi không cần emulator nữa → CI chỉ build 1 APK.

---

## 9. Ghi chú xung đột & giới hạn

- **Một `ImageAnalysis`**: bắt buộc dùng `aiAssistant.imageAnalysis`; analyzer cũ đi qua
  `legacyFrameTap`.
- **Một `ImageCapture`**: truyền instance của activity vào `setImageCapture()`; cả chụp tay và pipeline
  dùng chung.
- **Hai lần tự chụp**: đã chặn bằng `if (aiAssistant.settings.autoCaptureEnabled) return` trong
  `onSubjectPoint` (mục 3.6).
- **Resource trùng tên**: xoá dòng trùng trong `ai_strings.xml` / `ai_colors.xml`.
- **Tỉ lệ khung**: pipeline AI thiết kế cho preview 4:3 dọc (`previewAspect = 0.75`); đổi 16:9 thì
  khung 3:4 lệch như mục 5.
- **Emulator x86_64**: từ `tasks-vision:1.2.3` (native ở `tasks-core:1.2.3`) MediaPipe nạp được trên
  emulator; nếu log vẫn báo `pose landmarker unavailable` thì là lỗi tương thích model — xem
  `docs/reports/2026-11-18-mediapipe-abis.md`.
- **Chưa chạy runtime**: môi trường build là Linux cloud, không có emulator/KVM, nên chưa xác nhận
  bằng mắt overlay/cảm biến/tự chụp và chưa mở thử ảnh trong gallery trên máy thật. Những gì đã xác
  nhận: build xanh 2 lần, APK đúng ABI, đủ model (SHA-256 khớp), class mới có trong dex.
