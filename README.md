# FIRAS TV Kotlin

Android streaming client built with Kotlin, AndroidX Media3, and OkHttp 4.12.0. Minimum Android API: 21.

The launcher opens the lightweight FIRAS TV home screen first; the player is opened explicitly from its button. This keeps player initialization out of the app's initial launch path.

Build: `gradle assembleDebug assembleRelease`. GitHub Actions uploads debug and release APKs as `FIRAS-TV-Kotlin-APK`.

Channel entries are configured in `app/src/main/assets/channels_stream_config.json`; a writable copy under the app's `filesDir` takes precedence.
