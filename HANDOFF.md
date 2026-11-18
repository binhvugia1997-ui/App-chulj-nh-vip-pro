# HANDOFF — AICameraComposer: gợi ý dáng + tự căn khung/tự chụp

## Mục tiêu
Nâng cấp app Kotlin + CameraX (`com.gia.aicomposer`) với (A) gợi ý dáng theo logic pose-suggester,
(B) pipeline tự căn khung + tự chụp theo logic LiveCapture, (C) tích hợp trên một overlay preview —
**không viết lại MainActivity của app thật**, chỉ nối dây tối thiểu.

## Cách giao code hiện tại (đã đổi)
- Toàn bộ tính năng mới gói trong `com.gia.aicomposer.integration`:
  - `AiCameraAssistant.kt` — điểm nối duy nhất (overlay, cảnh, khung 3:4, cảm biến, pipeline 9 bước, chụp).
  - `AiOverlayHost.kt` — tự bọc `PreviewView` trong `FrameLayout` nếu layout chưa có.
  - `AiSettingsDialog.kt` — hộp thoại cài đặt dựng bằng code (không cần viewBinding/theme Material).
- `MainActivity.kt` trong workspace giờ chỉ là **bản mẫu nối dây tối thiểu** (~190 dòng), không còn chứa logic AI.
- Tài liệu ghép: `INTEGRATION.md` — danh sách file chép, diff gradle/manifest, 7 bước diff cho MainActivity,
  bảng API, checklist test, ghi chú xung đột.
- `TESTING.md` — checklist 12 mục emulator (E1–E12: giao diện, nút gợi ý dáng, overlay hình que,
  bật/tắt pipeline, pause-resume, watchdog) + 11 mục máy thật (D1–D11: rung/ổn định, khóa khung theo máy,
  hút tâm, tự chụp khi căn chuẩn, huỷ đếm, độ trễ, ảnh cuối), mỗi mục có cột đạt/không đạt.

## Đã xong
- **Rà soát/tối giản code (giữ nguyên ngưỡng 0.08 / 600ms, 30° / 0.55, 15dp / 1s):**
  - Bỏ dữ liệu không ai đọc: `SceneState.timestampMs`, `freeRegions: List<FreeRegion>` (luôn đúng 1 phần tử)
    → `freeRegion: RectF`; `MotionSnapshot` 10 field → 3 (`isStable`, `shakeToken`, `hasSensors`);
    `FramingResult` bỏ `source`/`score`/`confidence`; `PoseSuggestion` bỏ `reasons` (không UI nào dùng,
    mỗi lần bấm còn đỡ phải dựng 30 chuỗi); `DevicePosture.rollDeg`; `PipelineState.step`.
  - Bỏ API/nhánh chết: `PoseTemplate.frameRect`, `PoseLibrary.byId`, `SceneAnalyzer.isReady`,
    `FrameLockTracker.isLocked`, `AiCameraAssistant.currentPose`, `PhotoStore.viewIntent`,
    `PipelineUi.frameHeightFraction/frameNormAspect` (hằng số, set một lần), `start(enableAutoCapture)`
    (luôn `true`), hằng `LEGACY_*` trùng nghĩa, `BUSY_CLUTTER_RATIO` lặp lại trong `PoseScorer`
    (dùng `scene.isFrontBusy`), nhánh `pool.isEmpty()` không thể xảy ra.
  - Gộp lặp: `resetAlignment()` cho 4 chỗ trong `CapturePipeline`; `syncAutoCapture()` cho 3 chỗ trong
    `AiCameraAssistant`; `FramingAnalyzer.finish(request, faces)` thay callback 7 tham số; overlay vẽ
    hình que qua một map toạ độ thay vì tính 3 lần; `GalleryActivity` đọc ảnh qua `ContentResolver`
    (bỏ nhánh `file://` thủ công).
  - Bớt việc mỗi frame: `onGuidance` chỉ gọi khi câu hướng dẫn đổi (trước đây ~30 lần/giây); ảnh MediaPipe
    của Pose Landmarker được `close()` sau khi detect.
  - Kết quả: 4247 → 4069 dòng Kotlin; APK vẫn 66.033.933 byte, đúng 2 ABI, đủ 2 model (stored).
    (Build sạch lại cả 2 trường hợp: không lọc ABI 110.943.003 → có lọc 66.033.933, vẫn −40,5 %.)
