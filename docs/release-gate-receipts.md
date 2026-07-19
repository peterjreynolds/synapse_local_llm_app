# Synapse Chat Release-Gate Receipts

The canonical machine-readable release state is [`release-gates.json`](../release-gates.json).
A release is authorized only when every required gate is `PASS`, the receipt's
`sourceCommit` identifies the exact app candidate, every supporting check is
`PASS`, and `releaseReady` is `true`. `HEAD` may be a descendant of that
candidate only when every intervening commit changes release-gate metadata;
any app, Firebase, build, or workflow change makes the receipt stale.

Run the guard from the repository root:

```bash
node scripts/ci/verify-release-gates.mjs
```

The guard intentionally exits non-zero while any gate is `FAIL` or `BLOCKED`.
Local compilation and tests are supporting evidence; they do not replace live
service, signing-lineage, or physical-device evidence.

## Current Candidate

Source commit: `c23d0e6f66607643a853ce170830aea096f4ecea`

| Gate | Status | Why |
| --- | --- | --- |
| App Check activation | `BLOCKED` | The Android provider SDK is not installed and enforcement is disabled. |
| Live Firebase acceptance | `BLOCKED` | Emulator/Functions/rules checks are not a live production acceptance receipt. |
| Release signer comparison | `PASS` | Candidate package and signer match the canonical APK, and version code 2029 advances published 2028. |
| Galaxy S9 / API 29 matrix | `BLOCKED` | No Galaxy S9 / API 29 device was visible through repository ADB. |
| Modern API 33+ matrix | `BLOCKED` | No physical API 33+ device was visible through repository ADB. |

Supporting local Android validation is `PASS`: `testDebugUnitTest`,
`ktlintCheck`, `lintDebug`, `assembleDebug`, and `git diff --check` completed
successfully for the candidate.

## Updating A Gate

A gate may be changed to `PASS` only when its evidence was actually collected
for the exact `sourceCommit`. Update its summary and evidence array, remove
completed actions, update `generatedAt`, recompute `releaseReady`, and run the
guard from a clean working tree. Never convert emulator results, a successful
build, or an unperformed manual checklist into a live or physical-device pass.

## Physical-Device Receipt Minimum

Each device receipt must record:

- candidate commit and installed version;
- Android version and API level;
- a redacted, non-reversible device fingerprint;
- in-place update result without app-data clearing;
- launch and retained-account/session result;
- direct and group messaging result;
- attachment, notification, and updater result;
- Cinder unavailable-state behavior until its backend is connected;
- App Check token/sideload behavior after the provider is introduced;
- timestamp and tester attestation.

### Current Device Discovery Receipt

At `2026-07-19T05:19:39Z`, the repository toolchain's ADB `1.0.41`
(platform-tools `37.0.0`) completed three discovery scans. Every scan reported
zero ready devices and zero offline or unauthorized entries; ADB mDNS also
reported zero services. Therefore neither required physical-device class was
available. No APK was installed, no app data was changed, and no launch,
session-retention, messaging, attachment, notification, updater, or Cinder
behavior was claimed. Both physical-device gates remain `BLOCKED` pending the
class-specific actions in `release-gates.json`.

## Live Firebase Receipt Minimum

The live acceptance receipt must distinguish production evidence from emulator
evidence and cover authenticated account access, room/message synchronization,
push delivery, attachment behavior, account-state enforcement, backend revision,
and cleanup-job health. Store only redacted identifiers and aggregate results.

## Signing Receipt Minimum

Record the candidate APK SHA-256, package ID, monotonic version code, signing
certificate SHA-256, current published APK metadata, and the comparison result.
The workflow's expected package is `app.synapse.localllm.debug`; its expected
certificate SHA-256 is
`6f762970e8c29b2c810cb790c1e08dbebf80e40f60a03516b7ca665964a14e7b`.

### Current Signing Receipt

Verified at `2026-07-19T05:15:14Z` for source commit
`c23d0e6f66607643a853ce170830aea096f4ecea`.

| Field | Candidate | Published `synapse-ai` / `apk-latest` |
| --- | --- | --- |
| Package | `app.synapse.localllm.debug` | `app.synapse.localllm.debug` |
| Version | `2029` (`0.1.2029`) | `2028` (`0.1.2028`) |
| APK SHA-256 | `84115fa170ba41cfd57e5353e2a662f89dfc6a6c8f2a262ede57abb29a4431f7` | `15869b99122c103a35702ecb428d1ab4691d406316d96e5b1764ab9420c833cd` |
| APK bytes | `103024754` | `102971554` |
| Signing certificate SHA-256 | `6f762970e8c29b2c810cb790c1e08dbebf80e40f60a03516b7ca665964a14e7b` | `6f762970e8c29b2c810cb790c1e08dbebf80e40f60a03516b7ca665964a14e7b` |

The published release asset digest matches the APK hash recorded by fetched
`origin/apk-latest` head `3d99655e295025e1385f8c5c1f74ef6778a89468`.
The rolling release tag and branch both identify source commit
`56afe5501b6f0c057bbae56fd51f5d8d0050ff3f`. The package and certificate
lineage match, and the candidate version policy is monotonic, so the signer
comparison gate is `PASS`. This receipt does not satisfy any live-service or
physical-device gate.
