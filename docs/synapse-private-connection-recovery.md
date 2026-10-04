# Synapse Private connection recovery

The released client treated any unreadable historical envelope as an outage for
all chats and the profile. Production read-only checks on 2026-10-04 found one
active-device/message pair with no recipient envelope, alongside successful HTTP
200 chat reads. Membership can expose routing rows from before a device joined;
that does not give the new device a key for those messages. The restored
visibility filtering from commit 02c812a excludes that inaccessible history and
keeps strict envelope validation for the messages actually presented. This does
not fabricate plaintext or recover history onto a new device.

Profile polling now reads profiles/devices/presence independently. It never
replays a pending send or decrypts chat history before showing profile settings.

Temporary access-token expiry denies cache reads/writes without erasing the
Keystore-encrypted payload cache. Signal envelopes already consumed cannot safely
be decrypted again after renewal. Account mismatch, explicit sign-out, rejected
refresh/revocation, authoritative deletion and content expiry retain their purge
boundaries. Token expiry is not session revocation. This change prevents future
loss; it cannot recreate payloads already erased by an older client.

The Save error log action is available outside the profile and account gates.
It writes a bounded process-local snapshot through Android's document picker.
The report includes build/API version, timestamps, HTTP status, exception class,
and six code frames; it excludes exception messages/causes, payloads, request
paths, credentials, account IDs, message text and signaling contents. Nothing is
automatically uploaded. Reproduce the error and save the report before closing
the app process. The UI reports success only after the destination stream closes.

Regression coverage includes inaccessible historical envelopes, profile loading
without encrypted-history requests, token renewal with retained encrypted cache,
explicit invalidation, report bounds, and sensitive-data exclusion.

Validation of the initial repair slice: 257 JVM tests, zero failures/errors/skips;
`:privatechat:test :privatechat:ktlintCheck :privatechat:lintDebug
:privatechat:assembleDebug` passed with JDK 21 / SDK 36. Real-phone delivery is
still a separate acceptance check; no production deployment is implied here.

## Session retries, live directory, and background connection

The account UI now retries a transport-unavailable restore/refresh after 5, 10,
20, then at most 30 seconds. Bringing the app forward retries immediately.
Authorization, identity-verification, and local-vault failures do not become
unbounded permissive retries.

A private Supabase Realtime channel carries directory-invalidated notifications.
It contains no account, profile, device, or message payload. The existing bound
RPC reloads the directory after a notification; ten-second polling remains the
explicit recovery path if WebSocket delivery fails. Channel joins and heartbeats
have timeouts, rotated sessions reconnect, and pending invalidations are
conflated. Active leases still expire on the server after 60 seconds; losing
connectivity never makes an old Active badge permanent.

The user can enable **Stay connected in background**. A visible Android remote
messaging foreground service retrieves pending encrypted deliveries and renews
activity while the UI is backgrounded. Its notification has Stop. It does not
capture audio/video, publish read receipts, boot-start, or silently restart after
Android stops the process. It requires notifications so its operation remains
visible. Android force-stop or process termination ends this mode. Incoming calls
still require the app's calling UI to be open; background chat reception is not
background incoming-call delivery.

The composition root is now process-scoped so the Activity and connection service
share one session vault, Signal adapter, encrypted cache, and polling repository.
Foreground and background heartbeat publication have explicit lifecycle owners.
Backend acceptance remains authoritative: neither an Active badge nor a local
send attempt claims that a message was accepted.

Connection-slice validation: 311 JVM tests, ktlint, Android lint, debug and UI-test
APK compilation passed. Local database assertions passed: 11 private directory
notification checks, 47 existing directory/activity checks, and 8 inactive-recipient
send/retrieval checks. The latter verify server-side storage/authorization using
synthetic ciphertext, not a physical two-device decryption run.

Authenticated Supabase deployment receipt: `private_directory_realtime`, project
`xqifnldqcsgefeisscgu`, hosted version `20261004233317`. Read-only verification
confirmed the enabled trigger, authenticated-only receive policy, anonymous denial,
and absence of a client publish policy. No directory activity occurred during the
immediate observation window, so hosted notification delivery remains unobserved.
Post-deployment advisors were unchanged: existing deny-all RLS information and the
preexisting [leaked-password protection warning](https://supabase.com/docs/guides/auth/password-security#password-strength-and-leaked-password-protection).

## Previously erased history

For devices affected by the old cache-erasure behavior, a consumed Signal envelope
or an explicitly erased local envelope key now contributes to a visible unavailable
history count. It no longer prevents current chats from loading. This does not
recover deleted plaintext. Invalid ciphertext, identity changes, inconsistent
routing/context, and damaged vault errors still fail closed. Graph authorization
and envelope cardinality validation run before this narrow history classification.

History-slice validation: 315 JVM tests with no failures/errors/skips; ktlint,
Android lint, debug build and minified rolling build passed. Regression tests
cover erased keys, consumed envelopes, continued healthy payload decoding, and
rejection of all other Signal failure kinds.

Visible room-feed and conversation observers now stop when their UI leaves the
foreground and resume on return. Only the explicit background owner retrieves
messages while hidden. This prevents a previously selected conversation from
publishing read acknowledgments merely because the background service keeps the
process alive. The lifecycle regression checks subscription cancellation and
resubscription; all 315 JVM tests, ktlint, Android lint and debug build passed.
