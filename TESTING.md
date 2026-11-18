# Checklist test — gợi ý dáng + tự căn khung/tự chụp

Cách dùng: chạy **mục 1 (emulator)** trước để loại lỗi nối dây/giao diện, rồi mới sang **mục 2
(điện thoại thật)** cho phần cảm biến. Mỗi dòng là một mục trong yêu cầu, có cột *Đạt* / *Không đạt*.

Chuẩn bị chung (cả hai máy):

- APK debug đã cài, đã cấp quyền camera; mở log: `adb logcat -s AiCameraAssistant FramingAnalyzer`.
- Ghi lại giá trị mặc định: Cài đặt → *Bật gợi ý dáng* = ON, *Bật tự chụp* = OFF, *Độ trễ* = 1.00 s.
- Ảnh lưu ở album `Pictures/AICameraComposer` trong MediaStore (Android 10+); Android 8-9 lưu ở
  `Android/data/com.gia.aicomposer/files/Pictures/AICameraComposer/`. Tên `AIC_*.jpg`.
- Ảnh chụp tay (`MainActivity`) và ảnh pipeline AI dùng chung đường lưu này (`util/PhotoStore.kt`) —
  nhờ vậy cả hai đều hiện trong Thư viện.
- Quy ước: **treo** = một bước guidance đứng yên > 5 s mà không tự chuyển bước.

---

## 1. Emulator — giao diện, nút gợi ý dáng, overlay hình que, bật/tắt pipeline

