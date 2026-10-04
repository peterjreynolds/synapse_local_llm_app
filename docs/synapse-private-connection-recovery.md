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
