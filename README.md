# Flow Launcher

A small, original Android launcher inspired by the fluid interaction principles of minimalist list launchers.

## Current v0.1 features

- Real installed-app icons and labels
- Vertical favourites list
- A–Z thumb scrubber with haptic ticks
- Animated app-list reveal with staggered slide/scale transitions
- Search (tap Search or swipe up)
- Long-press any app in the app list to add/remove it from favourites
- Long-press a favourite app to remove it
- User-created website shortcuts mixed into favourites
- Optional custom image icon for every website shortcut
- Wallpaper-first transparent home screen
- No account, ads, analytics, telemetry, or network permission

## Website shortcuts

Tap **+ Website**, enter a name and URL, and optionally select any image from your phone as the shortcut icon. The shortcut is saved locally and added to the home list.

## Building

This project is intentionally dependency-light: it uses only Android framework APIs, no AndroidX and no third-party libraries.

Recommended toolchain:

- Android Studio / AGP 9.4.1
- Gradle 9.6
- JDK 17+
- compileSdk 37

Open the project folder in Android Studio, sync, then build the `debug` APK.

## Setting as your launcher

After installing, press the Home gesture/button. Android should offer **Flow Launcher** as a Home app. You can also change it from Android settings under Default apps > Home app.

## Interaction notes

- Drag on the A–Z strip: browse apps by letter.
- Long-press a listed app: add/remove favourite.
- Tap Search or swipe upward: search all apps.
- Tap + Website: make your own site shortcut.
- Long-press a website shortcut on Home: remove it.

## Design/legal note

Flow Launcher is an original implementation. It intentionally does not contain Niagara Launcher's source code, branding, proprietary graphics, or copied assets. It recreates general interaction ideas (minimal vertical list, alphabet scrubber, animated transitions) with original code.
