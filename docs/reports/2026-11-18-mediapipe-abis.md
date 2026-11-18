# MediaPipe trên emulator x86_64 — tra ABI (2026-11-18)

Mục tiêu: tìm cách chạy MediaPipe (pose landmarker + EfficientDet) trên emulator `x86_64`
để kiểm chứng checklist E1–E12 mà không cần điện thoại thật.

## Cách đo

```bash
# AAR của bản đang dùng và bản mới, rồi liệt kê native lib theo ABI
for f in tv-old.aar tv-new.aar tc.aar pdc.aar; do echo "== $f"; unzip -l "$f" | grep -o 'jni/[^/]*/' | sort -u; done
```

## Kết quả

| Artifact | `.so` theo ABI | Ghi chú |
|---|---|---|
| `com.google.mediapipe:tasks-vision:0.10.14` (AAR 17,8 MB) | `arm64-v8a`, `armeabi-v7a`, `x86` | **không có `x86_64`** — đúng như HANDOFF ghi |
| `com.google.mediapipe:tasks-vision:1.2.3` (AAR 0,49 MB) | — | native đã tách khỏi AAR |
| `com.google.mediapipe:tasks-core:1.2.3` (AAR 32,6 MB, dependency của tasks-vision 1.2.3) | `arm64-v8a`, `armeabi-v7a`, `x86`, **`x86_64`** | **có `x86_64`** → emulator chạy được |
| `com.google.mlkit:pose-detection:17.0.0` (AAR 6,2 MB) | — | native nằm ở `pose-detection-common` |
| `com.google.mlkit:pose-detection-common:17.0.0` (AAR 0,53 MB) | — | không có native → không phải đường thoát cho x86_64 |

Dependency mới của `tasks-vision:1.2.3`: `com.google.mediapipe:tasks-core:1.2.3`,
`com.google.ai.edge.litertlm:litertlm-android:0.18.0`, `com.google.guava:guava:27.0.1-android`.

## Quyết định

Bump `tasks-vision` 0.10.14 → **1.2.3** (`app/build.gradle.kts`). Code hiện có biên dịch
nguyên vẹn (không phải sửa `pose/` hay `vision/`), 47 unit test xanh, APK debug build thành công.

### Dung lượng sau khi bump (đo trong workspace này, `aapt`/`unzip -l`)

| | Trước (0.10.14) | Sau (1.2.3) | Chênh |
|---|---|---|---|
| APK debug (arm64-v8a + x86_64) | 66.033.933 B | **87.167.096 B** | +21,1 MB |
| `lib/arm64-v8a` | ~21,4 MB (3 lib) | 24.342 MB (3 lib: `libmediapipe_tasks_jni.so` 15,97 MB) | **+2,9 MB** |
| `lib/x86_64` | 9,4 MB (1 lib: `libface_detector_v2_jni.so`) | 29,6 MB (3 lib, thêm `libmediapipe_tasks_jni.so` 19,8 MB) | **+20,2 MB** |

Toàn bộ phần tăng nằm ở **x86_64 — kiến trúc chỉ dùng cho emulator**. Máy thật (arm64-v8a)
chỉ tăng 2,9 MB. Khi làm ABI splits (mục 7 trong plan) APK arm64 sẽ tách khỏi phần này.

Hai model trong `assets/` vẫn `Stored`: `efficientdet_lite0.tflite` 13.836.895 B,
`pose_landmarker_lite.task` 5.777.746 B (không đổi).

## Rủi ro còn lại (chưa kiểm được trong VM này)

- 1.2.x là dòng mới của MediaPipe Tasks (kéo thêm LiteRT-LM). **Chưa xác nhận runtime**:
  `pose_landmarker_lite.task` và `efficientdet_lite0.tflite` có được 1.2.3 đọc đúng không.
  Cách kiểm: chạy E2/E3 trên emulator `x86_64`, xem log `pose landmarker unavailable` /
  `EfficientDet Lite0 unavailable` có còn xuất hiện không. Nếu model cũ không tương thích →
  tải model `.task`/`.tflite` định dạng mới đặt cùng tên vào `app/src/main/assets/`.
- Nếu 1.2.3 chạy được nhưng chậm hơn đáng kể, cân nhắc `debugImplementation` 1.2.3 +
  `releaseImplementation` 0.10.14 — **không khuyến nghị** vì debug và release sẽ chạy hai
  engine khác nhau, test trên emulator không còn đại diện cho bản phát hành.

## Phương án đã cân nhắc và loại

- `com.google.mlkit:pose-detection:17.0.0`: không có native `x86_64` (kiểm ở bảng trên) →
  không giải quyết được vấn đề, lại thêm dependency.
- Emulator image `x86` 32-bit + thêm `"x86"` vào `abiFilters`: chạy được nhưng image x86
  32-bit không còn được phát hành cho API mới, và APK +~13 MB cho mọi người dùng.
- Chấp nhận test pose trên máy thật: vẫn phải làm cho các mục D1–D11, nhưng chặn E2/E3/E7
  trong CI/emulator — đúng thứ cần gỡ.
