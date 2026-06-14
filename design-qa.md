# Design QA

final result: blocked

Reference:
`/tmp/codex-remote-attachments/019ec6fd-d2a0-7b31-8ab5-e8b3fa0ea067/b18503b0-b631-4912-a6da-af373ebbc303/1-Photo-1.jpg`

## What Was Implemented

- Dark mobile-first chat surface with a centered empty state, top runtime controls,
  bottom rounded composer, attach, mic, send, stop, and speaker controls.
- Memory and settings panels reachable from the top bar.
- Attachment metadata chips and text-file extraction for prompt context.
- Storage warning banner when memory writes should be paused.

## Blocker

This host has no Java, Gradle installation, Android SDK, `sdkmanager`, `adb`,
Kotlin compiler, or emulator. `./gradlew tasks --all` stops at:

```text
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
```

Because the app cannot be built or launched here, I cannot capture an Android
screenshot and compare it against the reference image on this machine.

## Required QA When Toolchain Exists

1. Install JDK 17+ and Android SDK platform 36.
2. Run `./gradlew test`.
3. Run `./gradlew assembleDebug`.
4. Install on the S25 Ultra with `adb install app/build/outputs/apk/debug/app-debug.apk`.
5. Capture the empty chat screen and compare it against the reference image for
   top-bar spacing, empty-state alignment, bottom composer size, keyboard
   handling, and text clipping.