- Build debug APK thành công sau khi refactor (`BUILD SUCCESSFUL`).
- Lọc ABI trong `app/build.gradle.kts` (`arm64-v8a` + `x86_64`): build lại và đo trong workspace này,
  APK **110.943.003 → 66.033.933 byte (105,8 → 63,0 MiB; −42,8 MiB, −40,5 %)**. Phần phình to là
  native lib: 4 ABI = 73,5 MiB thô (x86 29,7 / arm64-v8a 21,4 / armeabi-v7a 13,1 / x86_64 9,4),
  cộng 22,8 MiB assets (2 model + 4,6 MiB model nhúng của ML Kit) và 20,7 MiB dex (12 file, debug không minify).
  Sau khi lọc, `aapt dump badging` chỉ còn `arm64-v8a` + `x86_64`; 2 model vẫn **stored, SHA-256 khớp**
  bản trước khi lọc. Số chi tiết + cách đo: `INTEGRATION.md` mục 8.
- **Sửa luồng lưu/xem ảnh**: pipeline AI trước đây ghi vào `getExternalFilesDir(...)/AICameraComposer`
  nên `GalleryActivity` (chỉ đọc MediaStore) không thấy ảnh tự chụp. Thêm `util/PhotoStore.kt` dùng
  chung: MediaStore `Pictures/AICameraComposer` + `IS_PENDING`/`publish()` trên Android 10+, fallback
  thư mục app trên Android 8-9. `AiCameraAssistant.capturePhoto()` và `MainActivity.takePhoto()` giờ
  cùng một album, cùng tiền tố `AIC_`.
- `GalleryActivity` bản mới: xin `READ_EXTERNAL_STORAGE` trên API 26-28, đọc MediaStore **và** thư mục
  app, nạp lại ở `onResume`, thumbnail có lấy mẫu, xem full ≤ 2048 px có xoay EXIF, trạng thái rỗng.
- `SubjectTracker` đọc được cả Y plane (YUV) và RGBA_8888 (use case của assistant) — nhánh YUV giữ
  nguyên số liệu cũ, nhờ vậy chấm vàng/heuristic tự chụp không đổi khi chạy chung `ImageAnalysis`.
- `AiCameraAssistant.legacyFrameTap`: hook cho analyzer cũ chạy trên cùng use case (CameraX chỉ cho
  bind một `ImageAnalysis`).
  **Cập nhật 2026-11-18:** đã nâng `tasks-vision` 0.10.14 → **1.2.3**. Native x86_64 của bản mới
  nằm ở dependency `com.google.mediapipe:tasks-core:1.2.3` (AAR `tasks-vision` 1.2.3 không còn `jni/`),
  nên MediaPipe **nạp được trên emulator x86_64**. Giá phải trả: APK debug +21,1 MB, trong đó +20,2 MB
  là riêng `lib/x86_64` (arm64 chỉ +2,9 MB). Chi tiết + cách đo: `docs/reports/2026-11-18-mediapipe-abis.md`.
  Chưa xác nhận runtime 1.2.3 đọc đúng `pose_landmarker_lite.task` / `efficientdet_lite0.tflite`.
