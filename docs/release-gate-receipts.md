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
| Release signer comparison | `BLOCKED` | The candidate signer has not been freshly compared with the canonical rolling release lineage. |
| Galaxy S9 / API 29 matrix | `BLOCKED` | No physical sideload and in-place update receipt exists for this candidate. |
| Modern API 33+ matrix | `BLOCKED` | No physical sideload and in-place update receipt exists for this candidate. |

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
