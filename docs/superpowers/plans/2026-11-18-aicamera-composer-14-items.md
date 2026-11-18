# AICameraComposer — 14 việc tiếp theo (implementation plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Đóng 14 việc tiếp theo của AICameraComposer — kiểm chứng runtime, gỡ chặn emulator, CI, giao code bằng patch, và các cải tiến chất lượng ảnh/đóng gói — mỗi task để lại phần mềm chạy được và test được.

**Architecture:** App Kotlin + CameraX một module `:app`; toàn bộ tính năng AI nằm trong `com.gia.aicomposer.integration` (một điểm nối `AiCameraAssistant`), các module `pose/`, `vision/`, `pipeline/`, `overlay/`, `util/` là thuần Kotlin và đã có test JVM (Robolectric cho chỗ cần `android.graphics`). Plan này thêm 3 lớp logic thuần mới (`FocusGate`, `ShutterCue`, `DetectionGate`) để phần quyết định của các tính năng mới test được bằng JVM, còn phần nối dây CameraX/UI nằm gọn trong assistant.

**Tech Stack:** Kotlin 1.9.24 (nâng 2.0.21 ở Task 8), AGP 8.5.2 (nâng 8.9.1 ở Task 8), CameraX 1.3.4 (nâng 1.4.2 ở Task 13), MediaPipe tasks-vision 0.10.14, ML Kit face-detection 16.1.6, JUnit4 + Robolectric 4.13, GitHub Actions.

**Spec:** danh sách xếp hạng 14 mục trong hội thoại ngày 2026-11-18 + `HANDOFF.md` (ngưỡng đã chốt) + `INTEGRATION.md` (hợp đồng ghép vào repo thật) + `TESTING.md` (checklist E/D/G).

## Global Constraints

- `minSdk = 26`, `applicationId = com.gia.aicomposer`, chỉ camera sau ở v0.1 (front camera thêm ở Task 7).
- Ngưỡng đã chốt, không được đổi giá trị: `FrameLockTracker.MAX_DRIFT_ANGLE_DEG = 30f`, `DRIFT_GAIN = 0.55f`, `SNAP_DEAD_ZONE_DP = 5f`, `SNAP_FADE_END_DP = 25f`, `SNAP_STRENGTH = 0.9f`; `CapturePipeline.ALIGN_TOLERANCE_DP = 15f`, `ALIGN_HOLD_MS = 1_000L`, `MOTION_FALLBACK_MS = 600L`; `StabilityMonitor.GYRO_STILL_STD = 0.08f`, `ACCEL_STILL_STD = 0.12f`, `EVALUATION_INTERVAL_MS = 50L`, `REQUIRED_STILL_EVALUATIONS = 10` (thực tế 500 ms — đã ghi rõ trong `AgreedThresholdsTest`).
- Mọi thay đổi phải giữ `./gradlew :app:testDebugUnitTest` xanh (47 test hiện có) và `./gradlew :app:assembleDebug` thành công.
- Chỉ thêm string tiếng Việt vào `res/values/ai_strings.xml`; không hard-code chữ trong view mới.
- Không copy code từ repo khác; không đổi `namespace`, tên class công khai đã có, hay chữ ký hàm mà `INTEGRATION.md` đang hướng dẫn host gọi.
- Môi trường build trong workspace này (không tự tồn tại sau archive):
  `export JAVA_HOME=$HOME/toolchain/jdk; export ANDROID_HOME=$HOME/toolchain/sdk; echo "sdk.dir=$ANDROID_HOME" > local.properties`
- Commit sau mỗi task (conventional commit, tiếng Anh, ngắn); không push trừ khi người dùng yêu cầu.

## Review Focus

1. **Pipeline lưu ảnh nhưng thư viện chưa thấy ngay** (IS_PENDING không được xoá, hoặc ảnh rơi vào thư mục app trên API 29+): người dùng bấm "Tự chụp", thấy "Xong!" rồi mở Thư viện và không thấy ảnh. Test: Task 1 (G2), Task 9 (`PhotoStore.isAppPicture`).
2. **Tự chụp khi khung chưa lấy nét**: khung đúng tâm nhưng ảnh nhoè vì AF chưa hội tụ. Test: Task 5 (`FocusGateTest`).
3. **Rời app đúng lúc đang chụp** (Home/khoá màn hình giữa `CAPTURING`): ảnh mồ côi hoặc switch tự chụp còn ON khi quay lại. Test: Task 1 (D9), Task 11 (review dialog chỉ hiện khi `onCaptureSaved`).
4. **Máy không có gyro/gia tốc**: pipeline phải đi tiếp nhờ mốc 600 ms chứ không kẹt ở "Giữ máy yên". Test: Task 1 (D11) + `AgreedThresholdsTest.motionStabilityIsGyro008OverTenEvaluations`.
5. **targetSdk 36 + edge-to-edge**: thanh điều hướng/status che mất câu guidance và nút Chụp. Test: Task 8 (kiểm bằng mắt trên máy + `aapt dump badging`).

---

### Task 1: Kiểm chứng runtime trên máy thật (mục 1)

**Files:**
- Modify: `TESTING.md` (điền kết quả, không đổi nội dung mục)
- Create: `docs/reports/2026-11-18-device-run.md`

**Interfaces:**
- Consumes: APK debug hiện tại (`app/build/outputs/apk/debug/app-debug.apk`), checklist E1–E12 / D1–D11 / G1–G5 trong `TESTING.md`.
- Produces: báo cáo ghi rõ mục ❌ + `adb logcat` + bước guidance tại thời điểm lỗi; danh sách lỗi được chuyển thành task riêng.

- [ ] **Step 1: Build APK sạch**

Run: `./gradlew :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`, file `app/build/outputs/apk/debug/app-debug.apk` tồn tại.