| Mã | Việc làm | Đạt khi | Không đạt khi |
|---|---|---|---|
| **E1** Mở app (nối dây cơ bản) | Cấp quyền camera, quay lại app | Preview hiện, guidance “Sẵn sàng — bấm ‘Tự chụp’ để bắt đầu”, không có `FATAL EXCEPTION` | Trắng/đen màn hình, crash, guidance trống |
| **E2** Nút gợi ý dáng (A) | Bấm **Gợi ý dáng** | Hình que màu accent hiện **trong** khung 3:4, đủ đầu–thân–tay–chân; dòng dưới guidance = “Tên dáng • điểm x.x”; log có `pose=… score=…` | Không vẽ gì, hình que lệch ra ngoài khung/đè lên nút bấm, điểm = 0 |
| **E3** Gợi ý theo cảnh (A) | Chĩa camera vào 2 cảnh khác nhau (người / đồ vật), bấm gợi ý 3–4 lần | Sau ~0.7 s cảnh mới được nhận; tên dáng hoặc điểm đổi; dáng lặp lại không xuất hiện liền nhau | Cùng một dáng + cùng điểm cho mọi cảnh, hoặc mỗi lần bấm ra dáng ngẫu nhiên không liên quan cảnh |
| **E4** Fallback không nhận diện được cảnh (A) | Che camera / chĩa vào tường trơn, bấm gợi ý | Vẫn ra một dáng từ thư viện mẫu, không crash; nếu cảnh rỗng thì có “Chưa nhận diện được cảnh — dùng dáng mặc định” | Không ra dáng, hoặc app treo ở lần bấm này |
| **E5** Overlay khung + vòng tâm (C) | Bật **Tự chụp** | Sau ~0.6 s (emulator không cảm biến) guidance “Đang tìm vùng đẹp — bước vào khung”; khung góc trắng dọc tỉ lệ ~3:4 nằm gọn trong vùng preview; vòng tâm 20 dp + vòng nét đứt 15 dp ở giữa | Khung méo/vuông, tràn ra ngoài letterbox, không có vòng tâm, overlay không hiện |
| **E6** Tắt gợi ý dáng trong Cài đặt (A/C) | Cài đặt → tắt *Bật gợi ý dáng*; bấm lại **Gợi ý dáng** | Hình que ẩn ngay, dòng tên dáng = “Đang tắt gợi ý dáng (bật trong Cài đặt)”, bấm nút chỉ hiện toast cùng câu đó, khung/vòng tâm vẫn còn | Hình que vẫn vẽ, hoặc khung biến mất luôn, hoặc crash |
| **E7** Bật pipeline → tự chụp (B) | Gạt **Tự chụp** ON, giữ máy yên | Chuỗi bước: *Giữ máy yên* → *Đang tìm vùng đẹp* → *Đã chọn khung* → *Căn tâm khung vào vòng tròn* → *Giữ nguyên…* → *Sắp chụp — giữ yên x.x giây* → *Đang chụp…* → *Đang lưu ảnh…* → *Xong!*; switch tự về OFF; có file ảnh mới | Treo ở bất kỳ bước nào > 5 s, không có ảnh, switch vẫn ON sau khi xong, hoặc pipeline chạy dù switch OFF |
| **E8** Tắt pipeline giữa chừng (B) | Khi đang ở *Căn tâm…* hoặc đang đếm, gạt switch OFF | Guidance về “Sẵn sàng — bấm ‘Tự chụp’ để bắt đầu”; khung + vòng tâm ẩn; không chụp thêm ảnh nào | Vẫn đếm/chụp tiếp, hoặc guidance đứng nguyên bước cũ |
| **E9** Nút chụp tay (C/B) | Bấm **Chụp** | *Đang chụp…* → *Đang lưu ảnh…* → *Xong!*, có ảnh; nếu đang bật tự chụp thì bị tắt sau khi chụp | Không có ảnh, ảnh có overlay, guidance nhảy loạn |
| **E10** Overlay không lọt vào ảnh (C) | Mở ảnh vừa chụp | Không có hình que, khung góc, vòng tâm trong ảnh | Có bất kỳ nét overlay nào trong file JPG |
| **E11** Pause/resume khi rời app (sửa `onStop`) | Ở bước *Căn tâm…*, nhấn Home rồi mở lại app | Trong ≤ 1 s quay về “Giữ máy yên để tìm khung hình” rồi chạy tiếp; **không** đứng ở “Sẵn sàng — bấm ‘Tự chụp’” và không kẹt ở *Đang tìm vùng đẹp* | Pipeline chết (guidance đứng “Sẵn sàng…” dù switch còn ON), hoặc kẹt *Đang tìm vùng đẹp* mãi |
| **E12** Watchdog ML Kit (sửa cờ `busy`) | Chĩa vào cảnh nghèo chi tiết để ML Kit chậm/không trả kết quả; lặp lại 3 lần | Sau tối đa ~2.5 s không có vùng đẹp thì tự lấy khung giữa và sang *Căn tâm…*; lần bật sau vẫn có kết quả khung mới (khung không đứng yên vĩnh viễn) | Kẹt ở *Đang tìm vùng đẹp*, hoặc các lần sau khung không bao giờ cập nhật nữa |

> Emulator không có gyro/gia tốc: `StabilityMonitor` báo “không cảm biến” và pipeline coi như yên sau
> 600 ms — đây là hành vi đúng, không phải lỗi. Các mục D1–D5 vì vậy bắt buộc chạy trên máy thật.
>
> **Emulator `x86_64`:** từ `tasks-vision:1.2.3` native x86_64 có trong `tasks-core:1.2.3`, nên
> MediaPipe **nạp được** trên emulator và E2/E3 là mục bắt buộc — không còn được tính là fallback.
> Nếu log vẫn báo `EfficientDet Lite0 unavailable` / `pose landmarker unavailable` thì đó là lỗi
> tương thích model của 1.2.3: ghi ❌ và xem `docs/reports/2026-11-18-mediapipe-abis.md`.

---

## 2. Điện thoại thật — rung/ổn định, khóa khung theo máy, tự chụp khi căn chuẩn

