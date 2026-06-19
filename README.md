# Synapse Local LLM App

Synapse is a native Android chat UI for a phone-local `llama.cpp` model.
The app can run an embedded ARM64 `llama.cpp` runtime directly in the APK, or
fall back to a Termux `llama-server` on `127.0.0.1:8080`. Chat and
evidence-backed memory stay in app-local storage.

The active product roadmap is tracked in
[`docs/canonical-master-plan.md`](docs/canonical-master-plan.md).

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

The default response budget is tuned for phone chat responsiveness. You can
raise `Tokens` in Settings for long answers, but short everyday chat should not
need a large budget.

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

## GitHub APK Delivery

The repo includes `.github/workflows/android-apk.yml`. Every push to
`main` builds `Synapse-AI.apk`, uploads it as a workflow artifact, updates the
rolling release tag `synapse-ai`, and force-replaces the
`apk-latest` branch with one canonical repo-file APK.

That gives the phone a stable place to download the newest APK:

```text
https://github.com/peterjreynolds/synapse_local_llm_app/releases/tag/synapse-ai
```

The old `synapse-ai-debug-latest` release/tag may still exist as a legacy
alias, but future automation publishes to `synapse-ai`.

On launch, Synapse checks the public `synapse-ai` release for a newer
`Synapse-AI.apk`. If one exists, the app shows an in-app update banner. Tapping
`Download now` downloads the APK, verifies the release checksum when GitHub
provides one, then opens Android's installer. Android still requires user
approval; the app cannot silently self-update.

Installed data is preserved when all Android package rules match:

- the package name stays the same;
- the APK is signed with the same signing key;
- the new `versionCode` is higher than the installed one.

If those rules fail, Android may show a package conflict or require uninstalling
first. Uninstalling deletes app-private chats, memory, settings, and downloaded
models, so stable signing is required for sane updates.

If the release asset link gives a private-repo `404` on Android, open the
single-APK branch file instead:

```text
https://github.com/peterjreynolds/synapse_local_llm_app/blob/apk-latest/APK/Synapse-AI.apk
```

Do not commit APK binaries to `main`. The `apk-latest` branch is intentionally
force-replaced so the visible download branch contains one APK commit and one
APK file.

For clean Android update installs, configure one GitHub secret so every
GitHub-built debug APK is signed with the same key:

```sh
keytool -genkeypair -v \
  -storetype JKS \
  -keystore synapse-debug.keystore \
  -storepass android \
  -alias androiddebugkey \
  -keypass android \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"

base64 -w0 synapse-debug.keystore
```

Add the base64 output as the GitHub repo secret
`SYNAPSE_DEBUG_KEYSTORE_B64`. Do not commit the keystore file.

## Memory Safety

Synapse memory uses one local truth: Room entities plus durable write/retrieval
receipts. The local LLM may propose memories, but durable memory writes require
source evidence and pass through the admission gate. When storage gets tight,
Synapse pauses memory writes and keeps chat usable.

## Debug Archives

Settings > Diagnostics > `Export Debug ZIP` creates a private troubleshooting
archive that excludes GGUF model weights. It includes raw Room/DataStore state,
readable database summaries, generation timing traces, runtime/model metadata,
UI state, window metrics, and app-state file manifests.
