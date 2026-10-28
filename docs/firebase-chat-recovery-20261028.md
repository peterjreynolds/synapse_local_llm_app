# Firebase Synapse Chat recovery — 2026-10-28

Status: **in progress; release acceptance blocked**. Owner: Peter J. Reynolds.
Execution: Codex. This report does not certify recovery of the phone crash.

## Scope and baseline

Handoff SHA-256: `9a4efce9730e21092c3d79a9b22abaae244d4cc0374facf3d37704d8e112547c`
(20,800 bytes). Read-only discovery preceded changes.

Recovery base: `6a4fad743262c3620632a2fc42788563fc79b9a3`, verified with
`git ls-remote origin refs/heads/main refs/tags/synapse-private` on October 28.
Both live refs point there. Local `main` was behind at `c8e582c`; the canonical
checkout was on `codex/modern-messenger-cinder-pass` at `d285857`.
Recovery branch: `fix/firebase-chat-recovery-20261028`, in its own worktree.

All seven pre-existing worktrees were inventoried. `fix-launch-anr` and
`fix-post-login-anr` were clean. The latter's `3e3c409` startup-load fixes are
already ancestors of the recovery base. The Supabase migration worktree has
36 modified/untracked paths and the Supabase private worktree has one; neither
was edited. Canonical untracked `NUL` and
`docs/debug-artifact-2026-07-20-synapse-debug.md` were preserved.

## Evidence inventory

| Surface | Status | Evidence and limits |
| --- | --- | --- |
| GitHub source | verified | Repository read/push/admin permission reported; existing repository is public. No production merge or repository creation performed. |
| Build project | verified | `app/google-services.json` selects `synapse-chat-pjr-2026`, package `app.synapse.localllm.debug`. Debug and rolling variants use this package; nominal release uses a different package and is not the established upgrade route. |
| Published original APK | verified | `apk-latest/APK/Synapse-AI.apk`, 33,366,026 bytes, versionCode 2041, versionName 0.1.2041, package `app.synapse.localllm.debug`, min SDK 28, target SDK 36. Downloaded copy passed `apksigner verify`. |
| Published APK digest | verified | SHA-256 `871ab2543cddefe7f7f5b76d925acf05f966b2632da73f35dfa1ea5d2941877d`, matches GitHub release asset digest. |
| Published signer | verified | Certificate SHA-256 `6f762970e8c29b2c810cb790c1e08dbebf80e40f60a03516b7ca665964a14e7b`. No private signing key accessed. |
| Installed phone build | blocked | ADB completed with no devices attached. Installed version, signer, build SHA, update compatibility and fatal stack trace cannot be established. |
| Google project | verified | `gcloud projects describe` reports ACTIVE. |
| Billing link | verified | `gcloud billing projects describe` reports billingEnabled=true. This does not certify payment account standing or budgets. |
| Prior backend outage | failed | Eight Cloud Run requests on 2026-10-26 at 22:35:09Z returned HTTP 500 with explicit diagnostic: “The request failed because billing is disabled for this project.” |
| Functions | verified | Listing reports deployed functions ACTIVE, GEN_2. Invocation success as a legitimate client remains not tested. |
| Required APIs | verified | Enabled services include Identity Toolkit, Secure Token, Firestore, Functions, Run, Firebase Storage, FCM, Scheduler, Logging and Secret Manager. Secret values not accessed. |
| Authentication provider | verified | Email enabled with passwordRequired=true, via Identity Toolkit project config. |
| Accounts | verified | Three records. Two enabled synthetic-email identities: one ACTIVE USER and one ACTIVE OWNER, both claimsVersion=1 and mustChangePassword=false. Third enabled ordinary-email identity has no chat claims. No records changed. |
| Identity/profile linkage | verified | Both synthetic identities have matching username UID records and ACTIVE/allowed Firestore profiles with matching roles. Owner bootstrap receipt exists; USER bootstrap receipt is absent (404), which alone does not invalidate an account created through another workflow. |
| Firestore | verified | Native database in northamerica-northeast1, deletion protection enabled; five composite indexes READY. Admin reads do not establish client rules acceptance. |
| Scheduled cleanup | not tested | Two jobs ENABLED, but listing has no last-attempt timestamp and status code -1. This is not proof of successful execution. |
| Firestore backups | blocked | Regional backup listing returned no backups. No backup/export or restore test performed. Deletion protection is not a backup. |
| Real account token refresh | not tested | Admin inspection is not a user sign-in. No passwords reset, custom tokens minted or identities impersonated. |
| Two-account messaging | blocked | Needs authorized device sessions and explicit test-message acceptance. |

