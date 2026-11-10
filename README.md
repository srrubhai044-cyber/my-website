# Superior X Editor

A native Android video-editing starter app built with Kotlin and AndroidX Media3 Transformer. Package: `com.superiorx.editor`.

## Run

Open this repository in Android Studio (or run `gradle :app:assembleDebug` with a local Gradle installation). The project uses Android Gradle Plugin 8.7.3, Kotlin 2.0.21, and requires Android SDK 35. Install the generated debug APK on Android 10 or later.

## Working features

- Select a video using Android's system document picker; no broad storage permission is requested.
- Preview and seek through the selected source video.
- Set trim-in and trim-out points from the current playhead.
- Remove the source audio track.
- Export H.264 MP4 at 720p, 1080p, or 2160p output height through Media3 Transformer (4K output is limited by the source/device encoder).
- Save completed exports to `Movies/Superior X Editor` using MediaStore, with a pending-item lifecycle and error cleanup.
- Reopen the last selected video through persisted document access where the provider allows it.

The source video is never overwritten. Export errors and save errors are displayed in the editor.

## Scope

This first implementation deliberately exposes only editing operations that are wired to Media3 and real output. Multi-clip composition, music mixing/import, filters, transitions, text/stickers, undo/redo, and frame-rate selection are not implemented yet; they are not presented as working controls. Additional tracks and effects should be added with Media3 Transformer/Composition and validated on-device before being advertised.
