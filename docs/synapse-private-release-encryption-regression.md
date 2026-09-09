# Synapse Private: immediate-send packaging regression

## Cause and scope

The published 0.1.2043 APK renamed
`org.signal.libsignal.internal.NativeHandleGuard$Owner` to `j7.o`.
libsignal 0.101.0's native library still resolves callbacks using the literal
descriptor `(J)Lorg/signal/libsignal/internal/NativeHandleGuard$Owner;`.
R8 preserved `SessionStore.loadSession` but changed its return descriptor.
Consequently the first native session lookup fails before a message is sent.
The chat gateway catches that exception as `TransportUnavailable`, which causes
the misleading reconnect notice even when ordinary HTTP requests succeed.

Debug tests did not expose this because the debug APK is not shrunk. A room with
one user can still exercise peer encryption when that user has a second device.
Presence is not a condition for sending encrypted envelopes to registered devices.

The fix preserves only this one native descriptor interface. It does not change
accounts, authentication, database access, local keys, or message retention.
No Supabase deployment or user-data reset is required.

## Reproduction receipt

The GitHub-distributed 2043 APK has SHA-256
`456ed232b3b679f8c9bbac1ff711b3430ce846d9876c22265ebd5af39e51ed2f`.
Inspection of that actual APK, without a deobfuscation mapping, returns:

```text
.method public abstract loadSession(J)Lj7/o;
```

On Android 7.1 / API 25, the independent packaged-APK probe invokes
`Native.SessionCipher_EncryptMessage` with a deliberately absent peer session.
For 2043 it fails before entering the Java store callback:

```text
JNI never reached loadSession: java.lang.RuntimeException:
error while invoking an ffi callback: JNI error Method not found:
loadSession (J)Lorg/signal/libsignal/internal/NativeHandleGuard$Owner;
```

The rebuilt 0.1.2044 APK passed that same probe on API 25: JNI reached the callback
and received the expected `NoSessionException` (`INSTRUMENTATION_CODE: -1`).
It does not send traffic, touch persisted app state, or claim
successful message delivery. The separate `SignalSameAccountDevicesTest` exercises
real encrypted sends and decryption between two devices of one account, including
exhausted one-time prekeys and messages queued while the other device is offline.

Live backend checks also verified authenticated recipient lookup, exhausted-key
lookup, and send receipts in explicitly rolled-back transactions. No diagnostic
message or consumed prekey was retained. These database checks do not substitute
for a physical two-device delivery test.

## Repeatable validation

Run the required module gates and build the minified APK:

```bash
./gradlew :privatechat:test :privatechat:ktlintCheck :privatechat:lintDebug \
  :privatechat:assembleDebug :privatechat:assembleRolling
node --test scripts/ci/verify-synapse-private-jni.test.mjs
node scripts/ci/verify-synapse-private-jni.mjs \
  privatechat/build/outputs/apk/rolling/privatechat-rolling.apk \
  "$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer"
```

Both the PR checks and the APK publication workflow inspect six literal native
method descriptors in the resulting APK. They fail before publication if shrinking
renames/removes the interface or changes a callback signature.

For an on-device proof, install the distribution APK unchanged, then build the
independent probe with `bash scripts/ci/build-packaged-signal-probe.sh` and install
the APK path it prints. The probe must be signed with the same certificate as the
target APK; the script uses the repository's existing debug-key signing convention.
Run it with:

```bash
adb shell am instrument -w \
  app.synapse.privatechat.packagedprobe/app.synapse.privatechat.data.chat.PackagedSignalProbe
```

Require `INSTRUMENTATION_CODE: -1` and a `PASS` receipt. Android's shell command exit
status alone does not prove an instrumentation test passed. This Java-only probe
avoids depending on Kotlin/AndroidX names that may be obfuscated in the actual
distribution APK; a normal debug instrumentation APK cannot safely make that
assumption when testing a separately built minified target.