| Mã | Việc làm | Đạt khi | Không đạt khi |
|---|---|---|---|
| **D1** Nhận rung (B) | Ở bước *Căn tâm…*, lắc máy mạnh 1 lần | Về ngay “Giữ máy yên để tìm khung hình”; vòng tiến trình/hold và đếm ngược reset về 0; hết rung thì tự quay lại tìm khung trong < 1 s | Không phản ứng với rung, hoặc rung nhẹ cũng reset liên tục (máy rung tay mà không bao giờ tới *Căn tâm…*) |
| **D2** Ngưỡng cũ giữ nguyên (B) | Cầm máy đi bộ chậm / run tay nhẹ | Vẫn giữ được khóa và vẫn tự chụp (gyro 0.08 / 600 ms) | Cứ run nhẹ là reset mãi; hoặc lắc rất mạnh mà vẫn chụp |
| **D3** Khóa khung theo máy (B) | Ở *Căn tâm…*, xoay cổ tay sang phải ~20° rồi trái ~20° | Khung trôi ngược chiều cảnh (bám cảnh), chuyển động mượt không giật từng nấc; xoay quá 30° khung dừng trôi (không văng khỏi màn hình); xoay về tư thế cũ khung về gần chỗ ban đầu (lệch ≤ ~10 dp) | Khung đứng im khi xoay máy, trôi cùng chiều cảnh, văng mất khung, hoặc nhảy cóc |
| **D4** Hút dính tâm (B) | Đặt khung lệch tâm ~3 dp, rồi ~15 dp, rồi ~30 dp | 3 dp: khung dính hẳn tâm; 15 dp: hút một phần về tâm; 30 dp: đứng yên (không hút) | Không hút ở cự ly gần, hoặc hút cả khi lệch xa (khung bị “kéo” khỏi cảnh) |
| **D5** Tự chụp khi căn chuẩn (B) | Giữ tâm khung trong vòng 15 dp liên tục | *Giữ nguyên… đang khóa khung* (vòng tiến trình chạy) → *Sắp chụp — giữ yên 1.0 giây* → chụp → *Đang lưu ảnh…* → *Xong!*; switch OFF; ảnh đúng cảnh lúc căn | Chụp khi khung còn lệch > 15 dp; hoặc giữ yên đủ lâu mà không chụp; hoặc chụp 2 ảnh cho một lần căn |
| **D6** Huỷ đếm khi lệch (B) | Đang đếm ngược, đưa khung lệch ra ngoài 15 dp rồi quay lại | Đếm ngược + vòng tiến trình biến mất, không chụp; khi căn lại thì đếm **lại từ đầu** (không cộng dồn) | Vẫn chụp dù đã lệch, hoặc đếm cộng dồn qua lần lệch |
| **D7** Độ trễ trong Cài đặt (B) | Đặt *Độ trễ* = 0.00 s, rồi 3.00 s, căn lại mỗi lần | 0 s: chụp gần như ngay sau khi khóa khung; 3 s: chụp sau ~3 s (sai ± 0.3 s); số trên guidance khớp giá trị đặt | Thời gian không đổi theo cài đặt, hoặc guidance hiện số khác |
| **D8** Ảnh cuối (C/B) | Mở ảnh vừa tự chụp bằng Gallery | Đúng hướng (không bị xoay 90°), nét, không có overlay, tên `AIC_yyyyMMdd_HHmmss_SSS.jpg` | Ảnh xoay sai, mất nét, có overlay, hoặc ghi đè ảnh cũ |
| **D9** Khoá màn hình / chuyển app (sửa `onStop`) | Khoá màn hình khi đang *Căn tâm…*, rồi khi đang *Đang chụp…*; mở lại | Không crash; về *Giữ máy yên* rồi chạy tiếp; không còn ảnh “ma”/đếm ngược treo; nếu rơi đúng lúc chụp thì ảnh vẫn được lưu và pipeline về *Sẵn sàng* | Crash, pipeline chết, đếm ngược tiếp tục chạy khi màn hình khoá, ảnh lưu nhưng switch vẫn ON |
| **D10** Watchdog khi ML Kit bận (sửa `busy`) | Chĩa vào cảnh tối/thiếu sáng, bật tắt tự chụp 5 lần liên tiếp | Mỗi lần đều tới được *Căn tâm…* trong ≤ 3 s; khung vẫn cập nhật sau mỗi lần bật (không kẹt cờ `busy`) | Từ lần thứ 2 trở đi không có khung/kết quả mới, hoặc kẹt *Đang tìm vùng đẹp* |
| **D11** Không cảm biến (máy thiếu gyro) | Chạy trên máy/emulator không gyro | Pipeline vẫn chạy nhờ mốc 600 ms, guidance không nhảy loạn | Kẹt *Giữ máy yên* vĩnh viễn |

