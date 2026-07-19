# Synapse Chat

Synapse Chat is a native Android room-based chat app with a phone-local
`llama.cpp` AI member. The app can run an embedded ARM64 `llama.cpp` runtime
directly in the APK, or fall back to a Termux `llama-server` on
`127.0.0.1:8080`. Rooms, messages, participant profiles, memberships, and
evidence-backed memory stay in app-local storage.

The active product roadmap is tracked in
[`docs/canonical-master-plan.md`](docs/canonical-master-plan.md).
The remote security, App Check, deletion, retention, and operations boundary is
recorded in [`docs/security-and-operations.md`](docs/security-and-operations.md).

## Local Rooms And Members

Phase 1 promotes the original one-user/one-assistant thread into a durable
local room model without creating a second app or replacing existing chat data.

- `AI_CHAT` rooms keep the original automatic local-AI conversation behavior.
- `DIRECT` rooms bind the local owner to one placeholder human identity. If
  that peer leaves, the history remains bound to them; start a new direct room
  for a different person.
- `GROUP` rooms contain the local owner and one or more placeholder human
  members.
- Synapse can be added to direct and group rooms as a first-class local-AI
  member.
- Every message resolves an explicit participant author. The legacy
  user/assistant/system role remains compatibility metadata rather than the
  source of identity.
- Room membership owns posting permission, joined/left state, and Synapse's AI
  response policy.

Synapse responds automatically in an AI chat. In a direct or group room it
responds only when `@Synapse` is present or the room's explicit automatic
response toggle is enabled. A human-only room never starts local inference and
does not create an empty assistant bubble.

The room drawer shows room kind and member summary, the create-room flow can add
placeholder humans and Synapse, the member sheet can add/remove Synapse or
change its response policy, and multi-participant message bubbles show the
resolved sender. See
[`docs/synapse-chat-room-architecture.md`](docs/synapse-chat-room-architecture.md)
for the persistence and routing contract.

## Standalone Boundary

This repository remains one standalone Android application. Its Room database,
local model runtime, memory, SMS receipts, and UI are app-owned. It does not
depend on OpenClaw, Wingman, a Synapse governance runtime, or an external
sidecar. The product name “Synapse” does not activate historical Synapse
governance infrastructure.

## Remote Chat Security Boundary

Remote people, direct rooms, groups, and rich messages use Firebase
Authentication, callable Cloud Functions, Firestore, Cloud Storage, and FCM.
Membership and message mutations are authorized server-side, while an
account-scoped Room cache supports offline display and an idempotent send
outbox. Removed room access is reconciled into the local cache when the
authoritative room list next synchronizes.

Remote chat is not end-to-end encrypted. Firebase and authorized project
operators remain inside the data trust boundary, even though transport and
provider-managed storage encryption are used. Push payloads carry routing
identifiers rather than plaintext message bodies. The phone-local AI rooms and
memory system remain app-local and are a separate boundary from remote chat.

Cinder now appears as an app-owned assistant conversation in the normal remote
chat list and uses the normal thread, composer, Room cache, and outbox. No
authenticated Cinder backend is configured yet, so sends fail with an explicit
not-connected state instead of fabricating replies. The required server
contract is recorded in
[`docs/cinder-conversation-integration.md`](docs/cinder-conversation-integration.md).

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

The rolling APK currently preserves the early side-loaded package id
`app.synapse.localllm.debug` so existing installs can update in place. Do not
change that package id without a deliberate migration plan, because Android
treats a package-id change as a different app and app-private chats, memory,
settings, and downloaded models will not carry over automatically.

The visible product name is now Synapse Chat, but Phase 1 intentionally leaves
the Android namespace, debug application ID, signing lineage, release tag, and
`Synapse-AI.apk` update asset unchanged.

If those rules fail, Android may show a package conflict or require uninstalling
first. Uninstalling deletes app-private chats, memory, settings, and downloaded
models, so stable signing is required for sane updates.

Each rolling release note includes the package id and APK signing-certificate
SHA-256 fingerprint. If Android refuses an update, compare that fingerprint with
the installed APK's signer. A mismatch cannot be repaired by app code; the next
APK must be signed with the original key or the old app must be uninstalled.
Current rolling builds are expected to use signing-certificate SHA-256
`6f762970e8c29b2c810cb790c1e08dbebf80e40f60a03516b7ca665964a14e7b`.

If the release asset link gives a private-repo `404` on Android, open the
single-APK branch file instead:

```text
https://github.com/peterjreynolds/synapse_local_llm_app/blob/apk-latest/APK/Synapse-AI.apk
```

Do not commit APK binaries to `main`. The `apk-latest` branch is intentionally
force-replaced so the visible download branch contains one APK commit and one
APK file.

For clean Android update installs, configure one GitHub secret so every
GitHub-built APK is signed with the same key:

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
archive with bounded app version, runtime state, storage health, UI counts,
window metrics, and Room/DataStore aggregate counts. It excludes raw app state,
chat and memory content, prompts, SMS and account data, credentials, tokens,
private filesystem paths, and GGUF model weights. Review the archive before
sharing it because aggregate usage metadata can still be sensitive. AI routing
returns an explicit decision reason, and generation diagnostics are created only
when an AI response actually starts.
