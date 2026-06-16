# Android Runtime Notes

Synapse has two runtime backends behind `LocalInferenceRuntime`.

## Embedded llama.cpp

The default backend is embedded `llama.cpp`. The APK builds ARM64 native
libraries from the pinned `third_party/llama.cpp` submodule and loads them
through `EmbeddedLlamaRuntime`.

The APK does not package GGUF weights. A model is imported from Android's
document picker, validated by GGUF magic bytes, copied into app-private
`files/models`, and persisted in settings as a filesystem path. This avoids
giant APKs and keeps model files out of git history.

The embedded adapter resets prompt state for each Synapse turn, decodes the
system prompt plus assembled chat/memory prompt, and streams generated tokens
back through `ChatStreamEvent.Token`.

Current embedded limits:

- `arm64-v8a` only. This is intentional for the Samsung S25 Ultra target.
- Text-only generation. Vision `mmproj` wiring is not implemented yet.
- Context is fixed at 4096 tokens in the native wrapper.
- Default chat responses use a smaller token budget for phone responsiveness;
  users can raise the setting when they need long answers.
- Generation traces record prompt size, token/event counts, visible/filtered
  output counts, first-token timing, and stop reason for debug exports.
- The first model load can take a while and Android may kill the process under
  heavy thermal or memory pressure.

## Termux Server Runtime

The fallback server backend talks to a local `llama-server` over loopback HTTP:

```sh
cd ~/llama.cpp/build
./bin/llama-server -m ../hardcore.gguf -c 2048 -t 6 --host 127.0.0.1 --port 8080 > /dev/null 2>&1 &
```

The app checks readiness with `GET /v1/models` and streams chat through `POST /v1/chat/completions`.

### Termux Startup

Synapse sends a Termux RUN_COMMAND intent through `TermuxCommandGateway`. The app
declares `com.termux.permission.RUN_COMMAND`, and the phone must also allow
external commands in Termux:

```properties
allow-external-apps = true
```

Startup is receipt-based:

- `SENT_TO_TERMUX` means Android accepted the command intent.
- `TERMUX_UNAVAILABLE` means Termux is not installed or not visible.
- `TERMUX_PERMISSION_MISSING` means Android denied the custom Termux permission.
- `FAILED` means Android or Termux rejected the service start.