- [ ] **Step 2: Cài và chạy checklist mục 1 (emulator)**

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk && adb logcat -c && adb logcat -s AiCameraAssistant FramingAnalyzer`
Expected: E1–E12 có kết quả Đạt/Không đạt; emulator `x86_64` ghi chú "MediaPipe unavailable" là **đúng thiết kế**, không tính ❌.

- [ ] **Step 3: Chạy checklist mục 2 + 3 (máy thật)**

Run: `adb shell content query --uri content://media/external/images/media --projection _display_name:relative_path --where "_display_name LIKE 'AIC_%'"`
Expected: D1–D11, G1–G5 có kết quả; G2 thấy ảnh pipeline ngay sau khi quay lại app.

- [ ] **Step 4: Ghi báo cáo**

Ghi vào `docs/reports/2026-11-18-device-run.md`: thiết bị, Android version, bảng mã mục ☑/☐, với mỗi ❌ thêm 3 dòng: guidance đang hiện, 20 dòng logcat cuối, hành vi mong đợi.

- [ ] **Step 5: Commit**

```bash
git add TESTING.md docs/reports/2026-11-18-device-run.md
git commit -m "test: record first device run of the AI pipeline"
```

---

### Task 2: Gỡ chặn MediaPipe trên emulator x86_64 (mục 2)

**Files:**
- Create: `docs/reports/2026-11-18-mediapipe-abis.md`
- Modify: `app/build.gradle.kts` (chỉ khi bước 3 kết luận "có x86_64")
- Modify: `HANDOFF.md` (mục "Lưu ý x86_64")

**Interfaces:**
- Consumes: `com.google.mediapipe:tasks-vision:0.10.14` (hiện tại), AAR trên Google Maven.
- Produces: kết luận dạng bảng "version → jni ABIs"; hoặc bump version, hoặc ghi rõ "chấp nhận test pose trên máy thật".

- [x] **Step 1: Liệt kê ABI của bản đang dùng và bản mới**

Run:
```bash
curl -s -o /tmp/tv-old.aar https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-vision/0.10.14/tasks-vision-0.10.14.aar
curl -s -o /tmp/tv-new.aar https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-vision/1.2.3/tasks-vision-1.2.3.aar
for f in /tmp/tv-old.aar /tmp/tv-new.aar; do echo "== $f"; unzip -l "$f" | grep -o 'jni/[^/]*/[^ ]*\.so' | sort -u; done
```
Expected: in ra danh sách `.so` theo ABI cho từng bản; ghi vào `docs/reports/2026-11-18-mediapipe-abis.md`.

- [x] **Step 2: Quyết định theo bảng**

Nếu bản 1.2.3 có `jni/x86_64/libmediapipe_tasks_vision_jni.so` → làm Step 3. Nếu không → ghi "blocked: không có x86_64" + phương án thay thế đã cân nhắc (`com.google.mlkit:pose-detection:17.0.0`, hoặc emulator `x86` 32-bit) cùng lý do không chọn, rồi nhảy tới Step 5.

- [x] **Step 3: Bump version và build**

Sửa `app/build.gradle.kts`: `implementation("com.google.mediapipe:tasks-vision:1.2.3")`.
Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`; nếu lỗi API (class/constructor đổi tên) thì sửa tại `pose/` hoặc `vision/` cho khớp, không đổi ngưỡng.

- [x] **Step 4: Xác nhận APK có x86_64**

Run: `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep 'lib/x86_64' | wc -l`
Expected: ≥ 2 (trước đây chỉ 1: `libface_detector_v2_jni.so`); ghi số này + dung lượng APK mới vào báo cáo.

- [x] **Step 5: Commit**

```bash
git add docs/reports/2026-11-18-mediapipe-abis.md HANDOFF.md app/build.gradle.kts
git commit -m "build: investigate MediaPipe x86_64 support for emulator testing"
```

---

### Task 3: CI build + test trên GitHub Actions (mục 4)

**Files:**
- Create: `.github/workflows/android.yml`

**Interfaces:**
- Consumes: `./gradlew :app:testDebugUnitTest`, `./gradlew :app:assembleDebug`, JDK 17, Android SDK platform 34 (36 sau Task 8).
- Produces: workflow tên `Android CI` chạy khi `push`/`pull_request`; artifact `app-debug-apk`.

- [x] **Step 1: Viết workflow**

`.github/workflows/android.yml`:
```yaml
name: Android CI
on:
  push:
    branches: [main]
  pull_request:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: android-actions/setup-android@v3
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: app-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: unit-test-report
          path: app/build/reports/tests/testDebugUnitTest
```

- [x] **Step 2: Kiểm YAML hợp lệ**

Run: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/android.yml')); print('ok')"`
Expected: `ok`. Nếu `ModuleNotFoundError: yaml` → `pip3 install --user pyyaml` rồi chạy lại.

