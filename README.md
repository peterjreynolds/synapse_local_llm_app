# Synapse Local LLM App

Synapse is a native Android chat UI for a phone-local `llama.cpp` model.
The app can run an embedded ARM64 `llama.cpp` runtime directly in the APK, or
fall back to a Termux `llama-server` on `127.0.0.1:8080`. Chat and
evidence-backed memory stay in app-local storage.

## Embedded Runtime

The APK includes native `llama.cpp` libraries for `arm64-v8a`, but it does not
bundle model weights. GGUF files are too large and are intentionally excluded
from git and APK packaging. On the phone:

1. Download a `.gguf` model into Downloads or another user-visible folder.
2. Open Synapse, go to Settings, keep runtime set to `Embedded`.
3. Tap `Import GGUF` and pick the model.
4. Tap the play button in the top bar to load the embedded model.

Synapse validates the `GGUF` magic bytes and copies the model into app-private
storage so native `llama.cpp` can open it by filesystem path.

## Termux Server Runtime

The expected Termux server command is:

```sh
cd ~/llama.cpp/build
./bin/llama-server -m ../hardcore.gguf -c 2048 -t 6 --host 127.0.0.1 --port 8080 > /dev/null 2>&1 &
```

Termux startup from Synapse requires:

- Termux installed.
- Synapse granted `Run commands in Termux environment`.
- `allow-external-apps = true` in `~/.termux/termux.properties`.

## Build

This workspace was validated with a user-local JDK and Android SDK at:

```sh
~/.local/share/synapse-android-toolchain
```

For a fresh shell on this machine:

```sh
export TOOLCHAIN_ROOT="$HOME/.local/share/synapse-android-toolchain"
export JAVA_HOME="$TOOLCHAIN_ROOT/jdk-17"
export ANDROID_SDK_ROOT="$TOOLCHAIN_ROOT/android-sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
```

Then run:

```sh
./gradlew test
./gradlew ktlintCheck lintDebug
./gradlew assembleDebug
```

The debug APK will be under `app/build/outputs/apk/debug/`.

## Memory Safety

Synapse memory uses one local truth: Room entities plus durable write/retrieval
receipts. The local LLM may propose memories, but durable memory writes require
source evidence and pass through the admission gate. When storage gets tight,
Synapse pauses memory writes and keeps chat usable.