Cloud requests use existing authorized credentials without printing tokens.
Initial Identity Toolkit HTTP 403 was resolved by supplying the existing project
as `x-goog-user-project`; no permission change was necessary. Private account
identifiers, emails, message contents and credential values are omitted here.

The eight failing services were `listcinderparticipants`,
`getownerregistrationconfiguration`, `getowneroperationssummary`,
`getnotificationpreferences`, `getcinderavailability`, `getownprivacystate`,
`listowneraccounts`, and `listownerauditevents`. They establish a past billing
outage, not the cause of the current Android fatal exception. No supplied crash
time has yet been correlated with device logs.

## Code investigation and validation

`FirebaseRemoteAuthenticationGateway` owns translation from Firebase identity
and token claims into the app's authentication state. Its broad exception
handlers also catch coroutine cancellation. A cancelled restored-session fetch
can overwrite a subsequent SignedOut state with InvalidSession. This is a
separate reproducible candidate defect; it must not be presented as proof of
the reported phone crash.

The intended local fix is confined to the authentication adapter's cancellation
semantics. Claim parsing, roles, provisioning and remote authorization stay
fail closed. Regression tests must establish failure before the change and
pass afterward. Validation results will be recorded below when available.

Initial Gradle attempts stalled while connecting to stale Kotlin daemon
loopback endpoints (thread-dump evidence). Only this task's Gradle processes
were stopped. Validation uses `-Pkotlin.compiler.execution.strategy=in-process
--max-workers=2`; no repository configuration was changed for this host issue.

## Recovery and release gates

1. Gate 0 — blocked: source/project/published APK linked; installed phone build,
   signing custody authorization, recoverable backups and device identity remain
   unresolved.
2. Gate 1 — blocked: need sanitized Android fatal stack and exact failing auth
   call. Billing outage evidence and a cancellation test cannot substitute.
3. Gate 2 — not tested: legitimate OWNER and USER token refresh and client
   authorization must pass; no speculative account repairs are warranted.
4. Gate 3 — blocked: Samsung upgrade preserving app data; two-account send and
   receive; persistence/restart; network recovery; Local AI switching. Attachments,
   groups, calls, notifications and local model operations remain not tested.
5. Gate 4 — blocked by Gate 3 and owner repository-name decision. Keep both app
   modules and original source intact. Proposed private repository name from the
   brief: `synapse-chat-legacy-recovery`; not created or approved as final.
6. Gate 5 — blocked: signed release, real-device acceptance, operational ownership
   and budget/alert review remain outstanding.

## Next actions and rollback

Codex: complete isolated regression repair and local validation, then reviewable
commit/PR preparation. Peter: connect and authorize the Samsung device for ADB
and provide the approximate latest crash time. Capture only relevant sanitized
fatal frames, package/build information and auth error category.

Do not uninstall, clear app data, reset passwords, mutate claims, deploy,
merge, export production data or read signing secrets without the specific
approval required by the handoff. A release must match the installed signer,
use a higher versionCode, and pass a data-preserving upgrade test. Keep the
published APK/hash as a baseline, but do not assume Android permits downgrade
or that older code can read newer local schemas.

Local source rollback is reverting the recovery commits on the isolated branch.
No backend rollback is needed because no backend mutation was performed.
Repository separation should begin with a provenance-preserving full copy after
health acceptance; retain `app/`, `firebase/`, build infrastructure and pinned
llama.cpp, inspect all cross-module references, and validate both independent
builds before any deletion. New private repo creation and final name remain
owner-gated. Never conflate the original APK channel with Synapse Private assets.