---

## 3. Thư viện ảnh (GalleryActivity + PhotoStore)

| Mã | Việc làm | Đạt khi | Không đạt khi |
|---|---|---|---|
| **G1** Ảnh chụp tay | Bấm **CHUP**, rồi mở **Thu viện** | Ảnh vừa chụp ở ô đầu tiên; chạm vào mở full màn hình, chạm lần nữa đóng | Không thấy ảnh, lưới trống, hoặc mở ảnh bị crash/OOM |
| **G2** Ảnh pipeline AI | Bật **Tự chụp**, đợi chụp xong, quay lại app rồi mở **Thu viện** | Ảnh tự chụp có mặt ngay (không cần khởi động lại app); **không** có ảnh “nửa vời”/ô trống | Ảnh tự chụp chỉ nằm trong `Android/data/...`, không thấy trong thư viện |
| **G3** Nạp lại khi quay về | Đang ở Thư viện, bấm Home → chụp thêm 1 ảnh bằng app khác có tên `AIC_`… → quay lại Thư viện | Danh sách tự nạp lại trong `onResume`, thấy ảnh mới | Phải tắt/mở lại activity mới thấy |
| **G4** Ảnh 1:1 và xoay | Chụp chế độ **1:1** cầm dọc, mở trong Thư viện | Ảnh vuông, đúng chiều (xoay EXIF được áp dụng), không méo | Ảnh sai chiều 90°, crop lệch tâm, ảnh bị bóp méo |
| **G5** Android 8-9 + thư viện rỗng | Chạy trên API 26-28, cấp `READ_EXTERNAL_STORAGE`; sau đó xoá app data rồi mở Thư viện | Có ảnh trong thư mục app; sau khi xoá data hiện “Chưa có ảnh nào — chụp một tấm trước”, không crash | Hỏi quyền rồi vẫn trống, hoặc crash khi thư mục rỗng |

> Kiểm tra nhanh bằng adb:
> `adb shell content query --uri content://media/external/images/media --projection _display_name:relative_path --where "_display_name LIKE 'AIC_%'"`

---

## 4. Kết luận

- **Đạt để giao**: E1–E12 đều đạt, G1–G5 đạt **và** D1–D8 đạt; D9–D11 là bắt buộc nếu máy test có khoá màn hình /
  cảnh tối / thiếu gyro.
- Ghi lại máy + Android version khi test, vì ngưỡng cảm biến và chất lượng ảnh phụ thuộc thiết bị:

  ```
  Thiết bị: …   Android: …   Ngày: …
  E1 ☐ E2 ☐ E3 ☐ E4 ☐ E5 ☐ E6 ☐ E7 ☐ E8 ☐ E9 ☐ E10 ☐ E11 ☐ E12 ☐
  G1 ☐ G2 ☐ G3 ☐ G4 ☐ G5 ☐
  D1 ☐ D2 ☐ D3 ☐ D4 ☐ D5 ☐ D6 ☐ D7 ☐ D8 ☐ D9 ☐ D10 ☐ D11 ☐
  ```
- Mục nào ❌ thì gửi kèm: bước đang ở (guidance), `adb logcat -s AiCameraAssistant`, và ảnh chụp
  màn hình — đủ để khoanh vùng trong `CapturePipeline` / `FrameLockTracker` / `FramingAnalyzer`.
