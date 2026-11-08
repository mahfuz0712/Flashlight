# Flashlight

Offline flashlight for Android (Kotlin, Jetpack Compose, Camera2). Package: `com.mux.flashlight`

Adjustable brightness (100% = the brightest the flash can do), strobe, SOS, morse code, beacon, a colored
screen light, auto-off timer, battery and heat guard, shake to toggle, quick settings tile and home screen widget.

## Build
Push to GitHub -> Actions -> "Build APK" -> download artifact `Flashlight-debug-apk`.

## Structure
- `Hud.kt`      chamfered-corner HUD design system (same as the Clock app)
- `Torch.kt`    the flashlight engine: hardware brightness levels, soft dim, patterns, guards
- `Service.kt`  foreground service, quick tile, widget, boot receiver
- `Store.kt`    settings, `Screens.kt` the five screens and the screen-light overlay, `MainActivity.kt` navigation

## Brightness
Android 13+ phones whose flash supports it expose real hardware brightness levels. On other phones the flash
only has one level, so the app offers "soft dim" (very fast switching) and a screen light with real brightness.

## Release (in-app updates)
1. Bump `appVersionName` in `app/build.gradle.kts` (and `version` in `metadata.json`).
2. Build, then create a GitHub Release tagged with the same number (e.g. `1.1.0`) and upload the APK.
3. Installed apps check `releases/latest` on open and offer the update.

## Before publishing
Edit `DEV_NAME`, `DEV_EMAIL`, `GITHUB_USER` and `REPO` in `app/src/main/java/com/mux/flashlight/Update.kt`,
and `author` in `metadata.json`. Replace the images in `public/screenshots` with real screenshots from your phone.
