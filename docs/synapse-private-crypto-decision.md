# Synapse Private Cryptographic Protocol Decision

Status: accepted for implementation; release remains blocked on the acceptance
proof in `synapse-private-security-contract.md`.

## Decision

Synapse Private uses Signal's `libsignal` Java/Android artifacts at the exact
version `0.101.0` for device identity keys, pre-key session establishment,
post-quantum pre-keys, Double Ratchet sessions, replay detection, and numeric
safety numbers.

The app sends a distinct Signal Protocol envelope to every active recipient
device. A group message initially uses the same pairwise fan-out rather than a
shared group secret. This is less bandwidth-efficient, but it keeps removed
devices out of later messages without introducing a custom group protocol.
Sender-key groups may replace fan-out only after membership-epoch rotation and
removal tests exist.

Signal protocol state is durable and encrypted under an Android Keystore key.
The transport database receives only public pre-key material and opaque
versioned message envelopes. An unexpected remote identity-key replacement
blocks sending until the user explicitly accepts and re-verifies it.

## Pinned Supply Chain

- Source repository: `https://github.com/signalapp/libsignal`
- Source tag: `v0.101.0`
- Peeled source commit: `b056faa6dd02961cff24064c54c089c52e1a0753`
- Maven repository: `https://build-artifacts.signal.org/libraries/maven/`
- `org.signal:libsignal-client:0.101.0` JAR SHA-256:
  `40c8edaa7e178a8b1610ac6c2c20f2f936c53791949468f77ea4b1af3a64a68f`
- `org.signal:libsignal-android:0.101.0` AAR SHA-256:
  `7034a7ae986153c2261775f43be88edbe8d46cf364b4bc0df08a63fc9a1e389ac`

Gradle dependency verification must pin the resolved artifacts and their
transitive dependencies before release. Upgrades are explicit security changes,
not automatic version drift.

## License And Support Boundary

`libsignal` is AGPL-3.0 and its maintainers state that use outside Signal is
unsupported and APIs may change without notice. Synapse Private therefore:

- distributes its complete corresponding source and build scripts from the
  public source tag identified beside every APK;
- includes the AGPL text, Signal copyright attribution, source URL, build
  commit, and no-warranty notice in the app;
- does not imply endorsement by Signal or use Signal trademarks as branding;
- keeps the protocol behind a narrow adapter so a pinned upgrade is reviewable;
- treats a closed-source or store-restricted distribution as blocked pending a
  separate license review.

This is an engineering compliance record, not legal advice.

## Rejected Alternatives

- A home-grown X25519/AES message scheme was rejected because cryptographic
  primitives alone do not supply a reviewed asynchronous messaging protocol,
  identity-change handling, forward secrecy, or replay protection.
- The archived `libsignal-protocol-java` library was rejected because it is no
  longer maintained and does not provide the current post-quantum session path.
- Matrix E2EE was rejected for this product boundary because it requires a
  Matrix homeserver/client architecture instead of the selected Supabase
  transport.
- Server-side or shared room encryption keys were rejected because the server
  or a removed room member could retain the key and decrypt later content.

## Release Evidence

No UI may say that chats are end-to-end encrypted until tests demonstrate:

1. independent devices establish and persist sessions from validated pre-key
   bundles;
2. initial pre-key messages and later ratcheted messages decrypt in and out of
   order, while replays fail;
3. identity-key replacement fails closed and changes the safety number;
4. non-members, revoked devices, Supabase, logs, and APK resources contain no
   plaintext or private key material;
5. expiry removes every encrypted envelope and destroys locally retained
   decryption state required only by that expired content.
