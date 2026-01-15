# Hướng Dẫn Build APK Cho Đồng Hồ Thông Minh

## Yêu Cầu Hệ Thống

1. **Java Development Kit (JDK)** - Phiên bản 11 trở lên
2. **Android SDK** - Đã cài đặt Android Studio hoặc Android SDK
3. **Gradle** - Đã có sẵn trong dự án (gradlew)
4. **Windows PowerShell** hoặc Command Prompt

## Các Bước Build APK

### Bước 1: Kiểm Tra Môi Trường

Mở PowerShell hoặc Command Prompt và kiểm tra:
- Java: `java -version` (cần JDK 11+)
- Android SDK: Đảm bảo đã cài Android SDK với API level 36

### Bước 1.5: Chấp Nhận Android SDK Licenses (QUAN TRỌNG)

**Trước khi build, bạn PHẢI chấp nhận Android SDK licenses:**

#### Cách 1: Qua Android Studio (Khuyến Nghị)
1. Mở **Android Studio**
2. Vào **File > Settings** (hoặc **File > Preferences** trên Mac)
3. Chọn **Appearance & Behavior > System Settings > Android SDK**
4. Chọn tab **SDK Platforms**
5. Đảm bảo **Android API 36** được chọn
6. Chọn tab **SDK Tools**
7. Đảm bảo **NDK (Side by side) 29.0.14033849** được chọn
8. Nhấn **Apply**
9. Khi được hỏi, nhấn **Accept** để chấp nhận tất cả licenses

#### Cách 2: Qua Command Line (Nếu có Android SDK Command-line Tools)
```bash
# Tìm sdkmanager trong Android SDK
# Thường nằm tại: C:\Users\YourUsername\AppData\Local\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat

# Chạy lệnh chấp nhận licenses
sdkmanager --licenses
# Nhấn 'y' để chấp nhận từng license
```

**Lưu ý**: Nếu không chấp nhận licenses, quá trình build sẽ thất bại với lỗi "License not accepted".

### Bước 2: Build APK Debug (Không Cần Keystore)

Để build APK debug cho flavor **foss** (khuyến nghị cho đồng hồ thông minh):

```bash
# Di chuyển vào thư mục dự án
cd D:\github\android-chess\android-chess

# Build APK debug cho flavor foss
.\gradlew.bat assembleFossDebug
```

Hoặc nếu muốn build cho flavor playStore:
```bash
.\gradlew.bat assemblePlayStoreDebug
```

### Bước 3: Build APK Release (Cần Keystore)

**Lưu ý**: File APK release cần được ký bằng keystore. Dự án đang cấu hình keystore tại `../../android-keystore`.

Nếu bạn có keystore:
```bash
.\gradlew.bat assembleFossRelease
```

Nếu chưa có keystore, bạn có thể:
1. Tạo keystore mới
2. Hoặc build APK debug (không cần sign) để test

### Bước 4: Tìm File APK Đã Build

Sau khi build xong, file APK sẽ nằm tại:
- **Debug APK**: `app\build\outputs\apk\foss\debug\app-foss-debug.apk`
- **Release APK**: `app\build\outputs\apk\foss\release\app-foss-release.apk`

## Cài Đặt APK Vào Đồng Hồ Thông Minh

### Cách 1: Qua ADB (Khuyến Nghị)

1. **Bật Developer Options trên đồng hồ**:
   - Vào Settings > About
   - Tap vào "Build number" 7 lần
   - Quay lại Settings, tìm "Developer options"
   - Bật "USB debugging"

2. **Kết nối đồng hồ với máy tính qua USB** hoặc qua WiFi ADB

3. **Cài đặt APK**:
```bash
# Kiểm tra thiết bị đã kết nối
adb devices

# Cài đặt APK
adb install app\build\outputs\apk\foss\debug\app-foss-debug.apk
```

### Cách 2: Copy File APK Trực Tiếp

1. Copy file APK vào đồng hồ (qua USB hoặc cloud)
2. Mở file APK trên đồng hồ bằng File Manager
3. Cho phép cài đặt từ "Unknown sources" nếu được hỏi
4. Cài đặt APK

## Thông Tin Dự Án

- **Application ID**: `jwtc.android.chess`
- **Min SDK Version**: 24 (Android 7.0)
- **Target SDK Version**: 36
- **Product Flavors**: 
  - `foss`: Không có Google Play Services (phù hợp cho đồng hồ thông minh)
  - `playStore`: Có Google Play Services

## Xử Lý Lỗi Thường Gặp

### Lỗi: "License not accepted" hoặc "Failed to install Android SDK packages"
**Đây là lỗi phổ biến nhất!**

Lỗi này xảy ra khi bạn chưa chấp nhận Android SDK licenses. Cách xử lý:

1. **Mở Android Studio** và làm theo **Bước 1.5** ở trên
2. Hoặc chạy `sdkmanager --licenses` và nhấn 'y' để chấp nhận tất cả
3. Sau khi chấp nhận, chạy lại lệnh build

**Lưu ý**: NDK và Android SDK Platform 36 đều cần license được chấp nhận.

### Lỗi: "SDK location not found"
- Cài đặt Android Studio và thiết lập ANDROID_HOME environment variable
- Hoặc tạo file `local.properties` trong thư mục gốc với nội dung:
```
sdk.dir=C:\\Users\\YourUsername\\AppData\\Local\\Android\\Sdk
```

### Lỗi: "Keystore not found"
- Build APK debug thay vì release
- Hoặc tạo keystore mới và cập nhật đường dẫn trong `app/build.gradle`

### Lỗi: "NDK not found"
- Cài đặt NDK version 29.0.14033849 qua Android Studio SDK Manager

## Lưu Ý Quan Trọng

1. **Flavor FOSS**: Nên dùng flavor `foss` cho đồng hồ thông minh vì không yêu cầu Google Play Services
2. **Kích Thước Màn Hình**: Ứng dụng hỗ trợ màn hình nhỏ (`android:smallScreens="false"`), có thể cần điều chỉnh cho đồng hồ
3. **Touchscreen**: Ứng dụng không bắt buộc touchscreen (`android:required="false"`), phù hợp với đồng hồ có nút bấm

