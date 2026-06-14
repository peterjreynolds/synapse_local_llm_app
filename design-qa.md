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

The local JDK and Android SDK are now installed under:

```sh
~/.local/share/synapse-android-toolchain
```

The app builds locally, but this host has no connected Android phone or emulator.
Because the app cannot be launched here, I cannot capture an Android screenshot
and compare it against the reference image on this machine.

## Validation Completed

```sh
./gradlew test
./gradlew ktlintCheck lintDebug
./gradlew assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Required QA When Toolchain Exists

1. Connect the S25 Ultra with USB debugging or start an Android emulator.
2. Install with `adb install app/build/outputs/apk/debug/app-debug.apk`.
3. Capture the empty chat screen and compare it against the reference image for
   top-bar spacing, empty-state alignment, bottom composer size, keyboard
   handling, and text clipping.
