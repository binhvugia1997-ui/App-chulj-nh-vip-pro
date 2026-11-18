# SubjectTracker thật bằng model — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Thay heuristic độ sáng của `SubjectTracker` bằng model nhận diện thật (MediaPipe EfficientDet Lite0, model đã có trong `assets/`) để chấm vàng và tự chụp bám đúng chủ thể, vẫn giữ đường lùi heuristic khi model không có.

**Architecture:** Ba lớp nhỏ, hai lớp đầu thuần Kotlin và test được bằng JVM: `SubjectPicker` (chọn điểm chủ thể từ danh sách detection) và `SubjectSmoother` (EMA + chính sách chuyển sang heuristic). `SubjectTracker` chỉ còn là keo dán: chạy detector theo nhịp, gọi picker/smoother, đọc khung YUV hoặc RGBA. `MainActivity` chỉ đổi cách khởi tạo và đóng tracker; `AiCameraAssistant` không sửa.

**Tech Stack:** Kotlin 2.0.21, AGP 8.9.1, CameraX 1.3.4, MediaPipe `tasks-vision:1.2.3` (`ObjectDetector`, IMAGE mode, `efficientdet_lite0.tflite` đã bundle), JUnit4 + Robolectric 4.13.

**Spec:** mục 1 trong danh sách follow-up ngày 2026-11-18 ("Thay heuristic bằng model nhận diện thật… plan đã dự trù hook `analyze()`") + `HANDOFF.md` (ngưỡng đã chốt) + `INTEGRATION.md` mục 4 (hợp đồng `legacyFrameTap`) + `SUBJECT_HEURISTIC` trong `SubjectTracker.kt`.

## Global Constraints

- Không đổi ngưỡng tự chụp đã chốt: `MainActivity.LOCK_THRESHOLD = 0.08f`, `LOCK_HOLD_MS = 600L`, `AUTO_COOLDOWN_MS = 2500L`; `FrameLockTracker` 30°/0.55/5dp/25dp/0.9; `CapturePipeline` 15dp/1000ms/600ms; `StabilityMonitor` 0.08/0.12/50ms×10; `FocusGate` 400ms.
- **Không thêm dependency và không thêm asset.** Dùng lại `efficientdet_lite0.tflite` + `tasks-vision:1.2.3` đã có (quyết định: ML Kit Object Detection sẽ thêm ~4 MB native và một bộ nhãn khác; segmentation cần thêm file model, cả hai đều không cần thiết khi EfficientDet đã bundle).
- Giữ nguyên chữ ký công khai `SubjectTracker.analyze(image: ImageProxy): Pair<Float, Float>?` để `MainActivity` và `legacyFrameTap` không phải sửa vòng gọi.
- `./gradlew :app:testDebugUnitTest :app:assembleDebug` phải xanh (56 test hiện có) sau mỗi task.
- Không copy code từ repo khác; comment/thông báo trong code bằng tiếng Việt không dấu như file hiện tại.
- Môi trường build trong workspace:
  `export JAVA_HOME=$HOME/toolchain/jdk; export ANDROID_HOME=$HOME/toolchain/sdk; echo "sdk.dir=$ANDROID_HOME" > local.properties`
- Commit sau mỗi task (conventional commit, tiếng Anh); không push trừ khi người dùng yêu cầu.

## Review Focus

1. **Model mất dấu đúng một khung** (người quay lưng, che mặt, detection rơi): chấm vàng không được nhảy về giữa khung. Test: Task 2 (`oneMissDoesNotRequestFallback`) + Task 1 (`emptyCandidatesReturnNull`).
2. **Chủ thể bị cắt mép khung** (box chạm biên dưới): điểm vàng phải nằm trong khung, không trôi ra ngoài preview. Test: Task 1 (`pointStaysInsideMargin`).
3. **Khung không có người** (chụp đồ vật, phong cảnh): vẫn phải có điểm bám vật lớn nhất thay vì rơi về giữa. Test: Task 1 (`largestObjectWinsWithoutPerson`).
4. **Máy không nạp được MediaPipe** (emulator/ABI lạ): hành vi phải y hệt v0.1 chứ không treo hay trả null liên tục. Test: Task 2 (`fallbackKeepsWorkingAfterTwoMisses`) + Task 3 bước build.
5. **Hai định dạng khung** (RGBA của assistant, YUV mặc định của `MainActivity`): không được đọc sai màu/không đọc được. Kiểm: Task 3 Step 4 (build + chạy checklist D13/D14 trên máy).

