# Synapse Private calling

Evidence recorded on 2026-09-20. Backend deployment is verified; this document
does not establish that a new APK has been published. Append the final APK,
signer, version, source commit, and update-metadata receipts after publication.

## Scope and privacy boundary

This is explicit, one-to-one voice/video calling in two-member direct rooms.
Both people consent to direct calling and verify device safety numbers in the
app. Direct connections can expose network addresses to the other participant.
There is no configured TURN relay and some networks will not connect. There is
no background incoming-call delivery: both apps must be open to receive the
initial call. Active calls use a visible foreground notification with Hang up;
there are no remote camera controls or hidden capture modes.

Call SDP and ICE are encrypted per recipient using the existing Signal adapter
before Supabase receives them. Call/room IDs, membership epoch, device addresses,
sequence, expiry, and media kind are bound inside the encrypted payload. Media
uses WebRTC DTLS-SRTP. The backend still sees routing, membership, timing, and
call-state metadata; encryption is not a claim of complete anonymity.

Ringing lasts at most 60 seconds. Accepted calls require both 45-second heartbeat
leases; first-device acceptance is atomic. Local capture stops independently of
a successful network hangup. Terminal calls discard signal ciphertext and device
leases; terminal receipts become unreadable after 120 seconds and are physically
purged by the next running minute job. Hosted pauses or job failures can delay
physical deletion. No guarantee is made about provider backups or peer copies.

## Pinned media dependency and notices

The dependency is `io.github.webrtc-sdk:android:144.7559.15`, not a floating
version. The [published Maven POM](https://repo.maven.apache.org/maven2/io/github/webrtc-sdk/android/144.7559.15/android-144.7559.15.pom)
identifies BSD-3-Clause and the WebRTC SDK Android source repository. The cached
AAR and freshly fetched POM match `gradle/verification-metadata.xml`:

| Artifact | SHA-256 |
| --- | --- |
| `android-144.7559.15.aar` | `fc7c8d027eb33860ccbb7847988f7f942d44a92d1f575348bba6c0250cde4071` |
| `android-144.7559.15.pom` | `9682ba549bd4d7732aa093427f09f597d9251ed7954b3df6a2b470d7cd356cf0` |

The [packaging release commit](https://github.com/webrtc-sdk/android/commit/0c62f604c97087664e412ec04e0ec79721a263b1)
and its [changelog](https://github.com/webrtc-sdk/android/blob/0c62f604c97087664e412ec04e0ec79721a263b1/CHANGES.md)
identify WebRTC source commit
[`30d5e63ae91da483e06577b5c35ee91cc5e5c3db`](https://github.com/webrtc-sdk/webrtc/tree/30d5e63ae91da483e06577b5c35ee91cc5e5c3db).
The packaging repository's MIT license is distinct from WebRTC's BSD license
and its bundled third-party licenses.

The following unmodified files from packaging commit
`0c62f604c97087664e412ec04e0ec79721a263b1` are included in
`privatechat/src/main/assets/licenses/`, with attribution in `NOTICE.txt`:

- `WebRTC-SDK-MIT.txt`: [upstream LICENSE](https://github.com/webrtc-sdk/android/blob/0c62f604c97087664e412ec04e0ec79721a263b1/LICENSE),
  SHA-256 `e6b282fe6c0fb353928923470457f31b44cbab203effd60c0cde4a5bb96c8aec`.
- `WebRTC-THIRD-PARTY.md`: [upstream notice bundle](https://github.com/webrtc-sdk/android/blob/0c62f604c97087664e412ec04e0ec79721a263b1/Licenses/WEBRTC.md),
  SHA-256 `d1f9382c6878ac024155fd6d44a5977329108bb8b0a01cea40e4a2f1d7de252e`.

The AAR itself contains a manifest, classes, and four native ABI libraries, but
no standalone license bundle or complete native component manifest. Source
mapping and notice preservation are verified; a reproducible source-to-binary
build, complete component inventory, and independent native-code security audit
are not. Existing libsignal obligations remain in
[the cryptographic protocol decision](synapse-private-crypto-decision.md).

## Android packaging boundary

The application minimum remains API 25 (Android 7.1), with `arm64-v8a`,
`armeabi-v7a` in production. Debug additionally includes `x86_64` for UI tests;
the upstream WebRTC native library crashes on the API-25 x86_64 emulator. The WebRTC AAR declares minimum API 21; its additional
32-bit `x86` library is excluded from this APK. The exact permitted native entries
are checked by `PrivateChatModuleBoundaryTest` and the private APK workflow:

```text
lib/arm64-v8a/libandroidx.graphics.path.so
lib/arm64-v8a/libsignal_jni.so
lib/arm64-v8a/libjingle_peerconnection_so.so
lib/armeabi-v7a/libandroidx.graphics.path.so
lib/armeabi-v7a/libsignal_jni.so
lib/armeabi-v7a/libjingle_peerconnection_so.so
```

`scripts/ci/verify-synapse-private-jni.mjs` checks packaged Signal and WebRTC JNI
callbacks. `scripts/ci/build-packaged-webrtc-probe.sh` builds an API-25-compatible
probe against the installed distribution APK without opening camera or microphone.
These checks do not substitute for physical-device audio/video acceptance.

## Backend deployment and validation receipts

- Isolated PostgreSQL: **260/260 pgTAP assertions passed**, comprising 205
  existing assertions and 55 calling assertions. The corrected existing tests
  also passed **205/205** before the calling migration. Local Auth/Storage/cron
  facades do not exercise hosted GoTrue, Storage HTTP, or actual scheduling.
- Two real local database connections raced acceptance: first acceptance won,
  the second was rejected, and exactly two active device leases remained.
- Supabase project: `xqifnldqcsgefeisscgu`; hosted migration version
  `20260920055844`, from the local `add_ephemeral_private_calls` migration.
- Release-operator receipt at **2026-09-20T05:58:49Z**: the read-only
  `supabase/tests/verification/verify-private-calls.sql` returned
  `all_checks_pass=true` for six public/private RPC boundaries, grants, six
  deny-all private RLS tables, and the minute purge registration.
- First hosted purge execution at **2026-09-20T05:59:00Z** succeeded. An anonymous
  `poll_private_calls` HTTP request returned **401**.
- The hosted security-advisor review reported only the preexisting disabled
  leaked-password-protection warning and expected deny-all RLS informational
  findings. This is a scoped advisor result, not a comprehensive security audit.

Real-phone microphone, speaker, camera, cross-network connectivity, and a
two-person call have **not** been verified by these backend receipts. TURN,
background incoming delivery, and production end-to-end calling acceptance
remain outside the completed proof. No APK publication receipt is recorded here
yet.

## Current-main integration (2026-10-04)

Restored the existing calling implementation alongside the People directory and
connection diagnostics, preserving the current navigation. Updated the media pin
to 144.7559.15 and verified its upstream notice files are byte-identical to the
bundled notices. Production builds retain the prior ARM-only compatibility fix.

Validation: 303 JVM tests passed; ktlint, Android lint, debug APK and Android UI-test
APK builds passed. All 55 calling pgTAP assertions and seven JNI-verifier tests
passed. Hosted read-only verification at 2026-10-04T23:22:05Z returned
`all_checks_pass=true`; the existing calling migration was not reapplied.
No physical device or emulator is attached in this workspace. UI instrumentation
execution and actual two-phone media acceptance are still required; compiling
those tests is not a claim that they ran.
