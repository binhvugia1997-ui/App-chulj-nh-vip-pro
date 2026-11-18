# AI Composition Camera — v0.1

App chup anh Android viet lai clean-room tu dau (Kotlin + CameraX).
Khong copy code, giao dien, logo, ten thuong hieu hay asset cua bat ky
app goc nao. Ten goi "AI Composition Camera" va package
`com.gia.aicomposer` la ten tam cua du an nay.

## Tinh nang v0.1

- Preview camera sau bang CameraX (`PreviewView`, FIT_CENTER).
- Overlay can khung: luoi 1/3, **cham trang** (diem muc tieu, mac dinh la
  giao diem 1/3 tren-phai), **cham vang** (vi tri chu the do tracker
  uoc luong), duong noi huong dan.
- **Tu dong chup**: khi cham vang trung cham trang (khoang cach chuan hoa
  < 0.08) va giu on ~0.6 giay thi tu khoa khung va chup. Nghi 2.5 giay
  giua 2 lan tu chup. Bat/tat bang nut "Tu dong".
- **Cham vao man hinh** de doi vi tri cham trang muc tieu.
- Nut chup tay (CHUP).
- Doi ti le khung hinh: 4:3, 16:9, 1:1 (1:1 chup 4:3 roi crop vuong tam
  giua truoc khi luu).
- Luu anh vao MediaStore, thu muc `Pictures/AICameraComposer`, ten
  `AIC_yyyyMMdd_HHmmss.jpg`.
- Man hinh Thu vien: luoi thumbnail 100 anh moi nhat, cham de xem full.

## Heuristic v0.1 (chua phai model AI that)

`SubjectTracker` uoc luong diem chu the tu mat phang do sang (Y):
chia khung hinh 640x480 thanh luoi 32x18, tinh do tuong phan tung o
(|do sang o - do sang trung binh|), lay trong tam co trong so, lam muot
bang EMA. Chu the thuong tuong phan manh hon nen hinh nen.

Gioi han: chi la heuristic do sang/tuong phan, khong hieu noi dung anh.
Nen nhieu chi tiet hoac nen sang/toi manh co the keo cham vang lech.

## Huong thay bang model that (sau v0.1)

- ML Kit Object Detection (nhan dien vat the, tra bounding box -> lay tam).
- Hoac model TFLite tu train (phan doan chu the / pose) chay tren
  `ImageAnalysis`, thay the ham `SubjectTracker.analyze()`.
- Giua nguyen giao dien: tracker chi can tra ve toa do chuan hoa (x, y).

## Build

Yeu cau: JDK 17+ (da test voi JDK 21), Android SDK voi
`platform-tools`, `platforms;android-34`, `build-tools;34.0.0`.
`local.properties` da tro san toi SDK tren may ollama; neu build o may
khac thi sua lai `sdk.dir` hoac dat bien `ANDROID_HOME`.

```bat
gradlew.bat assembleDebug
```

APK debug o `app\build\outputs\apk\debug\app-debug.apk`.

`abiFilters` giu `arm64-v8a` + `x86_64`: APK debug ~110.943.003 byte (105,8 MiB) khi dong ca 4 ABI
xuong con 66.033.933 byte (-40,5%) voi `tasks-vision:0.10.14`. Sau khi nang len `tasks-vision:1.2.3`
(dependency `tasks-core:1.2.3` co native x86_64 de chay tren emulator) APK debug la 87.167.096 byte;
phan tang nam o `lib/x86_64` (+20,2 MB), `lib/arm64-v8a` chi +2,9 MB. Hai model trong `assets/` van
duoc dong o dang khong nen (stored).

Cai len dien thoai:

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Ghi chu

- Chi build ban debug. Khong build release, khong ky release.
- Chi dung camera sau o v0.1 (camera truoc se bi mirror, chua xu ly).
- Anh luu dung MediaStore nen hien trong Thu vien cua may.
- Anh cua pipeline AI di cung duong luu (`util/PhotoStore.kt`) nen cung hien trong
  `GalleryActivity`; Android 8-9 luu o thu muc anh rieng cua app va duoc thu vien quet them.