---

### Task 1: `SubjectPicker` — chọn điểm chủ thể từ detection

**Files:**
- Create: `app/src/main/java/com/gia/aicomposer/vision/SubjectPicker.kt`
- Test: `app/src/test/java/com/gia/aicomposer/vision/SubjectPickerTest.kt`

**Interfaces:**
- Consumes: `android.graphics.RectF`.
- Produces:
  - `data class SubjectCandidate(val label: String, val score: Float, val box: RectF)`
  - `object SubjectPicker { fun pick(candidates: List<SubjectCandidate>): Pair<Float, Float>? }`
  - `const val MIN_SCORE = 0.40f`, `const val TORSO_OFFSET = 0.15f`, `const val EDGE_MARGIN = 0.05f` (trong `SubjectPicker`)

- [ ] **Step 1: Viết test đỏ**

`SubjectPickerTest.kt` (Robolectric như `FreeRegionFinderTest`):

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubjectPickerTest {

    private fun c(label: String, score: Float, l: Float, t: Float, r: Float, b: Float) =
        SubjectCandidate(label, score, RectF(l, t, r, b))

    @Test
    fun personWinsOverBiggerObject() {
        val point = SubjectPicker.pick(
            listOf(
                c("couch", 0.95f, 0f, 0.4f, 1f, 1f),
                c("person", 0.60f, 0.3f, 0.2f, 0.7f, 0.8f),
            ),
        )
        assertEquals(0.5f, point!!.first, 1e-4f)
        // tam box 0.5 + 0.6 * TORSO_OFFSET
        assertEquals(0.59f, point.second, 1e-4f)
    }

    @Test
    fun largestObjectWinsWithoutPerson() {
        val point = SubjectPicker.pick(
            listOf(
                c("cup", 0.9f, 0.1f, 0.6f, 0.2f, 0.75f),
                c("chair", 0.7f, 0.2f, 0.2f, 0.8f, 0.9f),
            ),
        )
        assertEquals(0.5f, point!!.first, 1e-4f)
        assertEquals(0.55f + 0.7f * 0.15f, point.second, 1e-4f)
    }

    @Test
    fun lowScoresAreIgnored() {
        assertNull(SubjectPicker.pick(listOf(c("person", 0.39f, 0.2f, 0.2f, 0.8f, 0.9f))))
    }

    @Test
    fun emptyCandidatesReturnNull() {
        assertNull(SubjectPicker.pick(emptyList()))
    }

    @Test
    fun pointStaysInsideMargin() {
        val point = SubjectPicker.pick(listOf(c("person", 0.9f, 0f, 0.9f, 1f, 1f)))!!
        assertEquals(0.95f, point.second, 1e-4f)
    }
}
```

- [ ] **Step 2: Chạy test để thấy đỏ**

Run: `./gradlew :app:testDebugUnitTest --tests '*SubjectPickerTest*' --no-daemon`
Expected: FAIL `Unresolved reference: SubjectCandidate` / `SubjectPicker`.

- [ ] **Step 3: Viết `SubjectPicker`**

`pick`: lọc `score >= MIN_SCORE`; nếu có `label == "person"` chọn person điểm cao nhất, ngược lại chọn box có diện tích lớn nhất. Điểm trả về: `x = box.centerX()`, `y = box.centerY() + box.height() * TORSO_OFFSET`, cả hai `coerceIn(EDGE_MARGIN, 1f - EDGE_MARGIN)`. Danh sách rỗng sau lọc → `null`.

- [ ] **Step 4: Chạy test để thấy xanh**

Run: `./gradlew :app:testDebugUnitTest --tests '*SubjectPickerTest*' --no-daemon`
Expected: 5 test PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/gia/aicomposer/vision/SubjectPicker.kt app/src/test/java/com/gia/aicomposer/vision/SubjectPickerTest.kt
git commit -m "feat: pick the subject point from object detections"
```

---

### Task 2: `SubjectSmoother` — EMA và chính sách rơi về heuristic

**Files:**
- Create: `app/src/main/java/com/gia/aicomposer/vision/SubjectSmoother.kt`
- Test: `app/src/test/java/com/gia/aicomposer/vision/SubjectSmootherTest.kt`