- [x] **Step 3: Chạy đúng hai lệnh mà CI sẽ chạy**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL` (đã có 47 test xanh).

- [x] **Step 4: Commit**

```bash
git add .github/workflows/android.yml
git commit -m "ci: build debug apk and run unit tests on GitHub Actions"
```

---

### Task 4: Giao code bằng patch áp được thay vì 7 mốc văn xuôi (mục 5)

**Files:**
- Create: `docs/merge/ai-module.patch`
- Modify: `INTEGRATION.md` (thay mục "7 mốc" bằng hướng dẫn `git apply` + giữ bảng mốc làm tham chiếu)

**Interfaces:**
- Consumes: baseline repo `binhvugia1997-ui/App-chulj-nh-vip-pro` commit `c9c7cc8`; bảng file trong `INTEGRATION.md` mục 1.
- Produces: một file `.patch` áp được bằng `git apply` lên `c9c7cc8`.

- [x] **Step 1: Tạo worktree nền ở đúng commit**

Run:
```bash
git fetch origin main
git worktree add /tmp/merge-base c9c7cc8
```
Expected: `/tmp/merge-base` chứa đúng cây của `c9c7cc8`. Nếu fetch không kéo được đúng commit (GitHub thường không cho fetch theo SHA), dùng `git worktree add /tmp/merge-base origin/main` và ghi lại SHA thực tế vào patch header.

- [x] **Step 2: Chép file theo bảng của INTEGRATION.md vào worktree nền**

Chép nguyên thư mục/file trong bảng "chép/ghi đè" (`pose/`, `vision/`, `pipeline/`, `overlay/`, `settings/`, `util/`, `integration/`, `GalleryActivity.kt`, `util/PhotoStore.kt`, `SubjectTracker.kt`, `res/values/ai_*.xml`, `res/layout/activity_gallery.xml`, `item_photo.xml`, `assets/*.tflite|task`, `app/src/test/`), không chép `MainActivity.kt`.

- [x] **Step 3: Sinh patch**

Run:
```bash
git -C /tmp/merge-base add -A
git -C /tmp/merge-base diff --cached --binary <base-sha> > docs/merge/ai-module.patch
```
(`<base-sha>` = `c9c7cc8` hoặc SHA ghi ở Step 1.)
Expected: file patch > 100 KB, có các dòng `diff --git` cho mọi file đã chép và **không** có `MainActivity.kt`.

- [x] **Step 4: Kiểm patch áp được trên cây sạch**

Run:
```bash
git worktree add /tmp/merge-check <base-sha>
git -C /tmp/merge-check apply --check "$PWD/docs/merge/ai-module.patch"
```
Expected: không in gì (exit 0). Nếu báo lỗi thì sửa patch (thường do file thiếu trong bảng), không sửa repo thật.

- [x] **Step 5: Commit**

```bash
git add docs/merge/ai-module.patch INTEGRATION.md
git commit -m "docs: ship the integration as an apply-able patch"
```

---

### Task 5: Khoá nét/phơi sáng trước khi tự chụp (mục 6)

**Files:**
- Create: `app/src/main/java/com/gia/aicomposer/pipeline/FocusGate.kt`
- Create: `app/src/test/java/com/gia/aicomposer/pipeline/FocusGateTest.kt`
- Modify: `app/src/main/java/com/gia/aicomposer/pipeline/CapturePipeline.kt` (thêm điều kiện `focusReady` vào `updateAlignment`)
- Modify: `app/src/main/java/com/gia/aicomposer/integration/AiCameraAssistant.kt` (tap-to-focus + đọc AF/AE state)
- Modify: `TESTING.md` (thêm D12)

**Interfaces:**
- Consumes: `CapturePipeline(settings, tracker, orientation, zoomProvider, onUi, onCaptureRequested, onFinished)`; `PipelineUi.alignmentOffsetDp`.
- Produces:
  - `class FocusGate(private val settleWindowMs: Long = 400L) { fun onCaptureResult(afConverged: Boolean, aeConverged: Boolean, nowMs: Long); fun isReady(): Boolean; fun reset() }`
  - `CapturePipeline(..., focusReady: () -> Boolean = { true })`
  - `AiCameraAssistant.focusAt(normX: Float, normY: Float)`

- [x] **Step 1: Viết test đỏ cho `FocusGate`** (xong 2026-11-18)

`FocusGateTest.kt`:
```kotlin
@Test fun notReadyBeforeAnyResult() { assertFalse(FocusGate().isReady()) }
@Test fun becomesReadyOnlyAfterWindowOfConvergedResults() {
    val gate = FocusGate(settleWindowMs = 400)
    gate.onCaptureResult(true, true, 0)
    assertFalse(gate.isReady())
    gate.onCaptureResult(true, true, 399)
    assertFalse(gate.isReady())
    gate.onCaptureResult(true, true, 400)
    assertTrue(gate.isReady())
}
@Test fun divergenceResetsTheWindow() {
    val gate = FocusGate(settleWindowMs = 400)
    gate.onCaptureResult(true, true, 0)
    gate.onCaptureResult(false, true, 200)
    gate.onCaptureResult(true, true, 300)
    assertFalse(gate.isReady())
    gate.onCaptureResult(true, true, 700)
    assertTrue(gate.isReady())
}
```
(JUnit4 + Robolectric như các test khác.)

- [x] **Step 2: Chạy test để thấy đỏ** (xong 2026-11-18)

Run: `./gradlew :app:testDebugUnitTest --tests '*FocusGateTest*' --no-daemon`
Expected: FAIL `Unresolved reference: FocusGate`.
Kết quả: `compileDebugUnitTestKotlin FAILED` với 5 lỗi `Unresolved reference: FocusGate` (exit 1).
Ghi chú: test viết thêm 2 case `resetMakesItUnreadyAgain` và `exposureDivergenceAlsoResetsTheWindow`
→ 5 test thay vì 3.

- [x] **Step 3: Viết `FocusGate`** (xong 2026-11-18)

`pipeline/FocusGate.kt`, logic: giữ `convergedSinceMs: Long?`; mỗi lần gọi, nếu `afConverged && aeConverged` thì set nếu null, ngược lại set `null`; `isReady()` = `convergedSinceMs != null && nowMs - convergedSinceMs >= settleWindowMs` (lưu `nowMs` lần gọi cuối vào field `lastMs`).

- [x] **Step 4: Nối vào pipeline và assistant** (xong 2026-11-18)

- `CapturePipeline`: thêm tham số `focusReady: () -> Boolean = { true }`; trong `updateAlignment`, đổi điều kiện thành `if (alignmentOffsetDp <= ALIGN_TOLERANCE_DP && focusReady())`, nhánh `else` giữ nguyên việc reset `holdMs/countdownMs`.
- `AiCameraAssistant`: giữ `ImageCapture.Builder` trong một field tạm khi dựng use case (hoặc nhận `ImageCapture` đã build từ host — khi đó host phải gắn callback), rồi `Camera2Interop.Extender(builder).setSessionCaptureCallback(cb)`; `cb` đọc `CaptureResult.CONTROL_AF_STATE` (`PASSIVE_FOCUSED`/`FOCUSED_LOCKED` → converged) và `CONTROL_AE_STATE` (`CONVERGED`/`LOCKED` → converged) rồi gọi `focusGate.onCaptureResult(..., SystemClock.elapsedRealtime())` trên main thread. Truyền `focusReady = { focusGate.isReady() }` vào `CapturePipeline`; `focusAt()` gọi `cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(...).build())` và `focusGate.reset()` để lần căn sau phải chờ AF/AE lại từ đầu.
- `MainActivity` thật (theo `INTEGRATION.md`): thêm `previewView.setOnTouchListener` → `assistant.focusAt(x / width, y / height)`.

- [x] **Step 5: Chạy test + build** (xong 2026-11-18)

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`
Expected: toàn bộ test xanh (47 test cũ + `FocusGateTest`), `BUILD SUCCESSFUL`.
Kết quả: `BUILD SUCCESSFUL`, **52 test, 0 fail** (47 cũ + 5 `FocusGateTest`). 2 APK ABI splits vẫn ra đủ:
arm64 57.563.730 B, x86_64 62.870.665 B (+~50 KB so với trước Task 5).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/gia/aicomposer/pipeline/FocusGate.kt app/src/test/java/com/gia/aicomposer/pipeline/FocusGateTest.kt app/src/main/java/com/gia/aicomposer/pipeline/CapturePipeline.kt app/src/main/java/com/gia/aicomposer/integration/AiCameraAssistant.kt TESTING.md
git commit -m "feat: require converged focus and exposure before auto capture"
```

---

### Task 6: ABI splits để mỗi máy chỉ tải phần của mình (mục 7)

**Files:**
- Modify: `app/build.gradle.kts` (bỏ `ndk.abiFilters`, thêm `splits`)
- Modify: `README.md` (số đo mới), `INTEGRATION.md` mục 8 (số đo mới)

**Interfaces:**
- Consumes: cấu hình `abiFilters = ["arm64-v8a", "x86_64"]` hiện tại; `androidResources.noCompress` giữ nguyên.
- Produces: 2 APK theo ABI, không có APK universal.

- [x] **Step 1: Đổi cấu hình** (xong 2026-11-18)

Trong `app/build.gradle.kts`: xoá `ndk { abiFilters += ... }`, thêm trong `android { }`:
```kotlin
splits {
    abi {
        isEnable = true
        reset()
        include("arm64-v8a", "x86_64")
        isUniversalApk = false
    }
}
```

- [x] **Step 2: Build và liệt kê APK** (xong 2026-11-18)

Run: `./gradlew :app:assembleDebug --no-daemon && ls -l app/build/outputs/apk/debug/`
Expected: `app-arm64-v8a-debug.apk` và `app-x86_64-debug.apk`, không có `app-universal-debug.apk`.
Kết quả: `BUILD SUCCESSFUL`, đúng 2 file, không có `app-universal-debug.apk`.

- [x] **Step 3: Ghi số đo** (xong 2026-11-18)

Run: `for f in app/build/outputs/apk/debug/*.apk; do printf "%s %s\n" "$(stat -c%s "$f")" "$f"; done`
Expected: APK arm64 nhỏ hơn 66.033.933 byte đáng kể (bỏ phần x86_64); ghi cả hai số vào `README.md` và `INTEGRATION.md` mục 8.
Kết quả: `app-arm64-v8a-debug.apk` = **57.513.275 byte** (54,9 MiB, `lib/arm64-v8a` 23,2 MiB);
`app-x86_64-debug.apk` = **62.820.210 byte** (59,9 MiB, `lib/x86_64` 28,3 MiB). So với APK gộp 2 ABI
trước splits (87.167.096 byte): arm64 −29.653.821 byte (−34,0%). Số đo đã ghi vào `README.md`
(mục Build) và `INTEGRATION.md` mục 8; `aapt dump badging` báo mỗi APK đúng 1 ABI.

- [x] **Step 4: Xác nhận model vẫn stored** (xong 2026-11-18)

Run: `unzip -lv app/build/outputs/apk/debug/app-arm64-v8a-debug.apk | grep -E 'assets/.*\.(tflite|task)'`
Expected: cột method = `Stored` cho cả 2 model.
Kết quả: cả 2 APK đều có `efficientdet_lite0.tflite` (13.836.895 byte) và `pose_landmarker_lite.task`
(5.777.746 byte) ở `Stored`.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts README.md INTEGRATION.md
git commit -m "build: split apks per abi to shrink the install"
```

---

### Task 7: Camera trước cho selfie (mục 8)

**Files:**
- Create: `app/src/test/java/com/gia/aicomposer/util/MirrorGeometryTest.kt`
- Modify: `app/src/main/java/com/gia/aicomposer/util/PreviewGeometry.kt`
- Modify: `app/src/main/java/com/gia/aicomposer/overlay/PoseOverlayView.kt`
- Modify: `app/src/main/java/com/gia/aicomposer/CompositionOverlayView.kt`
- Modify: `app/src/main/java/com/gia/aicomposer/integration/AiCameraAssistant.kt`
- Modify: `INTEGRATION.md` (mục 3: thêm bước đổi camera)

**Interfaces:**
- Consumes: `PreviewGeometry.toView/toNormalized`; `SubjectTracker` trả toạ độ chuẩn hoá từ khung phân tích (không bị mirror).
- Produces: `PreviewGeometry.mirrorX(x: Float): Float` = `1f - x`; `PoseOverlayView.mirrorHorizontally: Boolean`; `CompositionOverlayView.mirrorHorizontally: Boolean`; `AiCameraAssistant.setFrontCameraActive(active: Boolean)`.

- [ ] **Step 1: Viết test đỏ**

`MirrorGeometryTest.kt`:
```kotlin
@Test fun mirrorXFlipsAroundCenter() {
    assertEquals(0.2f, PreviewGeometry.mirrorX(0.8f), 1e-4f)
    assertEquals(0.5f, PreviewGeometry.mirrorX(0.5f), 1e-4f)
}
@Test fun mirroredPointRoundTrips() {
    val p = PreviewGeometry.mirrorX(PreviewGeometry.mirrorX(0.37f))
    assertEquals(0.37f, p, 1e-4f)
}
```

- [ ] **Step 2: Chạy test để thấy đỏ**

Run: `./gradlew :app:testDebugUnitTest --tests '*MirrorGeometryTest*' --no-daemon`
Expected: FAIL `Unresolved reference: mirrorX`.

- [ ] **Step 3: Thêm `mirrorX` + cờ trên overlay**

`PreviewGeometry.mirrorX` = `1f - x`. Hai overlay: khi `mirrorHorizontally` bật, đổi `x` của mọi điểm vẽ sang `PreviewGeometry.mirrorX(x)` **sau** khi đã map qua `contentRect` (tức mirror trong toạ độ view quanh `contentRect.centerX()`).

- [ ] **Step 4: Nối vào assistant**

`AiCameraAssistant.setFrontCameraActive(active)` set `mirrorHorizontally` cho cả hai overlay và ghi nhớ cờ; trong `analyzeFrame` khi gọi `legacyFrameTap` không đổi gì (SubjectTracker tự nhiên đọc khung chưa mirror; overlay đã mirror phần vẽ).

- [ ] **Step 5: Chạy test + build**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`
Expected: toàn bộ test xanh (47 test cũ + `FocusGateTest` + `MirrorGeometryTest`), `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/gia/aicomposer/util/PreviewGeometry.kt app/src/test/java/com/gia/aicomposer/util/MirrorGeometryTest.kt app/src/main/java/com/gia/aicomposer/overlay/PoseOverlayView.kt app/src/main/java/com/gia/aicomposer/CompositionOverlayView.kt app/src/main/java/com/gia/aicomposer/integration/AiCameraAssistant.kt INTEGRATION.md
git commit -m "feat: support the front camera with mirrored overlays"
```

---

### Task 8: targetSdk 36 + tooling + edge-to-edge (mục 9)

**Files:**
- Modify: `gradle/wrapper/gradle-wrapper.properties`
- Modify: `build.gradle.kts`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/gia/aicomposer/MainActivity.kt` (edge-to-edge insets)
- Modify: `app/src/main/java/com/gia/aicomposer/GalleryActivity.kt` (edge-to-edge insets)
- Modify: `README.md` (yêu cầu SDK 36)
- Modify: `.github/workflows/android.yml` (Task 3 đã tạo: thêm `platforms;android-36`)

**Interfaces:**
- Consumes: AGP 8.5.2, Gradle 8.7, Kotlin 1.9.24, `compileSdk = 34`, `targetSdk = 34`.
- Produces: `compileSdk = 36`, `targetSdk = 36`, AGP 8.9.1, Gradle 8.11.1, Kotlin 2.0.21.

- [ ] **Step 1: Cài SDK 36 và nâng tooling**

Run:
```bash
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-36" "build-tools;36.0.0"
```
Sửa `gradle-wrapper.properties` → `gradle-8.11.1-bin.zip`; `build.gradle.kts` → AGP `8.9.1`, Kotlin `2.0.21`; `app/build.gradle.kts` → `compileSdk = 36`, `targetSdk = 36`.

- [ ] **Step 2: Build để lộ lỗi biên dịch**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`. Nếu Kotlin 2.0 báo `kotlinOptions` deprecated thì đổi sang `compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }` trong `kotlin { }`.

- [ ] **Step 3: Áp edge-to-edge cho hai activity**

Thêm vào `onCreate` của `MainActivity` và `GalleryActivity`:
```kotlin
WindowCompat.setDecorFitsSystemWindows(window, false)
```
và `ViewCompat.setOnApplyWindowInsetsListener` trên root view: áp `insets.getInsets(WindowInsetsCompat.Type.systemBars())` thành padding. Root của `activity_main.xml` lấy bằng `findViewById<View>(android.R.id.content).getChildAt(0)` nếu layout không có id riêng.

- [ ] **Step 4: Kiểm manifest đầu ra**

Run: `$ANDROID_HOME/build-tools/36.0.0/aapt dump badging app/build/outputs/apk/debug/app-debug.apk | grep -E "sdkVersion|targetSdkVersion"`
Expected: `targetSdkVersion:'36'`, `sdkVersion:'26'`.

- [ ] **Step 5: Cập nhật CI và README**

Trong workflow: thêm bước `run: sdkmanager "platforms;android-36" "build-tools;36.0.0"` sau `setup-android`. Trong `README.md`: đổi yêu cầu `platforms;android-34` → `android-36`, `build-tools;34.0.0` → `36.0.0`.

- [ ] **Step 6: Commit**

```bash
git add gradle/wrapper/gradle-wrapper.properties build.gradle.kts app/build.gradle.kts app/src/main/java/com/gia/aicomposer/MainActivity.kt app/src/main/java/com/gia/aicomposer/GalleryActivity.kt README.md .github/workflows/android.yml
git commit -m "build: target Android 16 and handle edge-to-edge"
```

---

### Task 9: Chia sẻ và xoá ảnh trong Thư viện (mục 10)

**Files:**
- Create: `app/src/main/java/com/gia/aicomposer/util/PhotoShare.kt`
- Create: `app/src/test/java/com/gia/aicomposer/util/PhotoShareTest.kt`
- Modify: `app/src/main/java/com/gia/aicomposer/util/PhotoStore.kt` (thêm `delete`, `isAppPicture`)
- Modify: `app/src/main/java/com/gia/aicomposer/GalleryActivity.kt`
- Modify: `app/src/main/res/layout/activity_gallery.xml`
- Modify: `app/src/main/res/values/ai_strings.xml`

**Interfaces:**
- Consumes: `photos: MutableList<Uri>` trong `GalleryActivity`; `PhotoStore.listAppPictures`.
- Produces:
  - `PhotoShare.intent(uri: Uri): Intent` (ACTION_SEND, `image/jpeg`, `EXTRA_STREAM`, `FLAG_GRANT_READ_URI_PERMISSION`)
  - `PhotoStore.isAppPicture(uri: Uri): Boolean`
  - `PhotoStore.delete(context: Context, uri: Uri): Boolean`

- [ ] **Step 1: Viết test đỏ**

`PhotoShareTest.kt` (Robolectric):
```kotlin
@Test fun shareIntentCarriesReadGrant() {
    val intent = PhotoShare.intent(Uri.parse("content://media/external/images/media/42"))
    assertEquals(Intent.ACTION_SEND, intent.action)
    assertEquals("image/jpeg", intent.type)
    assertEquals("42", intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.lastPathSegment)
    assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
}
@Test fun appDirUrisAreRecognised() {
    assertTrue(PhotoStore.isAppPicture(Uri.parse("file:///data/user/0/com.gia.aicomposer/files/Pictures/AICameraComposer/AIC_1.jpg")))
    assertFalse(PhotoStore.isAppPicture(Uri.parse("content://media/external/images/media/42")))
}
```

- [ ] **Step 2: Chạy test để thấy đỏ**

Run: `./gradlew :app:testDebugUnitTest --tests '*PhotoShareTest*' --no-daemon`
Expected: FAIL `Unresolved reference: PhotoShare`.

- [ ] **Step 3: Viết `PhotoShare` + hai hàm `PhotoStore`**

`PhotoShare.intent` dựng `Intent(ACTION_SEND)` với `type = "image/jpeg"`, `putExtra(EXTRA_STREAM, uri)`, `addFlags(FLAG_GRANT_READ_URI_PERMISSION)`. `PhotoStore.isAppPicture` = scheme `file`. `PhotoStore.delete`: thử `contentResolver.delete(uri, null, null)`; nếu ném hoặc trả 0 và `isAppPicture` thì `File(uri.path!!).delete()`; trả `true` khi đã xoá.

- [ ] **Step 4: Thêm nút vào Thư viện**

`activity_gallery.xml`: thêm `LinearLayout` id `photo_actions` (visibility `gone`) chứa 2 `Button`: `share_button` (`@string/gallery_share`), `delete_button` (`@string/gallery_delete`). `GalleryActivity.showFull` bật `photo_actions`; `full.setOnClickListener` ẩn cả ảnh lẫn `photo_actions`. `share_button` → `startActivity(Intent.createChooser(PhotoShare.intent(current), null))`; `delete_button` → `AlertDialog` xác nhận, xoá xong gọi `loadPhotos()` và ẩn ảnh full.

- [ ] **Step 5: Chạy test + build**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`
Expected: toàn bộ test xanh (47 test cũ + `FocusGateTest` + `MirrorGeometryTest` + `PhotoShareTest`), `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/gia/aicomposer/util/PhotoShare.kt app/src/test/java/com/gia/aicomposer/util/PhotoShareTest.kt app/src/main/java/com/gia/aicomposer/util/PhotoStore.kt app/src/main/java/com/gia/aicomposer/GalleryActivity.kt app/src/main/res/layout/activity_gallery.xml app/src/main/res/values/ai_strings.xml
git commit -m "feat: share and delete photos from the gallery"
```

---

### Task 10: Tín hiệu trước khi chụp — âm thanh + rung (mục 11)

**Files:**
- Create: `app/src/main/java/com/gia/aicomposer/pipeline/ShutterCue.kt`
- Create: `app/src/test/java/com/gia/aicomposer/pipeline/ShutterCueTest.kt`
- Modify: `app/src/main/java/com/gia/aicomposer/integration/AiCameraAssistant.kt`

**Interfaces:**
- Consumes: `PipelineUi(state, countdownRemainingMs, lockedOnTarget, autoCapture)` trong `render()`.
- Produces: `class ShutterCue(private val play: () -> Unit) { fun onUi(ui: PipelineUi) }` — gọi `play()` đúng một lần cho mỗi lần đếm ngược.

- [ ] **Step 1: Viết test đỏ**

`ShutterCueTest.kt`:
```kotlin
@Test fun cuesOnceWhenCountdownStarts() {
    var plays = 0
    val cue = ShutterCue { plays++ }
    cue.onUi(PipelineUi(state = PipelineState.READY, lockedOnTarget = false))
    cue.onUi(PipelineUi(state = PipelineState.READY, lockedOnTarget = true, countdownRemainingMs = 900))
    cue.onUi(PipelineUi(state = PipelineState.READY, lockedOnTarget = true, countdownRemainingMs = 600))
    assertEquals(1, plays)
}
@Test fun rearmsAfterCapture() {
    var plays = 0
    val cue = ShutterCue { plays++ }
    cue.onUi(PipelineUi(state = PipelineState.READY, lockedOnTarget = true, countdownRemainingMs = 900))
    cue.onUi(PipelineUi(state = PipelineState.CAPTURING))
    cue.onUi(PipelineUi(state = PipelineState.READY, lockedOnTarget = true, countdownRemainingMs = 900))
    assertEquals(2, plays)
}
```

- [ ] **Step 2: Chạy test để thấy đỏ**

Run: `./gradlew :app:testDebugUnitTest --tests '*ShutterCueTest*' --no-daemon`
Expected: FAIL `Unresolved reference: ShutterCue`.

- [ ] **Step 3: Viết `ShutterCue`**

Giữ `armed: Boolean`; mỗi `onUi`: khi `state == READY && lockedOnTarget && armed` → `play()`, `armed = false`; khi `state != READY || !lockedOnTarget` → `armed = true`.

- [ ] **Step 4: Phát âm + rung trong assistant**

`AiCameraAssistant`: `private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 60)`; `private val cue = ShutterCue { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120); activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }`; gọi `cue.onUi(ui)` trong `render(ui)`; `release()` gọi `tone.release()`.

- [ ] **Step 5: Chạy test + build**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`
Expected: toàn bộ test xanh (47 test cũ + 4 test của Task 5/7/9/10), `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/gia/aicomposer/pipeline/ShutterCue.kt app/src/test/java/com/gia/aicomposer/pipeline/ShutterCueTest.kt app/src/main/java/com/gia/aicomposer/integration/AiCameraAssistant.kt
git commit -m "feat: beep and vibrate when the countdown starts"
```

---

### Task 11: Onboarding + màn xem lại sau khi chụp (mục 12)

**Files:**
- Create: `app/src/main/java/com/gia/aicomposer/integration/OnboardingDialog.kt`
- Create: `app/src/main/java/com/gia/aicomposer/integration/CaptureReviewDialog.kt`
- Create: `app/src/test/java/com/gia/aicomposer/settings/OnboardingFlagTest.kt`
- Modify: `app/src/main/java/com/gia/aicomposer/settings/AppSettings.kt`
- Modify: `app/src/main/java/com/gia/aicomposer/integration/AiCameraAssistant.kt`
- Modify: `app/src/main/res/values/ai_strings.xml`

**Interfaces:**
- Consumes: `AiCameraAssistant.start()`, `Listener.onCaptureSaved(uri)`.
- Produces:
  - `AppSettings.onboardingSeen: Boolean` (mặc định `false`)
  - `OnboardingDialog.showIfFirstRun(activity: ComponentActivity, settings: AppSettings)`
  - `CaptureReviewDialog.show(activity: ComponentActivity, uri: Uri, onRetake: () -> Unit)`

- [ ] **Step 1: Viết test đỏ**

`OnboardingFlagTest.kt` (Robolectric; `RuntimeEnvironment.getApplication()` để không phải thêm `androidx.test:core`):
```kotlin
@Test fun onboardingIsUnseenByDefaultThenPersists() {
    val context = RuntimeEnvironment.getApplication()
    val settings = AppSettings(context)
    assertFalse(settings.onboardingSeen)
    settings.onboardingSeen = true
    assertTrue(AppSettings(context).onboardingSeen)
}
```

- [ ] **Step 2: Chạy test để thấy đỏ**

Run: `./gradlew :app:testDebugUnitTest --tests '*OnboardingFlagTest*' --no-daemon`
Expected: FAIL `Unresolved reference: onboardingSeen`.

- [ ] **Step 3: Thêm cờ + hai dialog**

`AppSettings`: key `onboarding_seen`, getter/setter như các cờ khác. `OnboardingDialog`: `AlertDialog` 4 dòng giải thích (gợi ý dáng, tự căn khung, tự chụp, thư viện) + nút đóng; đặt `settings.onboardingSeen = true` khi đóng. `CaptureReviewDialog`: `AlertDialog` hiện thumbnail (`ImageView` + `setImageURI`), nút "Xem ảnh" (`Intent.ACTION_VIEW` với uri) và "Chụp lại" (`onRetake`).

- [ ] **Step 4: Nối vào assistant**

`start()`: `OnboardingDialog.showIfFirstRun(activity, settings)`. Trong `capturePhoto().onImageSaved`, sau `listener?.onCaptureSaved(uri)`, gọi `CaptureReviewDialog.show(activity, uri) { pipeline.captureNow() }`.

- [ ] **Step 5: Chạy test + build**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`
Expected: toàn bộ test xanh (47 test cũ + 5 test của Task 5/7/9/10/11), `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/gia/aicomposer/settings/AppSettings.kt app/src/main/java/com/gia/aicomposer/integration/OnboardingDialog.kt app/src/main/java/com/gia/aicomposer/integration/CaptureReviewDialog.kt app/src/test/java/com/gia/aicomposer/settings/OnboardingFlagTest.kt app/src/main/java/com/gia/aicomposer/integration/AiCameraAssistant.kt app/src/main/res/values/ai_strings.xml
git commit -m "feat: add first-run onboarding and a post-capture review sheet"
```

---

### Task 12: Đo và giảm tải nhận diện khi máy rảnh (mục 13)

**Files:**
- Create: `app/src/main/java/com/gia/aicomposer/vision/DetectionGate.kt`
- Create: `app/src/test/java/com/gia/aicomposer/vision/DetectionGateTest.kt`
- Modify: `app/src/main/java/com/gia/aicomposer/integration/AiCameraAssistant.kt`
- Modify: `HANDOFF.md` (ghi số đo)

**Interfaces:**
- Consumes: `AiCameraAssistant.analyzeFrame` (throttle 700 ms cảnh / 280 ms khung), `latestScene: SceneState` (`objects`, `affordances`).
- Produces: `class DetectionGate(private val idleIntervalMs: Long = 3_000L) { fun shouldDetectScene(nowMs: Long, hasSubject: Boolean): Boolean; fun onSceneRequested(nowMs: Long) }`

- [ ] **Step 1: Đo trước khi tối ưu**

Thêm tạm `Log.i("PerfLog", "detect=${durationMs}ms objects=${scene.objects.size}")` trong nhánh `needsScene` của `analyzeFrame` và trong `SceneAnalyzer` callback.
Run: trên máy thật `adb logcat -s PerfLog` trong 60 s, ghi số trung bình + số lần gọi/phút vào `HANDOFF.md`.
Expected: có số cụ thể (ví dụ "~1.4 lần/s, 38 ms/lần"). Không có số thì **dừng task**, báo lại.

- [ ] **Step 2: Viết test đỏ cho `DetectionGate`**

```kotlin
@Test fun detectsImmediatelyWhenSubjectPresent() {
    val gate = DetectionGate(idleIntervalMs = 3_000)
    assertTrue(gate.shouldDetectScene(nowMs = 10_000, hasSubject = true))
}
@Test fun slowsDownAfterIdleAndSpeedsUpOnDemand() {
    val gate = DetectionGate(idleIntervalMs = 3_000)
    gate.onSceneRequested(10_000)
    assertFalse(gate.shouldDetectScene(nowMs = 10_700, hasSubject = false))
    assertTrue(gate.shouldDetectScene(nowMs = 13_000, hasSubject = false))
    assertTrue(gate.shouldDetectScene(nowMs = 13_100, hasSubject = true))
}
```

- [ ] **Step 3: Chạy test để thấy đỏ**

Run: `./gradlew :app:testDebugUnitTest --tests '*DetectionGateTest*' --no-daemon`
Expected: FAIL `Unresolved reference: DetectionGate`.

- [ ] **Step 4: Viết `DetectionGate` và nối vào**

`DetectionGate` giữ `lastDetectMs`; `shouldDetectScene` trả `true` khi `hasSubject`, hoặc khi `nowMs - lastDetectMs >= idleIntervalMs`; `onSceneRequested` cập nhật `lastDetectMs`. Trong `analyzeFrame`: `hasSubject = latestScene.objects.isNotEmpty()`, dùng `gate.shouldDetectScene(now, hasSubject)` thay cho biểu thức `now - lastSceneRequestMs >= SCENE_INTERVAL_MS`, và gọi `gate.onSceneRequested(now)` khi đã submit. `suggestPose()` đặt `gate.onSceneRequested(0)` để lần bấm nút luôn được detect.

- [ ] **Step 5: Đo lại + chạy test + build**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`
Expected: toàn bộ test xanh (47 test cũ + 7 test của Task 5/7/9/10/11/12), `BUILD SUCCESSFUL`; ghi số đo sau tối ưu vào `HANDOFF.md` cạnh số trước.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/gia/aicomposer/vision/DetectionGate.kt app/src/test/java/com/gia/aicomposer/vision/DetectionGateTest.kt app/src/main/java/com/gia/aicomposer/integration/AiCameraAssistant.kt HANDOFF.md
git commit -m "perf: back off scene detection when the frame has no subject"
```

---

### Task 13: Nâng CameraX 1.3.4 → 1.4.2 (mục 14)

**Files:**
- Modify: `app/build.gradle.kts` (`cameraxVersion = "1.4.2"`)
- Modify: `README.md` (nếu có ghi version)
- Modify: `HANDOFF.md` (ghi lần nâng + kết quả test)

**Interfaces:**
- Consumes: `Preview`, `ImageCapture`, `ImageAnalysis` (`OUTPUT_IMAGE_FORMAT_RGBA_8888`), `Camera2Interop` (Task 5), `FocusMeteringAction`.
- Produces: bốn artifact CameraX cùng version 1.4.2, toàn bộ test cũ vẫn xanh.

- [ ] **Step 1: Đổi version và build**

Sửa `val cameraxVersion = "1.4.2"`.
Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`. Nếu lỗi API thì sửa tại chỗ dùng (không hạ version, không thêm suppression).

- [ ] **Step 2: Rà các API đã deprecated trong log**

Run: `./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | grep -c "is deprecated"`
Expected: 0 (trước đây 3 cảnh báo `setTargetAspectRatio`/`setTargetResolution` ở `MainActivity`); nếu còn thì đổi sang `ResolutionSelector` như `AiCameraAssistant.analysisResolutionSelector()`.

- [ ] **Step 3: Kiểm APK vẫn đủ model + ABI**

Run: `unzip -l app/build/outputs/apk/debug/app-arm64-v8a-debug.apk | grep -E 'assets/.*\.(tflite|task)|lib/arm64' | wc -l`
Expected: ≥ 6 (2 model + `.so` MediaPipe/ML Kit); ghi dung lượng APK mới.

- [ ] **Step 4: Chạy lại checklist rút gọn trên máy**

Chạy E7, D5, D8 trong `TESTING.md` (đường tự chụp quan trọng nhất khi đổi CameraX).
Expected: cả 3 Đạt; nếu ❌ thì quay lại 1.3.4 và ghi lý do vào `HANDOFF.md`.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts README.md HANDOFF.md
git commit -m "build: upgrade CameraX to 1.4.2"
```

---

## Thứ tự thực thi & phụ thuộc

- Task 1 nên chạy trước: nó tạo baseline hành vi và có thể sinh task sửa lỗi chen giữa.
- Task 8 (targetSdk 36) và Task 13 (CameraX) **phải** chạy sau Task 3 (CI) để mỗi lần nâng tooling có một chỗ kiểm khách quan.
- Task 5, 10, 11, 12 đều sửa `AiCameraAssistant.kt` → làm tuần tự, không song song.
- Task 6 (splits) đổi tên file APK: mọi lệnh `adb install`/`aapt` trong Task 8 và Task 13 phải dùng đường dẫn mới.

## Self-review notes

- Mục 3 của danh sách (unit test JVM) **đã xong** trong phiên trước: 47 test ở `app/src/test/java/com/gia/aicomposer/**`; plan này chỉ giữ chúng xanh.
- Mục 1 và 2 không sinh code sản phẩm; chúng tạo bằng chứng (báo cáo) và có thể chặn các mục khác — cố ý xếp đầu.
