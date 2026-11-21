# FIRAS TV — Android 2.1.2

Original Android project for FIRAS TV. Active development is isolated on the `restore-source-2.1.3` branch; the `main` branch is kept separate.

## Included
- Arabic RTL black-and-red interface.
- Wide landscape layout for phones and tablets, with the player beside the channel browser.
- Video player with fullscreen support and HLS playback through hls.js.
- Custom Android launcher icon.
- First-launch merge of the nine bundled `channels.json` channels without replacing existing user channels.
- Full backup for channels, categories, favorites, viewing history, and settings.

## Download the APK
1. Open the [FIRAS TV GitHub Actions workflow](https://github.com/trekforbeer-lang/FIRAS-TV-Android/actions/workflows/android.yml).
2. Select the latest run on `restore-source-2.1.3` and wait for a green success status.
3. Open the run and scroll to **Artifacts**.
4. Download `FIRAS-TV-APK`, unzip it, and install `app-debug.apk` on Android.

## Build configuration
- Java 17
- Gradle 8.7
- Android Gradle Plugin 8.6.1
- Compile SDK 35 / Target SDK 35
- Application ID: `com.firas.tv`
- Version 2.1.2 (versionCode 5)
- The current workflow produces a debug APK for testing, not a signed release build.

## Data preservation
Do not replace `channels.json` or remove user channel data. On first launch only, bundled starter channels are merged without deleting existing channels. A complete backup is available in the admin panel.

## Testing note
A successful APK build does not guarantee that every stream URL is currently reachable. Stream availability depends on the provider and its restrictions. Real-device playback testing is still required before calling the player fully tested.
