# Map Image Downloader - Android App

WebView app cho website https://downloadmapimage.com/

## Thông tin version
- **versionCode**: 19
- **versionName**: 1.1.9
- **applicationId**: com.downloadmapimage.app
- **minSdk**: 21 (Android 5.0+)
- **targetSdk**: 34 (Android 14)

---

## Cấu trúc project

```
android-project/
├── app/
│   ├── src/main/
│   │   ├── java/com/downloadmapimage/app/
│   │   │   └── MainActivity.java       ← Code chính
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml
│   │   │   ├── values/strings.xml
│   │   │   ├── values/styles.xml
│   │   │   ├── xml/network_security_config.xml
│   │   │   ├── xml/file_paths.xml
│   │   │   ├── mipmap-hdpi/            ← Icon app
│   │   │   ├── mipmap-mdpi/
│   │   │   ├── mipmap-xhdpi/
│   │   │   ├── mipmap-xxhdpi/
│   │   │   └── mipmap-xxxhdpi/
│   │   └── AndroidManifest.xml
│   ├── build.gradle
│   └── proguard-rules.pro
├── gradle/wrapper/
│   └── gradle-wrapper.properties
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## Cách build với Android Studio

### Bước 1: Mở project
1. Mở **Android Studio** (phiên bản Hedgehog 2023.1.1 trở lên)
2. Chọn **File → Open**
3. Trỏ đến thư mục `android-project/`
4. Chờ Gradle sync hoàn tất

### Bước 2: Tạo Keystore (nếu chưa có)
> **Quan trọng**: Dùng lại keystore cũ nếu đã upload versionCode 10 lên Play Store.
> Nếu dùng keystore khác, Google Play sẽ không cho phép update app cũ.

Nếu cần tạo keystore mới:
```
Build → Generate Signed Bundle / APK → APK → Create new...
```

### Bước 3: Build APK / AAB

**Build APK (để test trực tiếp trên máy):**
```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```

**Build AAB (để upload Google Play):**
```
Build → Generate Signed Bundle / APK → Android App Bundle → Next
→ Chọn keystore → Next → release → Finish
```

File AAB output: `app/release/app-release.aab`

---

## Tính năng

### ✅ Tải ảnh về Gallery
- Khi người dùng nhấn nút download trên web, ảnh tự động lưu vào **Pictures/MapDownloader/**
- Android 10+ (API 29+): dùng **MediaStore API** - không cần WRITE_EXTERNAL_STORAGE
- Android 9 trở xuống: dùng **DownloadManager** - cần WRITE_EXTERNAL_STORAGE (đã khai báo maxSdkVersion=28)

### ✅ "Open in new tab" mở Chrome/Samsung Browser
- Link có `target="_blank"` được bắt bằng JavaScript inject
- Mở bằng `Intent.ACTION_VIEW` → thiết bị tự chọn trình duyệt mặc định

### ✅ Pinch-to-zoom
- Người dùng có thể zoom WebView bằng 2 ngón tay
- Nút zoom +/- ẩn để giao diện gọn hơn

### ✅ Tránh vùng camera/status bar
- Dùng `WindowCompat.setDecorFitsSystemWindows(false)` + `WindowInsetsCompat`
- WebView tự động thụt xuống đúng chiều cao status bar của từng thiết bị

### ✅ Bảo mật
- Chỉ có **INTERNET** permission (và WRITE_EXTERNAL_STORAGE chỉ cho API < 29)
- HTTPS only (cleartext bị chặn)
- Network Security Config giới hạn domain

---

## Permissions

| Permission | Mục đích | Điều kiện |
|---|---|---|
| `INTERNET` | Tải web và ảnh | Luôn luôn |
| `WRITE_EXTERNAL_STORAGE` | Lưu ảnh (legacy) | Chỉ API ≤ 28 (Android 9) |

> Không cần READ_EXTERNAL_STORAGE vì app chỉ ghi, không đọc file người dùng.

---

## Lưu ý khi publish lên Google Play

1. **Dùng đúng keystore** từ lần upload trước (versionCode 10)
2. versionCode phải **lớn hơn 10** → đã set là **11** ✓
3. Upload file `.aab` (không phải `.apk`) cho Google Play
4. Điền đủ thông tin Store Listing trước khi submit review
