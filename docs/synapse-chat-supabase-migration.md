# Synapse Chat interface on the Supabase messenger

## Ownership and isolation

Work starts from `c8e582ccfd4feedfea995251c333de10861c9f33` on
`codex/synapse-chat-supabase-migration`. The original `:app` stays untouched.
The implementation target is the existing human-only `:privatechat` module,
not a third application or another backend. Its package remains
`app.synapse.privatechat`; the original app's installed identity is not reused.

The original `RemoteSignedInShell` in `RemoteChatApp.kt` supplies the familiar
interaction pattern: a hamburger menu, signed-in identity, and a chat-first
home screen. Only that presentation pattern is adapted. Its Firebase adapters,
application graph, local inference, and assistant participants are not imported.
The existing Supabase gateways, session verification, encrypted message transport,
invitation receipts, Android 7.1 compatibility, and update verification stay owned
by `:privatechat`.

## First slice: navigation

- A menu exposes Chats, New conversation, Profile & privacy, and Invite a friend.
- The home screen has a Synapse Chat heading, signed-in username, a direct profile
  shortcut, search, Unread/Archived filters, and a labeled New chat button.
- New conversation opens the existing direct/group/join chooser. It does not
  bypass the connected-transport gate or create a room without a confirmed receipt.
- Invite a friend requests an existing one-use **account** invitation. Conversation
  invitations remain in the conversation menu; these are different capabilities.
- Inviting is disabled until the profile is available and while another invitation
  request is running. The server remains the authorization owner.
- Back dismisses the menu first. Existing profile, conversation, and narrow/wide
  layouts retain their own navigation behavior. Search stays in memory only.

`PrivateChatNavigationDrawer` owns menu state and destination dispatch.
`PrivateRoomListPane` owns the chat-list presentation. `PrivateChatScreen` still
owns phone/tablet composition and overlays. No auth, transport, persistence,
cryptography, or database boundary is changed in this slice.

## Remaining parity work

| Area | State and next boundary |
| --- | --- |
| Accounts, invites, profiles, DMs and groups | Existing Supabase implementation retained; new menu uses its current actions. |
| Timers, reactions, replies, edits, deletion, archive/pin/mute | Existing encrypted chat implementation retained; not reimplemented in the drawer. |
| Presence, typing, read activity | Existing optional short-lived activity controls retained in Profile & privacy. |
| Calls, ringing, ringtones, video preview | Original code is donor material only. Supabase signaling, identity binding, permissions, media lifecycle, and device tests are still required. No call controls are presented as working. |
| Attachments and voice messages | Need encrypted upload/download and expiration contracts before UI enablement. |
| Owner device camera/mic sharing | Not implemented here. Requires explicit opt-in, authenticated/revocable device access, visible sensor status and Stop controls. No hidden capture or force-stop bypass. |
| Release | Version 0.1.2045 (code 2045) publishes this navigation through the existing Private APK workflow. Source and artifact receipts are recorded below. |

Calling must respect `synapse-private-security-contract.md`. Its current relay-only
privacy policy is not weakened by this UI work. An opt-in direct calling mode,
if approved and implemented, needs explicit peer-IP disclosure to both callers
and its own reviewed contract. No Raspberry Pi or other relay is configured.

The next calling slice should preserve the separation already visible in the donor
contracts: `RemoteDirectCallGateway` owns call state/signaling,
`DirectCallMediaGateway` owns local preview and media tracks, and
`DirectCallAlertGateway` owns incoming ringtone/outgoing ringback and their stop
operation. `DirectCallRingtoneRepository` separately owns ringtone selection and
persistence receipts. Their original account/room identifiers must not cross into
the private module. Supabase signaling needs its own authenticated, encrypted
contract rather than a copied Firebase gateway. Incoming calls while backgrounded
also need an explicit delivery design; a foreground-only demonstration is not
background calling support.

## Validation

Required module gates:

```bash
./gradlew :privatechat:test :privatechat:ktlintCheck :privatechat:lintDebug \
  :privatechat:assembleDebug :privatechat:assembleDebugAndroidTest
```

