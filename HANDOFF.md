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
- Build debug APK thành công sau khi refactor (`BUILD SUCCESSFUL`).
- Lọc ABI trong `app/build.gradle.kts` (`arm64-v8a` + `x86_64`): APK 110.974.312 → **66.028.099 byte**
  (−44,9 MB, −40,5 %). Phần phình to là native lib: 4 ABI ≈ 77 MB thô (x86 31,1 / arm64 22,4 /
  armeabi-v7a 13,7 / x86_64 9,8), cộng 19,6 MB model trong assets và ~20,6 MB dex.
  **Lưu ý:** AAR `tasks-vision:0.10.14` không có `.so` cho `x86_64` → trên emulator x86_64 MediaPipe
  không khởi tạo được (code bắt `Throwable`, fallback: thư viện dáng mẫu + ML Kit Face/saliency).
  Emulator x86 (32-bit) cần thêm `"x86"` vào `abiFilters` (+~31 MB).
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
- Đã fetch `main` của `binhvugia1997-ui/App-chulj-nh-vip-pro`: repo chỉ có `README.md` (1 commit),
  chưa có `MainActivity.kt` / `SubjectTracker.kt` / `GalleryActivity.kt`. Muốn diff theo số dòng thì
  cần user push code hoặc dán `MainActivity.kt` vào chat.

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
- `INTEGRATION.md` — hướng dẫn ghép; `app/build/outputs/apk/debug/app-debug.apk` — APK demo.

## Bước tiếp theo
1. Làm theo `INTEGRATION.md` mục 1 → 5 trên máy Windows.
2. Build + test emulator (UI/overlay/tự chụp nhờ fallback 600 ms).
3. Test máy thật: rung/ổn định, khóa khung khi xoay, tự chụp khi căn chuẩn.
4. Nếu có lỗi build khi ghép: sửa trong workspace rồi build lại `./gradlew :app:assembleDebug`.
