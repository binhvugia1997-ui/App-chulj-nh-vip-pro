# Prompt Freebuff — Nâng cấp AICameraComposer (gợi ý dáng + tự căn khung + tự chụp)

Skill: agency-mobile-app-builder

## Bối cảnh
- Project Android trên repo GitHub https://github.com/binhvugia1997-ui/App-chulj-nh-vip-pro (mở đúng repo này trong Freebuff rồi làm việc trên đó; code project đã có sẵn ở gốc repo), package `com.gia.aicomposer`, Kotlin + CameraX.
- Làm việc hoàn toàn trên repo GitHub này, không build local.
- File hiện có: `MainActivity.kt`, `SubjectTracker.kt` (khóa khung theo máy, ngưỡng `LOCK_THRESHOLD` gyro 0.08 / giữ 600ms — GIỮ NGUYÊN 2 ngưỡng này), `CompositionOverlayView.kt`, `GalleryActivity.kt`.
- Đọc kỹ code hiện có trước khi sửa; giữ nguyên hành vi cũ, chỉ thêm tính năng mới. Không copy code nguyên văn từ repo nào — chỉ lấy logic rồi viết lại theo kiến trúc app này.
- Cuối phiên: ghi trạng thái vào `HANDOFF.md` ở gốc project (ghi đè, không nối thêm) theo skill context-handoff, để phiên sau tiếp tục được ngay.

## Phần A — Gợi ý dáng theo cảnh
- Thư viện 30 dáng viết tay (20 FULL_BODY + 10 HALF_BODY): keypoint 0..1 trong khung riêng của dáng + aspect thật, ghi rõ affordance bắt buộc (ghế/bàn/cây/xe/tường...) và khuyến khích (mặt đất trống...).
- Chấm điểm dáng theo cảnh: +2/affordance khuyến khích có mặt, +1.5/affordance bắt buộc có mặt, −1.5 khi FULL_BODY thiếu vùng trống cao ≥ 0.5, +1.5 cho HALF_BODY khi vật gần che > 35%, −4 nếu dáng nằm trong 3 lần gợi ý gần nhất; chọn ngẫu nhiên trong nhóm ngang điểm cao nhất.
- Phân tích cảnh bằng EfficientDet Lite0 (`assets/efficientdet_lite0.tflite`, tải model về bỏ vào assets): chạy live-stream trên executor riêng, `STRATEGY_KEEP_ONLY_LATEST`; kết quả là `SceneState` (nhãn COCO + confidence + NEAR/MID/FAR + vùng trống lớn nhất).
- Overlay hình que (stick figure) của dáng được gợi ý lên preview; nút "Gợi ý dáng" chỉ chạy khi bấm. Khi không phân tích được cảnh thì fallback offline vẫn trả dáng.

## Phần B — Tự căn khung + tự chụp (pipeline 9 bước)
1. Chờ → 2. Mở camera → 3. Chờ ổn định → 4. Tìm vùng đẹp → 5. Khóa mẫu → 6. Sẵn sàng → 7. Đang chụp → 8. Đang lưu → 9. Xong/lỗi. Mỗi bước hiện 1 câu hướng dẫn ngắn.
- Ổn định: accel + gyro 60Hz, cửa sổ 0.8s, std accel 0.12 / gyro 0.08, 10 lần yên thì đạt, 5 lần rung thì mất ổn định; rung mạnh (>1.5 / >2.0) reset toàn bộ khóa.
- Tìm khung đẹp: ML Kit Face → Pose Landmarker (`assets/pose_landmarker_lite.task`) → saliency → khung giữa; chấm 40% confidence + 30% mặt trong khung + 20% điểm 1/3 + 10% không sát mép; khung 3:4.
- Khóa khung theo máy: khóa tâm + tư thế gốc, trôi cho phép = góc ±30° × 0.55 × cạnh màn hình × khoảng cách 0.6–1.4 × zoom; làm mượt thích ứng + bù dự đoán; hút dính tâm 5/25dp, hệ số 0.9.
- Tự chụp: dung sai 15dp, giữ căn chuẩn 1s thì khóa, đếm thêm 1s (chỉnh được trong Cài đặt, mặc định 1s) rồi chụp; lệch ra thì hủy đếm; chụp xong tự tắt pipeline và reset.

## Phần C — Tích hợp
- Một pipeline thống nhất: gợi ý dáng trước → khóa khung → tự chụp.
- Overlay gồm hình que + khung 3:4 + vòng tròn tâm, CHỈ hiện trên preview, không vào ảnh chụp.
- Cài đặt: bật/tắt gợi ý dáng, bật/tắt tự chụp, độ trễ chụp (mặc định 1s).

## Kiểm chứng bắt buộc trước khi báo xong
- Commit + push code lên GitHub, build bằng GitHub Actions (repo chưa có workflow build debug APK thì thêm `.github/workflows/android-debug.yml`; repo không có `gradle-wrapper.jar` nên workflow dùng `gradle/actions/setup-gradle` rồi chạy `gradle :app:assembleDebug`).
- Chỉ báo xong khi Actions chạy xanh và artifact APK tải về được; kiểm tra APK chứa class mới (PoseLibrary, CapturePipeline...) và 2 file model trong assets.
- Nếu build lỗi thì sửa tới khi xong, không báo xong khi chưa có APK thật từ Actions.
- Báo cáo cuối: liệt kê file đã thêm/sửa + link Actions run + dung lượng APK + phần nào chưa test trên máy thật (rung/ổn định, khóa khung, tự chụp cần test trên điện thoại).