**Interfaces:**
- Consumes: không.
- Produces:
  - `class SubjectSmoother(private val alpha: Float = 0.3f, private val fallbackAfterMisses: Int = 2)`
  - `val wantsFallback: Boolean` (chỉ đọc)
  - `fun onModelPoint(point: Pair<Float, Float>?): Pair<Float, Float>?`
  - `fun onHeuristicPoint(point: Pair<Float, Float>?): Pair<Float, Float>?`
  - `fun reset()`

- [ ] **Step 1: Viết test đỏ**

```kotlin
class SubjectSmootherTest {

    @Test
    fun firstPointIsUsedExactly() {
        assertEquals(0.4f to 0.4f, SubjectSmoother().onModelPoint(0.4f to 0.4f))
    }

    @Test
    fun laterPointsBlendWithAlpha() {
        val smoother = SubjectSmoother()
        smoother.onModelPoint(0.4f to 0.4f)
        val point = smoother.onModelPoint(0.6f to 0.6f)!!
        assertEquals(0.46f, point.first, 1e-4f)
        assertEquals(0.46f, point.second, 1e-4f)
    }

    @Test
    fun oneMissDoesNotRequestFallback() {
        val smoother = SubjectSmoother()
        smoother.onModelPoint(0.4f to 0.4f)
        assertNull(smoother.onModelPoint(null))
        assertFalse(smoother.wantsFallback)
    }

    @Test
    fun twoMissesRequestFallbackUntilTheModelHitsAgain() {
        val smoother = SubjectSmoother()
        smoother.onModelPoint(null)
        smoother.onModelPoint(null)
        assertTrue(smoother.wantsFallback)
        smoother.onModelPoint(0.5f to 0.5f)
        assertFalse(smoother.wantsFallback)
    }

    @Test
    fun fallbackKeepsWorkingAfterTwoMisses() {
        val smoother = SubjectSmoother()
        smoother.onModelPoint(null)
        smoother.onModelPoint(null)
        assertEquals(0.3f to 0.7f, smoother.onHeuristicPoint(0.3f to 0.7f))
        assertTrue(smoother.wantsFallback)
    }

    @Test
    fun resetForgetsTheSmoothedPoint() {
        val smoother = SubjectSmoother()
        smoother.onModelPoint(0.2f to 0.2f)
        smoother.reset()
        assertEquals(0.9f to 0.9f, smoother.onModelPoint(0.9f to 0.9f))
    }
}
```

- [ ] **Step 2: Chạy test để thấy đỏ**

Run: `./gradlew :app:testDebugUnitTest --tests '*SubjectSmootherTest*' --no-daemon`
Expected: FAIL `Unresolved reference: SubjectSmoother`.

- [ ] **Step 3: Viết `SubjectSmoother`**

`onModelPoint(null)`: tăng `misses`, đặt `wantsFallback = misses >= fallbackAfterMisses`, trả `null`. `onModelPoint(p)`: `misses = 0`, `wantsFallback = false`, trả điểm đã làm mượt. Làm mượt: lần đầu gán đúng giá trị; sau đó `x = (1 - alpha) * smoothX + alpha * p.first` (y tương tự). `onHeuristicPoint(p)`: trả điểm đã làm mượt, không đụng `misses`/`wantsFallback`; `null` trả `null`. `reset()`: xoá điểm mượt + `misses` + `wantsFallback`.

- [ ] **Step 4: Chạy test để thấy xanh**