- Thư viện 30 dáng viết tay (20 FULL_BODY + 10 HALF_BODY), affordance bắt buộc/khuyến khích.
- Chấm điểm +2 / +1.5 / −1.5 / +1.5 / −4, chọn ngẫu nhiên trong nhóm điểm cao nhất.
- EfficientDet Lite0 → `SceneState`; khung 3:4 qua Face → Pose → saliency → giữa (40/30/20/10).
- Ổn định 60 Hz giữ ngưỡng cũ 0.08 gyro / 600 ms; rung mạnh reset khóa.
- Khóa khung + trôi ±30° × 0.55 × cạnh màn hình × khoảng cách 0.6–1.4 × zoom, snap 5/25 dp × 0.9.
- Tự chụp dung sai 15 dp, giữ 1 s, đếm thêm 1 s (đổi trong Cài đặt), lệch thì huỷ, xong tự tắt.
- Overlay hình que + khung 3:4 + vòng tâm, chỉ trên preview.
- Tài nguyên tách riêng để chép: `res/values/ai_strings.xml`, `res/values/ai_colors.xml`.
- Sửa 3 chỗ có thể làm pipeline kẹt (build lại `BUILD SUCCESSFUL`):
  - `FramingAnalyzer`: cờ `busy` có watchdog 2 s + số thế hệ request; ML Kit không callback vẫn được nhả
    cờ, kết quả về muộn bị bỏ qua.
  - `FrameLockTracker`: công thức bù dự đoán viết lại theo `pxPerDegree` / `velocityPxPerMs`, hằng `90 ms`
    đổi tên thành `PREDICTION_LOOKAHEAD_MS` (sàn `MIN_LOOKAHEAD_MS = 16`), hành vi số học giữ nguyên.
  - `CapturePipeline.pause()/resume()` + `AiCameraAssistant.stop()/start()`: `onStop` chỉ tạm dừng,
    `onStart` (hoặc `onCameraOpened`) chạy tiếp ở bước "chờ ổn định", không reset về `WAITING`.

## Còn dở
- Chưa chạy trên emulator/điện thoại thật (VM cloud không có emulator/KVM) — chưa xác nhận được
  hành vi watchdog/khoảng dự đoán/pause-resume bằng runtime thật.
- Chưa đối chiếu được với `D:\AICameraComposer`: diff trong `INTEGRATION.md` dùng mốc, chưa phải diff
  chính xác trên file thật của bạn.
- `main` của `binhvugia1997-ui/App-chulj-nh-vip-pro` đã có app v0.1 + module AI (commit `c9c7cc8`),
  nên `INTEGRATION.md` giờ là diff theo **đúng nội dung file trong repo** (7 mốc cho `MainActivity`,
  1 chỗ cho `SubjectTracker`, `CompositionOverlayView` không cần sửa), không còn là diff theo mốc chung.

## Đã chốt
- Không copy code từ 2 repo; chỉ viết lại logic.
- Overlay là View phủ trên PreviewView; ImageCapture là use case riêng nên overlay không vào ảnh.
- Một ImageAnalysis duy nhất + KEEP_ONLY_LATEST (throttle cảnh 700 ms, khung 280 ms).
- Fallback offline: `SceneState.empty()` + thư viện dáng mẫu.
- Không cảm biến (emulator): coi như yên sau 600 ms.
- Không dùng viewBinding trong code integration; dialog cài đặt dựng bằng code.

## File chính
- `app/src/main/java/com/gia/aicomposer/integration/` — 3 file nối dây.
- `pose/`, `model/`, `vision/`, `pipeline/`, `overlay/`, `settings/`, `util/` — module thuần, không phụ thuộc activity.
- `app/src/main/assets/` — 2 model; `res/values/ai_strings.xml`, `ai_colors.xml`.
- `app/src/main/java/com/gia/aicomposer/util/PhotoStore.kt` — đường lưu ảnh dùng chung cho chụp tay + pipeline.
- `app/src/main/java/com/gia/aicomposer/GalleryActivity.kt` — thư viện đọc MediaStore + thư mục app.
- `INTEGRATION.md` — hướng dẫn ghép; `app/build/outputs/apk/debug/app-debug.apk` — APK demo.

## Bước tiếp theo
1. Làm theo `INTEGRATION.md` mục 1 → 5 trên máy Windows.
2. Build + test emulator (UI/overlay/tự chụp nhờ fallback 600 ms).
3. Test máy thật: rung/ổn định, khóa khung khi xoay, tự chụp khi căn chuẩn.
4. Nếu có lỗi build khi ghép: sửa trong workspace rồi build lại `./gradlew :app:assembleDebug`.
