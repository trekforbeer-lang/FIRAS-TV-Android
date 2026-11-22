# FIRAS TV Kotlin

Android streaming client built with Kotlin, AndroidX Media3, and OkHttp 4.12.0. Minimum Android API: 21.

Build: `gradle assembleDebug assembleRelease`. GitHub Actions uploads debug and release APKs as `FIRAS-TV-Kotlin-APK`.

Channel entries are configured in `app/src/main/assets/channels_stream_config.json`; a writable copy under the app's `filesDir` takes precedence.