Run: `./gradlew :app:testDebugUnitTest --tests '*SubjectSmootherTest*' --no-daemon`
Expected: 6 test PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/gia/aicomposer/vision/SubjectSmoother.kt app/src/test/java/com/gia/aicomposer/vision/SubjectSmootherTest.kt
git commit -m "feat: smooth subject points and fall back when detection drops"
```

---

### Task 3: `SubjectDetector` + `SubjectTracker` dùng model, `MainActivity` nối dây

**Files:**
- Create: `app/src/main/java/com/gia/aicomposer/vision/SubjectDetector.kt`
- Modify: `app/src/main/java/com/gia/aicomposer/SubjectTracker.kt` (viết lại phần đầu, giữ hàm `analyze`)
- Modify: `app/src/main/java/com/gia/aicomposer/util/ImageConversions.kt` (thêm `toSubjectBitmap`)
- Modify: `app/src/main/java/com/gia/aicomposer/MainActivity.kt:76` (`SubjectTracker(this)`), `:147-150` (`tracker.close()`)
- Modify: `INTEGRATION.md` mục 4 (một dòng: tracker cần `Context` và `close()`)
- Modify: `TESTING.md` (thêm D13/D14)

**Interfaces:**
- Consumes: `SubjectCandidate`, `SubjectPicker.pick`, `SubjectSmoother`; `efficientdet_lite0.tflite` trong assets.
- Produces:
  - `interface SubjectDetector { fun detect(bitmap: Bitmap, rotationDegrees: Int): List<SubjectCandidate>; fun close() }`
  - `class MediaPipeSubjectDetector(context: Context) : SubjectDetector`
  - `class SubjectTracker(context: Context, detector: SubjectDetector? = null, clock: () -> Long = SystemClock::elapsedRealtime)`
  - `SubjectTracker.analyze(image: ImageProxy): Pair<Float, Float>?`, `SubjectTracker.close()`

- [ ] **Step 1: Viết `SubjectDetector`**

`MediaPipeSubjectDetector`: `ObjectDetector.createFromOptions` với `BaseOptions.setModelAssetPath("efficientdet_lite0.tflite")`, `Delegate.CPU`, `RunningMode.IMAGE`, `setMaxResults(10)`, `setScoreThreshold(0.35f)`. `detect` dựng `BitmapImageBuilder(bitmap).build()`, gọi `detector.detect(image, ImageProcessingOptions.builder().setRotationDegrees(rotation).build())`, map `result.detections()` → `SubjectCandidate(category.categoryName(), category.score(), RectF(detection.boundingBox()))`, `finally image.close()`. Constructor ném lỗi được; `SubjectTracker` bắt và chuyển sang chế độ heuristic (một `Log.w`).

- [ ] **Step 2: Thêm `toSubjectBitmap`**

`ImageConversions.kt`: `fun ImageProxy.toSubjectBitmap(): Bitmap = if (format == PixelFormat.RGBA_8888) toArgbBitmap() else toBitmap()` (import `androidx.camera.core.ImageProxy.toBitmap` nếu cần; CameraX 1.3 có `ImageProxy#toBitmap()` cho YUV). Không đổi `toArgbBitmap()` để assistant giữ nguyên hành vi.

- [ ] **Step 3: Viết lại `SubjectTracker`**

Giữ KDoc giải thích: model là đường chính, heuristic độ sáng là đường lùi. Trạng thái: `picker`, `smoother`, `detector` (nullable, `lazy` khởi tạo từ `context.applicationContext`, lỗi → `null`), `lastModelMs = 0L`, `lastSmoothed: Pair<Float, Float>? = null`.

`analyze(image)`:
1. `now = clock()`. Nếu `detector == null` → trả `heuristicPoint(image)` đã làm mượt qua `smoother.onHeuristicPoint(...)` (đúng hành vi v0.1, EMA 0.7/0.3).
2. Nếu `now - lastModelMs >= MODEL_INTERVAL_MS` (`250L`, hằng companion): cập nhật `lastModelMs`, `bitmap = image.toSubjectBitmap()`, `point = SubjectPicker.pick(detector.detect(bitmap, image.imageInfo.rotationDegrees))` trong `try/catch/finally { bitmap.recycle() }`; lỗi → `point = null` + `Log.w`. `smoothed = smoother.onModelPoint(point)`; nếu `smoothed != null` → cache `lastSmoothed` và trả về; nếu `smoother.wantsFallback` → đi tiếp bước 3; ngược lại trả `null` (một khung miss: giữ nguyên chấm vàng cũ).
3. Khi `smoother.wantsFallback` → `heuristic = heuristicPoint(image)`; `smoothed = smoother.onHeuristicPoint(heuristic)`; cache và trả về.
4. Ngoài nhịp model (chưa tới 250 ms) → trả `lastSmoothed`.

`heuristicPoint(image)` = nguyên thuật toán lưới 32×18 + trọng tâm tương phản + xoay theo `rotationDegrees` đang có, **bỏ phần EMA** (EMA đã chuyển vào `SubjectSmoother`) và trả `null` khi `wSum <= 0f`. Xoá các field `smoothX/smoothY/initialized` cũ.

`close()`: `detector?.close()`, `detector = null`.

- [ ] **Step 4: Nối `MainActivity` + build**

