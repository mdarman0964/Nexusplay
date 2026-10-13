# Nexusplay

Ad-free, Android-only local video player inspired by modern media players. Nexusplay uses its own branding and focuses on privacy: local MediaStore library, search, folders, recent videos, and playback through AndroidX Media3 ExoPlayer.

## Planned stack
- Java 17
- Android Gradle Plugin 8.13.0
- Android SDK 36, min SDK 23
- AndroidX Media3 ExoPlayer and PlayerView
- No advertising, analytics, or network permissions

## Features in the initial app
- Local video library using Android MediaStore
- Runtime video permission handling for Android 13+ and older devices
- Search by title, filename, and folder
- All Videos / Folders / Recent filters
- Full-screen playback with standard ExoPlayer controls
- Dark interface with purple and mint Nexusplay branding

## Build
Open the project in Android Studio with JDK 17 and Android SDK Platform 36, sync Gradle, then choose **Build > Build APK(s)**. GitHub Actions is configured to build a debug APK on push and pull request.

Note: codec/container support depends on Android device decoder capabilities. A build must pass CI and be tested on a real device before claiming a tested release.
