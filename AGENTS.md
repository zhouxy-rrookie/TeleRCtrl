# AGENTS.md

## Build And Verify
- `./gradlew assembleDebug` builds the debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
- Focused verification tasks exist at `:app:lintDebug`, `:app:testDebugUnitTest`, and `:app:connectedDebugAndroidTest`.
- `./gradlew testDebugUnitTest` currently succeeds with `NO-SOURCE`; there are no checked-in tests under `app/src/test` or `app/src/androidTest` right now.

## Build Environment
- Gradle wrapper: `9.0.0`, AGP `8.5.2`, Kotlin `1.9.24`, `compileSdk = 34`, `minSdk = 24`, `targetSdk = 34`.
- Source/target compatibility is Java 17 (`jvmTarget = "17"`).

## Repo Shape
- Single Android app module: `:app`.
- `MainActivity` is the real hub for Bluetooth permissions, paired-device selection, socket IO, joystick streaming, mode selection, sidebar state, and `UvcPreviewController` wiring. There is no separate service or data layer to update instead.
- Main UI is `app/src/main/res/layout/activity_main.xml`; the manifest locks the activity to `sensorLandscape`, so layout changes should be judged in landscape first.
- `com.serenegiant.widget.UVCCameraTextureView` is a tiny local shim in this repo, not something provided by the UVC dependency.

## Bluetooth Control Protocol
- `README.md` is stale here: the app no longer sends `F/B/L/R/S\n` commands.
- Bluetooth transport is still Classic SPP with UUID `00001101-0000-1000-8000-00805F9B34FB`, and the device picker only shows `BluetoothAdapter.bondedDevices`; pairing must happen in Android system settings first.
- Motion frames are 8 bytes: `[0xAA, 0x55, x, y, yaw, seq, checksum, 0x0D]`.
- Joystick axes use a `0.14` dead zone, scale to `-100..100`, and resend every `50 ms` while either stick is active.
- Mode frames are `[0xAA, 0x55, 0x7F, mode, 0x00, seq, checksum, 0x0D]`; the UI only exposes modes `1..15`.
- `checksum` is the low byte of the sum of frame bytes `0..5`.

## UVC Notes
- `UvcPreviewController` owns USB monitor registration and camera teardown; keep its calls aligned with `MainActivity.onStart()`, `onStop()`, and `onDestroy()`.
- UVC detection is interface/class-based: only USB video-class devices are treated as cameras.
- Preview fallback order is MJPEG first, then YUYV, trying `1280x720`, `1024x768`, `960x720`, `800x600`, `640x480`, `320x240`.
- The UVC dependency is `org.uvccamera:lib:0.0.13` from JitPack; keep the JitPack repository in `settings.gradle.kts` if you touch dependency resolution.
- The manifest marks both Bluetooth hardware and USB host support as required; this app intentionally targets devices with both.

## Copy
- User-facing status text and most controls are Chinese in `app/src/main/res/values/strings.xml`; preserve that language unless the user asks for a localization change.