`MainActivity`: `private val tracker = SubjectTracker(this)`; trong `onDestroy`, gọi `tracker.close()` trước `analysisExecutor.shutdown()`. Build:
Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`, 67 test xanh (56 + 5 + 6). Nếu `toBitmap()` không resolve, dùng `ImageProxy.toBitmap()` của `camera-core` qua import tường minh; không tự viết YUV decoder.

- [ ] **Step 5: Ghi checklist và hợp đồng ghép**

`TESTING.md` thêm D13 ("chấm vàng bám người khi đi ngang khung, không nhảy về nền"), D14 ("chấm vàng vẫn bám khi model miss 1–2 khung"). `INTEGRATION.md` mục 4: thêm một dòng "`SubjectTracker` giờ cần `Context` (`SubjectTracker(activity)`) và phải gọi `close()` trong `onDestroy`; khung YUV và RGBA đều đọc được."

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/gia/aicomposer/vision/SubjectDetector.kt app/src/main/java/com/gia/aicomposer/SubjectTracker.kt app/src/main/java/com/gia/aicomposer/util/ImageConversions.kt app/src/main/java/com/gia/aicomposer/MainActivity.kt INTEGRATION.md TESTING.md
git commit -m "feat: locate the subject with EfficientDet instead of a brightness heuristic"
```

---

### Task 4: Kiểm chứng và hiệu chỉnh trên máy thật

**Files:**
- Modify: `TESTING.md` (điền D13/D14)
- Create: `docs/reports/2026-11-18-subject-detection-run.md`

**Interfaces:**
- Consumes: APK debug `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.
- Produces: bảng kết quả D13/D14 + số đo thời gian `detect` trung bình.

- [ ] **Step 1: Đo thời gian detect**

Thêm tạm `Log.i("SubjectPerf", "detect=${durationMs}ms candidates=${list.size}")` quanh lời gọi `detector.detect` trong `SubjectTracker` (xoá sau khi đo). Trên máy thật:
Run: `adb logcat -s SubjectPerf`
Expected: có số trung bình; ghi vào báo cáo. Nếu trung bình > 120 ms thì tăng `MODEL_INTERVAL_MS` lên 400 ms và ghi lý do — **không** đổi ngưỡng tự chụp.

- [ ] **Step 2: Chạy D13/D14**

Làm theo `TESTING.md` D13, D14 trên máy thật.
Expected: cả hai Đạt (chấm vàng bám người; không nhảy khi miss 1–2 khung). Nếu ❌: ghi guidance + 20 dòng logcat cuối, mở task sửa riêng.

- [ ] **Step 3: Ghi báo cáo**

`docs/reports/2026-11-18-subject-detection-run.md`: thiết bị, Android version, bảng D13/D14, thời gian detect trước/sau, ghi chú "chưa kiểm được" cho mục nào không chạy.

- [ ] **Step 4: Commit**

```bash
git add TESTING.md docs/reports/2026-11-18-subject-detection-run.md
git commit -m "test: record subject detection behaviour on a device"
```

> **Ghi chú môi trường:** VM cloud không có emulator/KVM nên Task 4 **không chạy được ở đây**; khi thực thi trong workspace này, dừng ở Task 3 và ghi rõ Task 4 là blocked thay vì đánh dấu xong.

---

## Thứ tự thực thi & phụ thuộc

- Task 1 và Task 2 độc lập nhau, làm trước vì test JVM chạy được ngay.
- Task 3 phụ thuộc cả hai (dùng `SubjectCandidate`, `SubjectPicker`, `SubjectSmoother`).
- Task 4 chỉ chạy trên thiết bị; không chặn Task 1–3.

## Self-review notes

- **Spec coverage:** mục 1 của danh sách nói "thay heuristic bằng model thật" và "plan đã dự trù hook `analyze()`" → Task 3 giữ nguyên `analyze(ImageProxy)` nên hook không đổi; model thật dùng EfficientDet đã bundle (không thêm dependency/asset).
- **Ngoài phạm vi:** 9 ý tưởng còn lại là subsystem độc lập (video, preset, release, UI test, pin, thiết bị, i18n, cloud) — mỗi cái cần plan riêng; plan này không đụng tới.
- **Type consistency:** `SubjectCandidate`/`List<SubjectCandidate>` dùng chung ở Task 1 và Task 3; `wantsFallback` chỉ đọc, được Task 3 đọc đúng tên; `onModelPoint`/`onHeuristicPoint`/`reset` khớp giữa hai task.
- **Proportion:** phần lớn nội dung là test và quyết định (nhãn ưu tiên, torso offset, nhịp 250 ms); không chép thân hàm heuristic cũ.
