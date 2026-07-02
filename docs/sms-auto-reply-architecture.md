# SMS Auto-Reply Architecture

Status: implemented opt-in background automation

Synapse can automatically reply to inbound SMS when the user enables
`Settings > SMS auto-reply`. This is an explicit autonomous outbound path:
incoming SMS text is submitted to the same local LLM turn coordinator used by
normal chat, then the finalized assistant message is queued back to the sender
through Android `SmsManager`.

## Boundaries

- Android SMS broadcasts are external input.
- Android `SmsManager` is the outbound transport boundary.
- SMS auto-reply does not poke the composer UI.
- SMS turns reuse `SynapseTurnCoordinator` so prompt formatting, generation
  diagnostics, output filtering, and chat persistence remain centralized.
  The SMS receipt is linked to the chat turn as soon as the user/assistant
  message ids exist, before model generation starts.
- SMS turns disable memory writes because inbound SMS is third-party input, not
  a deliberate owner memory command.
- SMS turns use the currently active Synapse runtime/model settings.
- SMS style is controlled by the normal Persona/Custom Instructions plus the
  optional `SMS Auto-Reply Instructions` setting. Synapse does not impose a
  separate assistant persona for SMS.
- Replies are automatic when the toggle and Android permissions are enabled.

## Receipts

SMS auto-reply writes durable Room receipts with:

- inbound message key;
- sender address;
- inbound body hash and character count;
- linked chat thread, user message, and assistant message ids when available;
- reply hash and character count when queued;
- SMS part count;
- final state and failure reason.

Receipts intentionally avoid duplicating full SMS body text. The chat turn owns
the message content; the receipt owns mutation evidence and correlation.

## Runtime Flow

1. `SmsAutoReplyReceiver` receives `SMS_RECEIVED` or `SMS_DELIVER`.
2. `AndroidInboundSmsParser` normalizes sender, body, timestamp, and key.
3. `SmsAutoReplyForegroundService` starts a remote-messaging foreground service.
4. The service loads persisted settings from `SynapseSettingsStore`.
5. `SmsAutoReplyCoordinator` deduplicates the inbound message key.
6. The coordinator reuses or creates a per-sender chat thread.
7. The inbound SMS becomes a normal persisted user turn.
8. The coordinator links the SMS receipt to the chat turn before generation.
9. The coordinator adds only a transport contract, applies the owner's optional
   SMS instructions, and disables memory writes for that turn.
10. On completed generation, `AndroidSmsOutboundGateway` queues the SMS reply.
11. Room receipts record queued or failed outcome.

If Android redelivers the same inbound SMS key after an interrupted attempt,
the coordinator retries receipts left in generation-retryable states:
`GENERATING`, `GENERATION_FAILED`, `EMPTY_REPLY_REJECTED`, and
`SMS_QUEUE_FAILED`. Terminal receipts such as `SMS_QUEUED`,
`AUTO_REPLY_DISABLED`, and `INVALID_INBOUND_MESSAGE` are returned without a
second send.

## Startup Cleanup

When Synapse reopens, normal chat cleanup marks stale streaming assistant
messages as failed. Recent SMS auto-reply turns are excluded from that cleanup
so opening the app does not kill an active foreground SMS reply. Generating SMS
receipts older than the startup grace window are marked failed so receipts do
not stay active forever after a real process interruption.

## Current Limits

- The first outbound receipt means Android accepted the queue request. Carrier
  sent/delivered callbacks are not yet tracked.
- Contact-name lookup is not implemented; threads are keyed by sender address.
- MMS/RCS/third-party messengers are not part of this path.
- Android still requires the user to grant `RECEIVE_SMS` and `SEND_SMS`.
