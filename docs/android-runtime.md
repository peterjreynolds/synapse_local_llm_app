# Android Runtime Notes

Synapse V1 talks to a local `llama-server` over loopback HTTP:

```sh
cd ~/llama.cpp/build
./bin/llama-server -m ../hardcore.gguf -c 2048 -t 6 --host 127.0.0.1 --port 8080 > /dev/null 2>&1 &
```

The app checks readiness with `GET /v1/models` and streams chat through `POST /v1/chat/completions`.

## Termux Startup

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

## Future Embedded Runtime

The runtime boundary is `LocalInferenceRuntime`. A future embedded `llama.cpp`
adapter should implement that interface without changing chat UI, memory
admission, or prompt assembly.