`PrivateChatNavigationTest` exercises the composed chat screen with local UI
fixtures, not real accounts: profile/back/search preservation, both new-chat entry
points, invitation in-flight/refusal handling, unavailable profile, drawer Back,
and conversation Back. Run it on an Android device with:

```bash
./gradlew :privatechat:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.synapse.privatechat.ui.chat.PrivateChatNavigationTest
```

Keep the existing minified libsignal JNI checks when preparing a distribution
build; see `synapse-private-release-encryption-regression.md`. Navigation test
success is not proof of a live two-phone send, calling, or a published release.

### Local receipt, 2026-09-20

- Required unit/style/lint/debug gates: passed; 233 JVM tests, zero failures/errors.
- Final API 25 emulator navigation run: `OK (6 tests)` in 80.274 seconds, covering
  the composed phone layout. Tests use local account/chat fixtures, not live traffic.
- Debug instrumentation APK and minified rolling APK: assembled successfully.
- Release metadata/version/retry/JNI verifier tests: 21 passed.
- Final minified APK: all six expected libsignal native callback descriptors verified.
- Dependency verification: 22 added test-artifact hashes independently matched
  Google Maven/Maven Central; all 1,382 existing artifact hashes stayed unchanged.
- Local rolling APK SHA-256:
  `f47a52f463d394ebf95f167f3958b4be7fbff68c10910e0b5d8a757fec2e8585`.
  This is a development build with the default code 2031, **not a published update**.
- CI now builds the instrumentation APK and runs the navigation suite on API 25.
  CI execution and publication are distinct from the local checks above.

### Published release receipt, 2026-09-20

The initial hosted navigation run failed before Gradle because
`android-actions/setup-android@v3` requested the retired SDK package `tools`.
Both Private workflows now request `platform-tools` explicitly. The required
Android and publication gates remain in place.

- Source commit and rolling release tag:
  `3305614acd3dc1ca6ceaedb000b35a91278ff568`.
- [Hosted recovery checks](https://github.com/peterjreynolds/synapse_local_llm_app/actions/runs/35489691045):
  passed, including unit/style/lint/debug gates, six API 25 navigation tests,
  minified assembly, and the literal libsignal JNI callback checks.
- [Private publication workflow](https://github.com/peterjreynolds/synapse_local_llm_app/actions/runs/35489710133):
  passed, including release contracts, unit/style/lint gates, minified assembly,
  JNI/package/signature/ABI checks, publication, and release receipt readback.
- Version: `0.1.2045`, code `2045`; package `app.synapse.privatechat`;
  minimum Android API `25`; ABIs `arm64-v8a`, `armeabi-v7a`, `x86_64`.
- [Public APK](https://github.com/peterjreynolds/synapse_local_llm_app/releases/download/synapse-private/Synapse-Private.apk):
  27,273,564 bytes; SHA-256
  `9a0bebb88268b86cbec9d2f2f87060cd327304e6f475944229b31dee7600b006`.
- [Update metadata](https://github.com/peterjreynolds/synapse_local_llm_app/releases/download/synapse-private/Synapse-Private-update.json):
  SHA-256 `4719a32e52f1f9c1eba7fb76a20f82063ba6b170fd987c126b7cee4a38028066`.
- Signing certificate SHA-256:
  `6f762970e8c29b2c810cb790c1e08dbebf80e40f60a03516b7ca665964a14e7b`.
- An independent anonymous download matched the GitHub asset digests and update
  metadata. `aapt` confirmed the package/version/API/ABI values, and `apksigner`
  verified the downloaded APK for API 25 with warnings treated as errors.
- The exact public APK was installed on an isolated Android 7.1.1/API 25
  x86_64 emulator. The standalone packaged Signal probe reported
  `PASS: packaged JNI reached loadSession and rejected the absent session` and
  `INSTRUMENTATION_CODE: -1`. This exercises the minified native callback that
  caused the earlier immediate-send failure; it is not a full live-message test.

This release adds navigation, not calling or attachments. Release notes now
explicitly say that voice and video calls are not implemented. Hosted navigation
fixtures are not live two-phone message or call tests.
