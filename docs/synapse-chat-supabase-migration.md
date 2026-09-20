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
| Release | No rolling release or update metadata is changed by this slice. A new version requires the existing publication workflow and artifact/signature receipts. |

Calling must respect `synapse-private-security-contract.md`. Its current relay-only
privacy policy is not weakened by this UI work. A separate owner-device direct-first
mode, if implemented, needs explicit peer-IP disclosure and its own reviewed contract.
A Raspberry Pi is the proposed relay host, not a configured or verified deployment.

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
