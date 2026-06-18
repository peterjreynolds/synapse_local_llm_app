# Voice Mode Architecture

Status: first hands-free loop foundation

Synapse now has an explicit Voice Mode state machine for push-to-start,
hands-free turn taking. This is separate from per-message speaker playback and
does not add always-listening wake-word behavior.

## Current Flow

1. User taps Voice Mode in the composer area.
2. ViewModel moves Voice Mode to `LISTENING` and increments a recognition
   request id.
3. Compose observes the request and launches Android system speech recognition.
4. Android speech recognition handles endpointing/silence detection and returns
   a transcript.
5. ViewModel sends the transcript through the normal chat turn path.
6. Synapse generates a normal persisted assistant message.
7. ViewModel fetches the completed assistant text and moves Voice Mode to
   `SPEAKING`.
8. Compose speaks the response using a Voice Mode TextToSpeech controller.
9. When TTS finishes, ViewModel returns to `LISTENING` and requests the next
   recognition pass if Voice Mode is still active.

## States

- `OFF`: no listening or playback is active.
- `LISTENING`: waiting for one Android speech recognition result.
- `PROCESSING`: transcript was submitted through the normal chat path.
- `SPEAKING`: assistant reply is being read aloud.
- `ERROR`: loop is paused after a recognition, generation, or playback error.

## Boundaries

- Voice Mode uses the existing chat pipeline. Voice transcripts are not written
  to a separate memory path.
- The app never listens while Voice Mode TTS is speaking.
- Recognition errors pause the loop instead of retrying forever.
- Stop Voice cancels any active Voice Mode turn and returns the state to `OFF`.
- Per-message speaker playback remains separate.

## Current Android Adapter

The first implementation uses Android `RecognizerIntent` for speech recognition
and Android `TextToSpeech` for playback. `RecognizerIntent` provides the current
silence/endpoint detection. This is not yet a fully offline STT engine and does
not yet expose selectable voice models.

## Remaining Work

1. Replace or supplement Android speech recognition with a fully offline STT
   engine when practical.
2. Add selectable assistant voices, speed, pitch, and test playback controls.
3. Add interruption controls for barge-in while Synapse is speaking.
4. Add richer diagnostics for recognition/playback failures.
5. Add manual S25 Ultra QA for repeated long Voice Mode sessions.
