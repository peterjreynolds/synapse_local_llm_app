# Synapse Local LLM App

Synapse is a native Android chat UI for a phone-local `llama.cpp` model. V1 talks to `llama-server` on `127.0.0.1:8080`, can ask Termux to start that server, and keeps chat plus evidence-backed memory in app-local storage.

## V1 Runtime

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

This machine currently needs a JDK and Android SDK before Gradle can build the APK.

Once installed:

```sh
./gradlew test
./gradlew assembleDebug
```

The debug APK will be under `app/build/outputs/apk/debug/`.

## Memory Safety

Synapse memory uses one local truth: Room entities plus durable write/retrieval receipts. The local LLM may propose memories, but durable memory writes require source evidence and pass through the admission gate. When storage gets tight, Synapse pauses memory writes and keeps chat usable.

